import type { Job, JobEvent, JobState, Plan } from "./api/types";

export type Tone = "" | "ok" | "info" | "warn" | "bad";
export const JOB_STATES: Record<JobState, [string, Tone]> = {
  WAITING: ["等可用应用", "info"], QUEUED: ["排队中", "info"], LEASED: ["已下发", "info"], RUNNING: ["运行中", "info"], COMPLETED: ["已完成", "ok"],
  FAILED: ["失败", "bad"], UNKNOWN: ["结果未知", "warn"], CANCELLED: ["已取消", ""], SKIPPED: ["已跳过", "warn"],
};
export const ACTIVE = new Set<JobState>(["WAITING", "QUEUED", "LEASED", "RUNNING"]);
export const PHASES: Record<string, string> = {
  CONNECTING: "连接中", IDLE: "空闲", RUNNING: "运行中", PAUSING: "正在暂停", PAUSED: "已暂停", REMOVING: "正在停止", SWITCHING: "切换中", BLOCKED_UNKNOWN: "需要处理",
};
export const dateTime = (value: number | null | undefined) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
export const clock = (value: number) => new Date(value).toLocaleTimeString("zh-CN", { hour12: false });

export const WEEK = ["一", "二", "三", "四", "五", "六", "日"];
export const clockText = (minute: number) => `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
export function planRule(plan: Plan): string {
  const days = plan.days.length === 7 ? "每天" : `每周${plan.days.map((day) => WEEK[day - 1]).join("、")}`;
  return `${days} ${clockText(plan.minuteOfDay)}`;
}

const EVENT_KINDS: Record<string, string> = {
  "enqueued": "已排队", "step-started": "开始步骤", "step-observed": "步骤结果", "step-completed": "步骤完成", "task-completed": "任务完成",
  "task-failed": "任务失败", "task-restarted": "任务重来", "run-ended": "运行结束", "cancelled": "已取消", "input-unconfirmed": "输入未确认",
  "result-resolved": "结果已确认", "revocation-observed": "停止确认", "action": "动作",
};
const ACTION_RESULTS: Record<string, string> = { PRODUCED: "完成", VERIFIED: "核对通过", UNAVAILABLE: "未通过", INTERRUPTED: "中断" };
export const eventKind = (kind: string) => EVENT_KINDS[kind] ?? kind;
/** Makes the executor's wording readable without changing what it says. */
const RUN_ENDS: Record<string, string> = { COMPLETED: "已完成", FAILED: "失败", CANCELLED: "已停止", UNKNOWN: "结果未知", INTERRUPTED: "已中断" };
export function eventDetail(event: JobEvent): string {
  if (event.kind === "action") {
    const [result, ...rest] = event.detail.split("：");
    return [ACTION_RESULTS[result] ?? result, rest.join("：")].filter(Boolean).join("：");
  }
  // A run's end names its state the device's way (COMPLETED, FAILED, …).
  if (event.kind === "run-ended") return RUN_ENDS[event.detail] ?? event.detail;
  return event.detail.replace(/^Completed\(evidence=(.*)\)$/, "完成：$1").replace(/^Unknown\(reason=(.*)\)$/, "结果未知：$1")
    .replace(/^Failed\(reason=(.*)\)$/, "失败：$1").replace(/^Interrupted$/, "已中断");
}

/** A time in a list: today shows the clock, yesterday says so, older ones add the date. */
export function shortTime(value: number | null | undefined): string {
  if (!value) return "—";
  const at = new Date(value); const now = new Date();
  const hm = at.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit" });
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((day(now) - day(at)) / 86400000);
  if (days === 0) return hm;
  if (days === 1) return `昨天 ${hm}`;
  return at.getFullYear() === now.getFullYear() ? `${at.getMonth() + 1}月${at.getDate()}日 ${hm}` : `${at.getFullYear()}年${at.getMonth() + 1}月${at.getDate()}日`;
}
export const seconds = (ms: number) => ms < 60000 ? `${Math.max(1, Math.round(ms / 1000))} 秒` : `${Math.floor(ms / 60000)} 分 ${Math.round((ms % 60000) / 1000)} 秒`;

const PLATFORMS: Record<string, string> = { android: "Android", windows: "Windows 桌面端", ios: "iOS" };
export const platformName = (platform: string) => PLATFORMS[platform.toLowerCase()] ?? platform;

/** Where a job came from, as far as the server records it: a plan, or a run started by hand. */
export const jobSource = (job: Job) => job.planName ? `计划 · ${job.planName}` : "立即运行";
/** The job's state as the user should read it, taking pending pause and cancel requests into account. */
export function jobStatus(job: Job): [string, Tone] {
  const [label, tone] = JOB_STATES[job.state];
  if (job.cancelRequested && ACTIVE.has(job.state)) return ["正在停止", "warn"];
  if (job.state === "RUNNING" && job.pauseRequested) return ["已暂停", "warn"];
  return [label, tone];
}
/** How an app is doing, as a pill. */
export function appStatus(phase: string, online: boolean): [string, Tone] {
  if (!online) return ["离线", ""];
  const label = PHASES[phase] ?? phase;
  if (phase === "RUNNING" || phase === "SWITCHING") return [label, "info"];
  if (phase === "IDLE") return [label, "ok"];
  if (phase === "BLOCKED_UNKNOWN") return [label, "bad"];
  if (phase === "PAUSED" || phase === "PAUSING" || phase === "REMOVING") return [label, "warn"];
  return [label, ""];
}
export const shortDuration = (ms: number) => ms < 10000 ? `${(ms / 1000).toFixed(1)} 秒` : seconds(ms);
