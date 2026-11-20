import { useState } from "react";
import { api, enc } from "../../api/client";
import type { App, Device, GameAccount, Job } from "../../api/types";
import { Button, Muted, Result, RowLink } from "../../components/ui";
import { orAlert } from "../../components/useAction";
import { ACTIVE, jobSource, jobStatus, shortTime } from "../../format";
import { paths } from "../../router";
import s from "./Jobs.module.css";

/** The jobs an app holds: the one it is running (or was just handed), then the ones queued behind it. */
export function appJobs(jobs: Job[], deviceId: string, appKey: string): { current: Job | null; queued: Job[] } {
  const mine = jobs.filter((job) => job.deviceId === deviceId && job.appKey === appKey && ACTIVE.has(job.state));
  const current = mine.find((job) => job.state === "RUNNING") ?? mine.find((job) => job.state === "LEASED") ?? null;
  return { current, queued: mine.filter((job) => job !== current).reverse() };
}
/** Where a job ran, as the list shows it: the device, and the app when the device has more than one. */
export function jobPlace(job: Job, devices: Device[]): string {
  if (!job.deviceName) return job.state === "WAITING" ? "等可用应用" : "—";
  const device = devices.find((item) => item.id === job.deviceId);
  const app = device && device.apps.length > 1 ? device.apps.find((item) => item.key === job.appKey) : undefined;
  return app ? `${job.deviceName} · ${app.name}` : job.deviceName;
}
export const lastFinished = (jobs: Job[], keep: (job: Job) => boolean) => jobs.find((job) => keep(job) && !ACTIVE.has(job.state)) ?? null;

export function jobCommand(job: Job, command: "pause" | "resume" | "cancel") {
  return orAlert(() => api(`/api/jobs/${enc(job.id)}/${command}`, { method: "POST", body: {} }));
}
/** Pause or resume, and stop (or cancel before it starts), for a job that is still going. */
export function JobControls({ job, small }: { job: Job; small?: boolean }) {
  if (!ACTIVE.has(job.state) || job.cancelRequested) return null;
  return (
    <>
      {job.state === "RUNNING" && <Button small={small} onClick={() => jobCommand(job, job.pauseRequested ? "resume" : "pause")}>{job.pauseRequested ? "继续" : "暂停"}</Button>}
      <Button small={small} variant="danger" onClick={() => jobCommand(job, "cancel")}>{job.state === "RUNNING" ? "停止" : "取消"}</Button>
    </>
  );
}

export type RunColumn = "place" | "account" | "source";
/** Run records as rows that open the record. Newest first, as the server sends them. */
export function RunRows({ jobs, devices, columns, limit }: { jobs: Job[]; devices: Device[]; columns: RunColumn[]; limit?: number }) {
  const [all, setAll] = useState(false);
  const shown = limit && !all ? jobs.slice(0, limit) : jobs;
  return (
    <>
      {shown.map((job) => {
        const [label, tone] = jobStatus(job);
        return (
          <RowLink key={job.id} to={paths.job(job.id)}>
            <span className={s.task}>{job.taskName}{!columns.includes("account") && job.accountName ? ` · ${job.accountName}` : ""}</span>
            {columns.includes("account") && <span className={`${s.cell} ${s.narrow}`}>{job.accountName ?? "—"}</span>}
            {columns.includes("place") && <span className={`${s.cell} ${s.wide}`}>{jobPlace(job, devices)}</span>}
            {columns.includes("source") && <span className={s.cell}>{jobSource(job)}</span>}
            <span className={`${s.cell} ${s.time}`}>{shortTime(job.updatedAt)}</span>
            <span className={s.state}><Result tone={tone}>{label}</Result></span>
          </RowLink>
        );
      })}
      {limit && jobs.length > limit ? <div className={s.more}><Button variant="link" onClick={() => setAll(!all)}>{all ? "只看最近的" : `显示全部 ${jobs.length} 条`}</Button></div> : null}
    </>
  );
}

/**
 * Starts tasks now and keeps a note beside each one about what happened. [place] is the app to send it to, or none to
 * let the server pick an app signed in to the account (or one that can switch to it).
 */
export function useRunNow() {
  const [notes, setNotes] = useState<Record<string, { text: string; ok: boolean }>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const run = async (task: { targetId: string; taskId: string }, accountId: string, place: { deviceId: string; appKey: string } | null, done: string) => {
    const key = task.taskId; setBusy(key);
    try {
      await api("/api/jobs", { method: "POST", body: { targetId: task.targetId, taskId: task.taskId, accountId, deviceId: place?.deviceId ?? null, appKey: place?.appKey ?? null } });
      setNotes((now) => ({ ...now, [key]: { text: done, ok: true } }));
    } catch (error) {
      setNotes((now) => ({ ...now, [key]: { text: error instanceof Error ? error.message : "没有提交成功", ok: false } }));
    } finally { setBusy(null); }
  };
  return { notes, busy, run, clear: () => setNotes({}) };
}
export function Note({ note }: { note?: { text: string; ok: boolean } }) {
  return <span className={note && !note.ok ? s.noteBad : s.note} role="status">{note?.text ?? ""}</span>;
}

const nameOf = (accounts: GameAccount[], id: string | null) => accounts.find((item) => item.id === id)?.name ?? null;
/** What will happen when [accountId] runs on [app], following the server's switching rules. */
export function placeHint(app: App, accountId: string, accounts: GameAccount[]): string {
  const name = nameOf(accounts, accountId) ?? "这个账号";
  if (app.accountId === accountId) return `${app.name}登录着${name}，用${name}的设置，不切号。`;
  if (app.switchable === false) return `${app.name}不能切号，只有它登录着${name}时才能运行，否则作业会失败。`;
  const back = nameOf(accounts, app.defaultAccountId);
  return `会在${app.name}先切到${name}${back ? `，运行完切回${back}` : "，运行完不切回"}；用${name}的设置。`;
}
export const AUTO_HINT = "自动分配：优先交给登录着这个账号的应用；没有就找空闲、没设默认账号、能切号的应用；都没有就等，超过最晚开始时间不执行。";
export function Hint({ children }: { children: string }) { return <Muted as="span" className={s.hint}>{children}</Muted>; }
