import type { JobEvent, JobState, Plan } from "./api/types";

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
export function eventDetail(event: JobEvent): string {
  if (event.kind === "action") {
    const [result, ...rest] = event.detail.split("：");
    return [ACTION_RESULTS[result] ?? result, rest.join("：")].filter(Boolean).join("：");
  }
  return event.detail.replace(/^Completed\(evidence=(.*)\)$/, "完成：$1").replace(/^Unknown\(reason=(.*)\)$/, "结果未知：$1")
    .replace(/^Failed\(reason=(.*)\)$/, "失败：$1").replace(/^Interrupted$/, "已中断");
}
