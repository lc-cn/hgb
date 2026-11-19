import { useEffect, useMemo, useState } from "react";
import { api, enc } from "../../api/client";
import type { Device, Game, GameAccount, Job, JobEvent } from "../../api/types";
import { Button, Card, Field, Form, Grow, Message, Muted, Pill } from "../../components/ui";
import { orAlert, useAction } from "../../components/useAction";
import { ACTIVE, clock, dateTime, eventDetail, eventKind, JOB_STATES } from "../../format";
import s from "./Jobs.module.css";

/**
 * Runs a task now for one game account. "自动分配" lets the server pick an app signed in to the account; choosing an
 * app sends it there, which works only while that app is signed in to the account (switching comes later).
 */
export function JobForm({ devices, accounts, games }: { devices: Device[]; accounts: GameAccount[]; games: Game[] }) {
  const tasks = useMemo(() => games.flatMap((game) => game.tasks.map((task) => ({ ...task, game }))), [games]);
  // Kept by task, not by position, so a task list that changes underneath never swaps the chosen task.
  const keyOf = (item: { targetId: string; taskId: string }) => `${item.targetId}/${item.taskId}`;
  const [taskKey, setTaskKey] = useState("");
  const task = tasks.find((item) => keyOf(item) === taskKey) ?? tasks[0];
  const mine = accounts.filter((account) => account.gameId === task?.targetId);
  const [accountId, setAccountId] = useState("");
  const [where, setWhere] = useState("");
  const { busy, message, run } = useAction();
  // The choice follows the task: an account of another game, or an app that went offline, is not kept.
  const account = mine.some((item) => item.id === accountId) ? accountId : mine[0]?.id ?? "";
  const apps = devices.filter((device) => device.online).flatMap((device) => device.apps.filter((app) => app.gameId === task?.targetId)
    .map((app) => ({ value: JSON.stringify([device.id, app.key]), label: device.apps.length > 1 ? `${device.name} · ${app.name}` : device.name })));
  const place = apps.some((app) => app.value === where) ? where : "";
  if (tasks.length === 0) return <Card><Muted>设备上线并上报任务后，就可以在这里运行任务。</Muted></Card>;
  if (accounts.length === 0) return <Card><Muted>先在“游戏账号”里添加账号，任务会用这个账号的设置运行。</Muted></Card>;
  const submit = () => run(async () => {
    const [deviceId, appKey] = place ? JSON.parse(place) as [string, string] : [null, null];
    await api("/api/jobs", { method: "POST", body: { targetId: task.targetId, taskId: task.taskId, accountId: account, deviceId, appKey } });
    return "已提交，进度见作业记录";
  });
  return (
    <Card>
      <Form inline onSubmit={submit} className={s.newJob}>
        <Field label="任务">
          <select required value={task ? keyOf(task) : ""} onChange={(e) => setTaskKey(e.target.value)}>
            {tasks.map((item) => <option key={keyOf(item)} value={keyOf(item)}>{games.length > 1 ? `${item.game.name} · ${item.name}` : item.name}</option>)}
          </select>
        </Field>
        <Field label="账号">
          <select required value={account} onChange={(e) => setAccountId(e.target.value)}>
            {mine.length ? mine.map((item) => <option key={item.id} value={item.id}>{item.name}</option>) : <option value="">这个游戏还没有账号</option>}
          </select>
        </Field>
        <Field label="在哪运行">
          <select value={place} onChange={(e) => setWhere(e.target.value)}>
            <option value="">自动分配（登录着这个账号的应用）</option>
            {apps.map((app) => <option key={app.value} value={app.value}>{app.label}</option>)}
          </select>
        </Field>
        <button className={s.run} type="submit" disabled={busy}>运行</button>
      </Form>
      <Message text={message?.text} ok={message?.ok} />
    </Card>
  );
}

export function JobList({ jobs, logVersion, epoch }: { jobs: Job[]; logVersion: Record<string, number>; epoch: number }) {
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (id: string) => setOpen((now) => { const next = new Set(now); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  if (jobs.length === 0) return <Card><p className={s.empty}>还没有作业</p></Card>;
  return (
    <Card flush>
      {jobs.map((job) => <JobRow key={job.id} job={job} open={open.has(job.id)} toggle={() => toggle(job.id)} logVersion={`${epoch}.${logVersion[job.id] ?? 0}`} />)}
    </Card>
  );
}

function JobRow({ job, open, toggle, logVersion }: { job: Job; open: boolean; toggle: () => void; logVersion: string }) {
  const [label, tone] = JOB_STATES[job.state];
  const active = ACTIVE.has(job.state);
  const command = (path: string) => orAlert(() => api(`/api/jobs/${enc(job.id)}/${path}`, { method: "POST", body: {} }));
  const where = job.deviceName ?? (job.state === "WAITING" && job.latestStartAt ? `最晚 ${clock(job.latestStartAt)} 前开始` : null);
  const shown = job.cancelRequested && active ? "取消中" : job.state === "RUNNING" && job.pauseRequested ? "暂停中" : label;
  return (
    <div className={s.wrap}>
      <div className={s.job}>
        <Grow>
          <div className={s.name}>{job.planName ? `${job.taskName} · 计划“${job.planName}”` : job.taskName}</div>
          <Muted>{[job.accountName, where, dateTime(job.updatedAt), job.detail].filter(Boolean).join(" · ")}</Muted>
        </Grow>
        <Pill tone={tone}>{shown}</Pill>
        {job.state === "RUNNING" && !job.cancelRequested && <Button onClick={() => command(job.pauseRequested ? "resume" : "pause")}>{job.pauseRequested ? "继续" : "暂停"}</Button>}
        <Button aria-expanded={open} onClick={toggle}>{open ? "收起" : "明细"}</Button>
        {active && !job.cancelRequested && <Button onClick={() => command("cancel")}>{job.state === "RUNNING" ? "停止" : "取消"}</Button>}
      </div>
      {open && <JobLog job={job} version={logVersion} />}
    </div>
  );
}

/** The steps and actions the device reported; read again when the stream says the log grew. */
function JobLog({ job, version }: { job: Job; version: string }) {
  const [events, setEvents] = useState<JobEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    api<JobEvent[]>(`/api/jobs/${enc(job.id)}/events`).then((list) => { if (current) { setEvents(list); setError(null); } })
      .catch((failure: Error) => { if (current) setError(failure.message); });
    return () => { current = false; };
  }, [job.id, version]);
  if (error) return <div className={s.log}><Muted>{error}</Muted></div>;
  if (events === null) return null;
  if (events.length === 0) return <div className={s.log}><Muted>{ACTIVE.has(job.state) ? "设备还没有上报明细" : "这次作业没有明细"}</Muted></div>;
  return (
    <div className={s.log}>
      {events.map((event, i) => (
        <div key={i} className={event.kind === "action" ? s.line : `${s.line} ${s.fact}`}>
          <span className={s.time}>{clock(event.at)}</span>
          <span>{[event.stepName, eventKind(event.kind), event.action].filter(Boolean).join(" · ")}</span>
          <span className={s.detail}>{[eventDetail(event), event.elapsedMs ? `${event.elapsedMs} ms` : ""].filter(Boolean).join(" · ")}</span>
        </div>
      ))}
    </div>
  );
}
