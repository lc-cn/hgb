package io.github.yysassist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Wire format shared by the cloud service and the app. Secrets never appear in toString-visible logs. */
@Serializable data class RegisterRequest(val email: String, val password: String)
@Serializable data class VerifyEmailRequest(val email: String, val code: String)
@Serializable data class PasswordLoginRequest(val email: String, val password: String)
@Serializable data class CodeRequest(val email: String)
@Serializable data class CodeLoginRequest(val email: String, val code: String)
@Serializable data class PasswordResetRequest(val email: String, val code: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class GitHubTokenRequest(val accessToken: String)
/** How this server lets people sign in: [email] needs a mail service (codes, sign-up, reset), [github] a GitHub App. */
@Serializable data class AuthOptions(val email: Boolean, val github: Boolean)

/** Tokens are only returned to device clients; browsers receive an HttpOnly cookie instead. */
@Serializable data class AuthResult(val userId: String, val email: String? = null, val accessToken: String? = null,
    val refreshToken: String? = null, val accessExpiresAt: Long? = null)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class AccountView(val userId: String, val email: String?, val hasPassword: Boolean, val githubLogin: String?, val timeZone: String = "Asia/Shanghai")
/** The other account a sign-in method already belongs to, shown before the two are merged into the signed-in one. */
@Serializable data class MergeSummary(val email: String?, val githubLogin: String?, val devices: Int, val gameAccounts: Int, val plans: Int)
/** Proof that the signed-in user also owns another account; [token] merges it within ten minutes. */
@Serializable data class MergeOffer(val token: String, val other: MergeSummary)
/** Binding an email either succeeds ([account]) or finds the address on another account of the user's ([merge]). */
@Serializable data class EmailBindResult(val account: AccountView? = null, val merge: MergeOffer? = null)
/** [confirm] must be the word 注销; [code] is the emailed code when the account has an email. */
@Serializable data class AccountDeletion(val confirm: String, val code: String? = null)

@Serializable data class DeviceRegistration(val installId: String, val name: String, val platform: String)
@Serializable data class DeviceRegistered(val deviceId: String, val userId: String)
@Serializable data class CloudTask(val targetId: String, val taskId: String, val name: String)
/** The task bundle a device runs. [digest] is the SHA-256 (hex) of the bundle text. */
@Serializable data class CatalogRef(val version: String, val digest: String)
/** Sent once per digest, when the cloud asks for it, so the cloud can read the settings each task declares. */
@Serializable data class CatalogUpload(val version: String, val digest: String, val bundle: String)
/**
 * One game instance on a device, the unit that runs tasks. [key] is stable on that device (the Android package, later a
 * window or clone id); [gameId] is the task bundle's target id.
 */
/**
 * One game instance on the device. [switchable] when its task version can identify and switch accounts; [observed] is
 * the account the app last read on screen, which the server matches to a saved game account.
 */
@Serializable data class AppStatus(val key: String, val gameId: String, val name: String, val phase: String = "IDLE",
    val currentTask: String? = null, val queued: Int = 0, val switchable: Boolean = false, val observed: ObservedAccount? = null)
/** An account as the game shows it: its number and in-game nickname, and when it was read. */
@Serializable data class ObservedAccount(val uid: String, val nickname: String, val at: Long)
/** The account a job must run on; the device makes sure the game is signed in to it, switching by [nickname]. */
@Serializable data class AccountTarget(val accountId: String, val name: String, val uid: String, val nickname: String)
/** Everything a device reports about itself. Sent whole, on connect, on change and at least every few seconds. */
@Serializable data class DeviceStatus(val phase: String, val currentTask: String? = null, val queued: Int = 0,
    val tasks: List<CloudTask> = emptyList(), val appVersion: String = "", val catalog: CatalogRef? = null,
    val apps: List<AppStatus> = emptyList(), val concurrency: Int = 1)

/** Messages a device sends on its socket (`/api/device/socket`). */
@Serializable sealed interface DeviceMessage {
    @Serializable @SerialName("status") data class Status(val status: DeviceStatus) : DeviceMessage
}
/** Messages the server sends on a device's socket. */
@Serializable sealed interface ServerMessage {
    /** The server does not have the task bundle the device reported; upload it over HTTP. */
    @Serializable @SerialName("catalog-needed") data object CatalogNeeded : ServerMessage
    /** Stop these jobs if they are running here. */
    @Serializable @SerialName("cancel") data class Cancel(val jobIds: List<String>) : ServerMessage
    /** Run this job. The server sends a device one job at a time and resends it until the device reports it started. */
    @Serializable @SerialName("job") data class Job(val lease: JobLease) : ServerMessage
    /** Pause or resume the job if it is the one running here. */
    @Serializable @SerialName("pause") data class Pause(val jobId: String) : ServerMessage
    @Serializable @SerialName("resume") data class Resume(val jobId: String) : ServerMessage
    /** Everything the app shows about accounts, settings and plans, sent whole on connect and after each change. */
    @Serializable @SerialName("data") data class Data(val data: DeviceData) : ServerMessage
    /** Show this notice on the device: a local-notification channel for this device is subscribed to its rule. */
    @Serializable @SerialName("notify") data class Notify(val notice: DeviceNotice) : ServerMessage
    /** A developer workbench command for this phone; it answers with a [DevReport]. */
    @Serializable @SerialName("dev") data class Dev(val command: DevCommand) : ServerMessage
}

/**
 * SAMPLE: capture the game, read the whole frame and add it to the author's samples as [name].
 * RUN: run [task] of [bundle] (a bundle the workbench compiled: a trial, a probe or a judgement) once, with
 * [configuration], against the screen or, with [sample], against that sample shown as a still screen that no input
 * changes. Assets the bundle names without a `release` come from the workbench.
 */
/** SAMPLE: capture the screen as a new sample. RUN: run a compiled task. RECOGNIZE: read the text of an existing sample (one uploaded from elsewhere). */
@Serializable enum class DevKind { SAMPLE, RUN, RECOGNIZE }
@Serializable data class DevCommand(val id: String, val kind: DevKind, val target: String, val name: String = "", val bundle: String? = null,
    val task: String? = null, val configuration: JsonObject = JsonObject(emptyMap()), val sample: String? = null, val timeoutMs: Long = 600_000)
@Serializable enum class DevState { RUNNING, FINISHED, FAILED }
/** One action as it ran. [trace] keeps numbers, states and template ids only: recognized text never leaves the phone. */
@Serializable data class DevTrace(val step: String, val action: String, val capability: String, val result: String, val reason: String? = null,
    val elapsedMs: Long = 0, val trace: JsonObject? = null)
@Serializable data class DevFact(val type: String, val detail: String = "")
@Serializable data class DevReport(val state: DevState, val detail: String = "", val sample: String? = null,
    val facts: List<DevFact> = emptyList(), val traces: List<DevTrace> = emptyList())
/** Close codes the server uses when it refuses a device socket. */
object SocketClose { const val INVALID_SESSION: Short = 4401; const val NOT_A_DEVICE: Short = 4403; const val REPLACED: Short = 4409 }

/**
 * WAITING: created but no app can take it yet; QUEUED: assigned to an app and waiting for its turn; LEASED: sent to the
 * device, not yet acknowledged; SKIPPED: never started before its latest start time.
 */
@Serializable enum class JobState { WAITING, QUEUED, LEASED, RUNNING, COMPLETED, FAILED, UNKNOWN, CANCELLED, SKIPPED }

/**
 * Permission to run one job. [leaseNo] rises on every grant, so a stale holder can never report. The job runs on
 * task bundle [digest] with exactly [configuration], the settings saved in the cloud when the job was created.
 */
@Serializable data class JobLease(val jobId: String, val leaseNo: Long, val userId: String, val deviceId: String,
    val targetId: String, val taskId: String, val expiresAt: Long, val version: String, val digest: String, val configuration: JsonObject,
    /** Present when the account is known by number: the device checks it, and switches to it first when needed. */
    val account: AccountTarget? = null,
    /** The app's default account, to switch back to after this job; absent when the next job uses the same account. */
    val restore: AccountTarget? = null)
/** [restoreIssue] says why the app could not return to its default account after the job, when it could not. */
@Serializable data class JobReport(val leaseNo: Long, val state: JobState, val detail: String = "", val restoreIssue: String? = null)

/**
 * One line of a job's run log. [key] names the record on the device ("fact:12", "action:340") so a resend after a
 * lost reply is stored once. [kind] is an executor fact type, or "action" for one performed action.
 */
@Serializable data class JobEvent(val key: String, val at: Long, val kind: String, val step: String = "", val action: String = "",
    val detail: String = "", val elapsedMs: Long = 0)
@Serializable data class JobEvents(val leaseNo: Long, val events: List<JobEvent>)
/** A stored log line as the console shows it, with the step's name from the job's task version. */
@Serializable data class JobEventView(val at: Long, val kind: String, val step: String, val stepName: String, val action: String,
    val detail: String, val elapsedMs: Long)
/**
 * A job runs a task for one game account, with that account's settings. Without [deviceId] the server picks an app:
 * one signed in to the account. With [deviceId] (and [appKey] when the device has several apps of the game) it goes there.
 */
@Serializable data class CreateJobRequest(val targetId: String, val taskId: String, val accountId: String, val deviceId: String? = null, val appKey: String? = null)
/** A task's settings for one game account, with the field definitions of the newest uploaded task version that has the task. */
@Serializable data class ConfigView(val accountId: String, val targetId: String, val taskId: String, val taskName: String, val version: String,
    val fields: JsonArray, val values: JsonObject, val revision: Long)
/** [revision] is the one the editor started from; a newer save in between is refused rather than overwritten. */
@Serializable data class ConfigSave(val values: JsonObject, val revision: Long)

/** A game account; settings, plans and run records belong to it. [uid] is optional until the app can read it. */
/** [uid] is the game's own number for the account and [nickname] its name in the game; both are needed to switch to it. */
@Serializable data class GameAccountView(val id: String, val gameId: String, val name: String, val uid: String? = null, val createdAt: Long,
    val nickname: String? = null)
@Serializable data class GameAccountSave(val gameId: String = "", val name: String, val uid: String? = null, val nickname: String? = null)
/** A game the user's devices can run, from the task bundles they reported. */
@Serializable data class GameView(val id: String, val name: String, val tasks: List<CloudTask>)

/**
 * A plan of one game account, timed by the server in the user's time zone. [days] are ISO weekdays 1 (Monday) to 7;
 * [minuteOfDay] counts from midnight. [deviceIds] empty means any device with the game. A run that has not started
 * [latestStartMinutes] after its time is skipped.
 */
@Serializable data class PlanView(val id: String, val accountId: String, val name: String, val days: List<Int>, val minuteOfDay: Int,
    val tasks: List<CloudTask>, val deviceIds: List<String>, val latestStartMinutes: Int, val enabled: Boolean, val revision: Long, val updatedAt: Long)
/** [revision] 0 creates a plan; otherwise it must match the stored one. */
@Serializable data class PlanSave(val name: String, val days: List<Int>, val minuteOfDay: Int, val tasks: List<CloudTask>,
    val deviceIds: List<String> = emptyList(), val latestStartMinutes: Int = 30, val enabled: Boolean = true, val revision: Long = 0)

/** An app as the console shows it: what the device reports plus the accounts the user assigned to it. */
@Serializable data class AppView(val key: String, val gameId: String, val name: String, val phase: String, val currentTask: String?, val queued: Int,
    val accountId: String? = null, val defaultAccountId: String? = null, val switchable: Boolean = false, val observed: ObservedAccount? = null)
/** Until the app can tell which account is signed in, the user says so. Null clears it. */
@Serializable data class AppAccounts(val accountId: String? = null, val defaultAccountId: String? = null)

@Serializable data class DeviceView(val id: String, val name: String, val platform: String, val online: Boolean, val lastSeenAt: Long?,
    val phase: String?, val currentTask: String?, val queued: Int, val tasks: List<CloudTask>, val apps: List<AppView> = emptyList(), val concurrency: Int = 1)
@Serializable data class TimeZoneSave(val timeZone: String)
/** One task's saved settings for one game account. */
@Serializable data class AccountConfig(val accountId: String, val targetId: String, val taskId: String, val values: JsonObject, val revision: Long)
/** The jobs of a plan's latest run, one per task, so the app can show how it went. */
@Serializable data class PlanRun(val planId: String, val at: Long, val states: List<JobState>, val detail: String = "")
/**
 * What the app keeps of the server's data: it shows and runs with it, and keeps it for when it is offline. [apps] are
 * this device's, with the accounts the user assigned to them.
 */
@Serializable data class DeviceData(val accounts: List<GameAccountView>, val apps: List<AppView>, val plans: List<PlanView>,
    val configs: List<AccountConfig>, val lastRuns: List<PlanRun>, val timeZone: String, val rules: List<DeviceRule> = emptyList())
/**
 * A rule the device judges itself, because only it can see the condition; it is on. [threshold] is in minutes for a
 * lasting condition. [local] says a local-notification channel for this device subscribes to it, so the device shows
 * what it notices at once, even offline.
 */
@Serializable data class DeviceRule(val ruleId: String, val threshold: Long? = null, val local: Boolean = false)
/**
 * Something the device noticed by a [DeviceRule]. [key] names the occurrence on this device, so a resend is recorded
 * once; [shown] says the device already showed it, so the server does not push it back.
 */
@Serializable data class DeviceEvent(val key: String, val ruleId: String, val at: Long, val title: String, val detail: String = "", val shown: Boolean = false)
@Serializable data class DeviceEvents(val events: List<DeviceEvent>)
/** A notice pushed to a device to show; [id] lets the device show a resend once. */
@Serializable data class DeviceNotice(val id: String, val title: String, val detail: String = "", val at: Long)
/** First message of the console's event stream (`/api/events`), and what it falls back to after a gap. */
@Serializable data class ConsoleSnapshot(val devices: List<DeviceView>, val jobs: List<JobView>, val accounts: List<GameAccountView> = emptyList(),
    val plans: List<PlanView> = emptyList(), val games: List<GameView> = emptyList(), val timeZone: String = "Asia/Shanghai")
@Serializable data class RemovedDevice(val id: String)
/** A job's run log has new lines; consoles that show it fetch them. */
@Serializable data class JobLogUpdated(val id: String)
/** [deviceId] and [appKey] are null while the job waits for an app. [planName] is set for a plan's run. */
@Serializable data class JobView(val id: String, val deviceId: String?, val deviceName: String?, val targetId: String, val taskId: String,
    val taskName: String, val state: JobState, val detail: String, val cancelRequested: Boolean, val createdAt: Long, val updatedAt: Long,
    val accountId: String? = null, val accountName: String? = null, val appKey: String? = null, val planId: String? = null, val planName: String? = null,
    val latestStartAt: Long? = null, val pauseRequested: Boolean = false)

/**
 * How an event rule decides: [INSTANT] on each occurrence; [COUNT] after a number of failures in a row; [DURATION] once a
 * condition has lasted a number of minutes; [RESULT] on a plan's end with one of the chosen results.
 */
@Serializable enum class RuleKind { INSTANT, COUNT, DURATION, RESULT }
/** How a plan's run ended, judged over all its jobs. */
@Serializable enum class PlanOutcome { COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED }
/**
 * One event rule of the user. All are off until the user turns them on; [threshold] (minutes or a count, by [kind]) is
 * set by the user, [suggested] only fills the editor.
 */
@Serializable data class EventRuleView(val ruleId: String, val name: String, val kind: RuleKind, val description: String, val enabled: Boolean,
    val threshold: Long? = null, val suggested: Long? = null, val results: List<PlanOutcome> = emptyList(), val byDevice: Boolean = false)
@Serializable data class EventRuleSave(val enabled: Boolean, val threshold: Long? = null, val results: List<PlanOutcome> = emptyList())
/** Something a rule noticed. Kept whether or not any channel is subscribed to it. */
@Serializable data class NoticeView(val id: String, val ruleId: String, val ruleName: String, val at: Long, val title: String, val detail: String = "",
    val jobId: String? = null, val deviceId: String? = null, val accountId: String? = null, val planId: String? = null,
    val deliveries: List<DeliveryView> = emptyList())

/** Where notices are sent. The server sends them all, so they arrive even when every device is offline. */
@Serializable enum class ChannelKind { DINGTALK, WECOM, BARK, EMAIL, WEBHOOK, LOCAL }
/**
 * A channel as the console sees it: [target] says where it sends without giving away a secret, and [filled] names
 * the secret fields that are set; their values never leave the server.
 */
@Serializable data class ChannelView(val id: String, val kind: ChannelKind, val name: String, val enabled: Boolean, val rules: List<String>,
    val target: String, val filled: List<String> = emptyList(), val fields: Map<String, String> = emptyMap(), val last: DeliveryView? = null)
/**
 * Creates or changes a channel. [fields] holds its settings by field id; a secret field left blank on a change keeps
 * the value already saved, unless it is named in [clear] or the channel's address changed.
 */
@Serializable data class ChannelSave(val kind: ChannelKind, val name: String, val enabled: Boolean = true, val rules: List<String> = emptyList(),
    val fields: Map<String, String> = emptyMap(), val clear: List<String> = emptyList())
/** Sends a test message with the draft [save], filling blank secrets from channel [id] when it is an edit. */
@Serializable data class ChannelTest(val id: String? = null, val save: ChannelSave)
@Serializable data class ChannelTestResult(val ok: Boolean, val message: String)
@Serializable enum class DeliveryState { PENDING, SENT, FAILED }
/** One notice's sending to one channel, or a test. [attempts] counts sends tried; [nextAt] is when a pending one is tried. */
@Serializable data class DeliveryView(val id: String, val channelId: String?, val channelName: String, val state: DeliveryState, val attempts: Int,
    val at: Long, val nextAt: Long? = null, val error: String = "", val test: Boolean = false)
