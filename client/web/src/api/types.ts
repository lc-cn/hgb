// Mirrors core/protocol CloudProtocol.kt: what the console reads from and sends to the server.

export interface Account { userId: string; email: string | null; hasPassword: boolean; githubLogin: string | null; timeZone: string }
export interface CloudTask { targetId: string; taskId: string; name: string }
/** The account the app last read on screen. */
export interface ObservedAccount { uid: string; nickname: string; at: number }
export interface App {
  key: string; gameId: string; name: string; phase: string; currentTask: string | null; queued: number;
  accountId: string | null; defaultAccountId: string | null; switchable?: boolean; observed?: ObservedAccount | null;
}
export interface Device {
  id: string; name: string; platform: string; online: boolean; lastSeenAt: number | null; phase: string | null;
  currentTask: string | null; queued: number; tasks: CloudTask[]; apps: App[]; concurrency: number;
}
export type JobState = "WAITING" | "QUEUED" | "LEASED" | "RUNNING" | "COMPLETED" | "FAILED" | "UNKNOWN" | "CANCELLED" | "SKIPPED";
export interface Job {
  id: string; deviceId: string | null; deviceName: string | null; targetId: string; taskId: string; taskName: string;
  state: JobState; detail: string; cancelRequested: boolean; createdAt: number; updatedAt: number;
  accountId: string | null; accountName: string | null; appKey: string | null; planId: string | null; planName: string | null;
  latestStartAt: number | null; pauseRequested: boolean;
}
export interface JobEvent { at: number; kind: string; step: string; stepName: string; action: string; detail: string; elapsedMs: number }
export interface GameAccount { id: string; gameId: string; name: string; uid: string | null; createdAt: number; nickname?: string | null }
export interface Game { id: string; name: string; tasks: CloudTask[] }
export interface Plan {
  id: string; accountId: string; name: string; days: number[]; minuteOfDay: number; tasks: CloudTask[]; deviceIds: string[];
  latestStartMinutes: number; enabled: boolean; revision: number; updatedAt: number;
}
export interface PlanSave {
  name: string; days: number[]; minuteOfDay: number; tasks: CloudTask[]; deviceIds: string[]; latestStartMinutes: number; enabled: boolean; revision: number;
}
export type Widget = "SINGLE_CHOICE" | "MULTI_CHOICE" | "SWITCH" | "NUMBER" | "RANGE" | "TEXT" | "TIME" | "DATE" | string;
export interface FieldOption { value: string; label: string }
export interface SettingField {
  id: string; label: string; widget: Widget; required: boolean; description?: string; options?: FieldOption[];
  minimum?: number; maximum?: number; step?: number; recommended?: unknown; readOnly?: boolean;
}
export interface ConfigView {
  accountId: string; targetId: string; taskId: string; taskName: string; version: string; fields: SettingField[];
  values: Record<string, unknown>; revision: number;
}
export interface ConsoleSnapshot {
  devices: Device[]; jobs: Job[]; accounts: GameAccount[]; plans: Plan[]; games: Game[]; timeZone: string;
}

/** How an event rule decides; see EventRuleView on the server. */
export type RuleKind = "INSTANT" | "COUNT" | "DURATION" | "RESULT";
export type PlanOutcome = "COMPLETED" | "PARTIAL_FAILED" | "FAILED" | "CANCELLED";
/** One of the user's event rules; [threshold] is minutes or a count by [kind], [suggested] only fills the editor. */
export interface EventRule { ruleId: string; name: string; kind: RuleKind; description: string; enabled: boolean; threshold?: number | null; suggested?: number | null; results: PlanOutcome[] }
/** Something a rule noticed. */
export interface Notice { id: string; ruleId: string; ruleName: string; at: number; title: string; detail: string; jobId?: string | null; deviceId?: string | null; accountId?: string | null; planId?: string | null }
