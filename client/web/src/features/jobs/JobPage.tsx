import { useEffect, useState } from "react";
import { api, enc } from "../../api/client";
import type { Job, JobEvent } from "../../api/types";
import { Breadcrumb, Button, ButtonLink, Card, Empty, Muted, Pill } from "../../components/ui";
import { useAction } from "../../components/useAction";
import { ACTIVE, clock, dateTime, eventDetail, eventKind, jobSource, jobStatus, seconds, shortDuration } from "../../format";
import { paths } from "../../router";
import { useConsole } from "../../state/context";
import { NotFound } from "../ConsolePage";
import { jobPlace, JobControls } from "./runs";
import s from "./Jobs.module.css";

type StepState = "done" | "stop" | "running" | "plain";
interface Step { stepId: string; key: string; name: string; events: JobEvent[]; state: StepState; summary: string; tookMs: number }

/**
 * Groups the log by the step it belongs to, in the order the steps ran, and reads each step's outcome from its events.
 * Each start of a step opens its own group; a step's later lines (its result, the check that its input stopped) can
 * arrive after the next step has started, so they join the latest group of the same step rather than the last group.
 */
function stepsOf(events: JobEvent[], job: Job): Step[] {
  const steps: Step[] = [];
  for (const event of events) {
    const key = event.step || "-";
    // A start joins only a group of the same step that has not started yet (the run's queued line); anything else
    // joins the step's latest group.
    let step = [...steps].reverse().find((item) => item.stepId === key &&
      (event.kind !== "step-started" || !item.events.some((seen) => seen.kind === "step-started")));
    if (event.kind === "step-started" && step && step !== steps[steps.length - 1]) step = undefined;
    if (!step) { step = { stepId: key, key: `${key}#${steps.length}`, name: event.stepName || "作业", events: [], state: "plain", summary: "", tookMs: 0 }; steps.push(step); }
    step.events.push(event);
  }
  steps.forEach((step, i) => {
    const kinds = new Set(step.events.map((event) => event.kind));
    const last = i === steps.length - 1;
    // The step's own result says more than the later check that its input stopped.
    const facts = [...step.events].reverse().filter((event) => event.kind !== "action" && event.detail);
    const fact = facts.find((event) => event.kind !== "revocation-observed") ?? facts[0] ?? step.events[step.events.length - 1];
    step.tookMs = step.events[step.events.length - 1].at - step.events[0].at;
    step.summary = fact ? [eventKind(fact.kind), eventDetail(fact)].filter(Boolean).join("：") : "";
    if (kinds.has("task-failed") || kinds.has("cancelled") || kinds.has("input-unconfirmed")) step.state = "stop";
    else if (kinds.has("step-completed")) step.state = "done";
    else if (last && ACTIVE.has(job.state)) step.state = "running";
    else if (last && !ACTIVE.has(job.state) && job.state !== "COMPLETED") step.state = "stop";
  });
  return steps;
}
const MARK: Record<StepState, string> = { done: "✓", stop: "!", running: "…", plain: "" };

/** 运行记录详情: what the job was, how it ended, and every step and action the device reported. */
export function JobPage({ id }: { id: string }) {
  const { data } = useConsole();
  const job = data.jobs.find((item) => item.id === id);
  const version = `${data.epoch}.${data.logVersion[id] ?? 0}`;
  const [events, setEvents] = useState<JobEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [all, setAll] = useState(false);
  const again = useAction();
  useEffect(() => {
    let current = true;
    api<JobEvent[]>(`/api/jobs/${enc(id)}/events`).then((list) => { if (current) { setEvents(list); setError(null); } })
      .catch((failure: Error) => { if (current) setError(failure.message); });
    return () => { current = false; };
  }, [id, version]);
  if (!job) return <NotFound text="找不到这条运行记录" />;
  const [label, tone] = jobStatus(job);
  const device = data.devices.find((item) => item.id === job.deviceId);
  const app = device?.apps.find((item) => item.key === job.appKey);
  const steps = events ? stepsOf(events, job) : [];
  const stopped = steps.find((step) => step.state === "stop");
  const started = events?.find((event) => event.kind === "step-started")?.at;
  const ended = events?.length ? events[events.length - 1].at : null;
  const crumbs: [string, string | null][] = device
    ? [["设备", paths.devices], [device.name, paths.device(device.id)], ...(app ? [[app.name, paths.app(device.id, app.key)] as [string, string]] : []), ["运行记录", null]]
    : job.accountId ? [["游戏账号", paths.accounts], [job.accountName ?? "账号", paths.account(job.accountId, "runs")], ["运行记录", null]] : [["设备", paths.devices], ["运行记录", null]];
  const toggle = (key: string) => { setAll(false); setOpen((now) => { const next = new Set(now); if (next.has(key)) next.delete(key); else next.add(key); return next; }); };
  const accountExists = data.accounts.some((item) => item.id === job.accountId);
  return (
    <>
      <Breadcrumb items={crumbs} />
      <header className={s.jobHeader}>
        <div className={s.jobTitle}>
          <div className={s.titleLine}><h1>{job.taskName}</h1><Pill tone={tone}>{label}</Pill></div>
          <Muted>{[jobSource(job), job.accountName ? `游戏账号：${job.accountName}` : null, jobPlace(job, data.devices),
            started ? `${dateTime(started)} 开始` : `${dateTime(job.createdAt)} 创建`, started && ended && !ACTIVE.has(job.state) ? `用时 ${seconds(ended - started)}` : null].filter(Boolean).join(" · ")}</Muted>
        </div>
        <div className={s.jobActions}>
          {job.accountId && accountExists && <ButtonLink to={paths.account(job.accountId)}>修改设置</ButtonLink>}
          <JobControls job={job} />
          {!ACTIVE.has(job.state) && job.accountId && accountExists && (
            <Button variant="primary" className={s.big} disabled={again.busy} onClick={() => again.run(async () => {
              await api("/api/jobs", { method: "POST", body: { targetId: job.targetId, taskId: job.taskId, accountId: job.accountId, deviceId: null, appKey: null } });
              return "已提交，服务端会按账号分配应用";
            })}>再运行一次</Button>
          )}
        </div>
      </header>
      {again.message && <p role="status" className={again.message.ok ? s.noteOk : s.noteBad}>{again.message.text}</p>}
      {(job.state === "FAILED" || job.state === "UNKNOWN" || job.state === "SKIPPED" || (job.state === "CANCELLED" && stopped)) && (
        <section aria-labelledby="why" className={s.why}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6M12 17h.01" /></svg>
          <div>
            <h2 id="why">{stopped ? `停在“${stopped.name}”：${label}` : label}</h2>
            <p>{job.detail || stopped?.summary || "设备没有说明原因。"}</p>
          </div>
        </section>
      )}
      <section aria-labelledby="steps" className={s.stepsSection}>
        <div className={s.stepsHead}>
          <h2 id="steps">步骤</h2>
          {steps.length > 0 && <Button small onClick={() => { setAll(!all); setOpen(new Set()); }}>{all ? "收起全部" : "展开全部"}</Button>}
        </div>
        {error ? <Card><Empty>{error}</Empty></Card> : events === null ? <Card><Empty>正在读取…</Empty></Card> : steps.length === 0 ? (
          <Card><Empty>{ACTIVE.has(job.state) ? "设备还没有上报明细" : "这次运行没有明细"}</Empty></Card>
        ) : (
          <ol className={s.steps}>
            {steps.map((step, i) => {
              const shown = all || open.has(`${i}`);
              return (
                <li key={i} className={step.state === "stop" ? `${s.step} ${s.stepStop}` : s.step}>
                  <button type="button" className={s.stepButton} aria-expanded={shown} onClick={() => toggle(`${i}`)}>
                    <span aria-hidden="true" className={`${s.stepMark} ${s[`mark_${step.state}`]}`}>{MARK[step.state]}</span>
                    <span className={s.stepText}><b>{step.name}</b><span>{step.summary}</span></span>
                    <span className={s.took}>{step.tookMs > 0 ? seconds(step.tookMs) : ""}</span>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" className={s.chevron}><path d={shown ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6"} /></svg>
                  </button>
                  {shown && (
                    <ul className={s.actionsList}>
                      {step.events.map((event, j) => (
                        <li key={j} className={event.kind === "action" ? undefined : s.factLine}>
                          <span className={s.at}>{clock(event.at)}</span>
                          <span className={s.what}>{event.kind === "action" ? event.action : eventKind(event.kind)}</span>
                          <span className={event.kind === "action" && /^(UNAVAILABLE|INTERRUPTED)/.test(event.detail) ? s.resultWarn : s.resultText}>{eventDetail(event)}</span>
                          <span className={s.at}>{event.elapsedMs ? shortDuration(event.elapsedMs) : ""}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              );
            })}
          </ol>
        )}
      </section>
    </>
  );
}
