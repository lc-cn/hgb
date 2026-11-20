// Mirrors core/protocol CloudProtocol.kt: what the console reads from and sends to the server.

/** How this server lets people sign in; email needs a mail service. */
export interface AuthOptions { email: boolean; github: boolean }
export interface Account { userId: string; email: string | null; hasPassword: boolean; githubLogin: string | null; timeZone: string }
/** Another account the signed-in user proved is theirs; merging it moves its data here. */
export interface MergeSummary { email: string | null; githubLogin: string | null; devices: number; gameAccounts: number; plans: number }
export interface MergeOffer { token: string; other: MergeSummary }
export interface EmailBindResult { account?: Account | null; merge?: MergeOffer | null }
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
export interface Notice { id: string; ruleId: string; ruleName: string; at: number; title: string; detail: string; jobId?: string | null; deviceId?: string | null; accountId?: string | null; planId?: string | null; deliveries: Delivery[] }
export type ChannelKind = "DINGTALK" | "WECOM" | "BARK" | "EMAIL" | "WEBHOOK" | "LOCAL";
/** A channel; [filled] names its secret fields that are set (their values never come back), [fields] its plain ones. */
export interface Channel { id: string; kind: ChannelKind; name: string; enabled: boolean; rules: string[]; target: string; filled: string[]; fields: Record<string, string>; last?: Delivery | null }
export interface ChannelSave { kind: ChannelKind; name: string; enabled: boolean; rules: string[]; fields: Record<string, string>; clear?: string[] }
export type DeliveryState = "PENDING" | "SENT" | "FAILED";
export interface Delivery { id: string; channelId?: string | null; channelName: string; state: DeliveryState; attempts: number; at: number; nextAt?: number | null; error: string; test: boolean }
