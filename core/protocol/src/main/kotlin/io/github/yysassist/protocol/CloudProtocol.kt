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

/** Tokens are only returned to device clients; browsers receive an HttpOnly cookie instead. */
@Serializable data class AuthResult(val userId: String, val email: String? = null, val accessToken: String? = null,
    val refreshToken: String? = null, val accessExpiresAt: Long? = null)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class AccountView(val userId: String, val email: String?, val hasPassword: Boolean, val githubLogin: String?)

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
@Serializable data class AppStatus(val key: String, val gameId: String, val name: String, val phase: String = "IDLE",
    val currentTask: String? = null, val queued: Int = 0)
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
}
/** Close codes the server uses when it refuses a device socket. */
object SocketClose { const val INVALID_SESSION: Short = 4401; const val NOT_A_DEVICE: Short = 4403; const val REPLACED: Short = 4409 }

@Serializable enum class JobState { QUEUED, LEASED, RUNNING, COMPLETED, FAILED, UNKNOWN, CANCELLED }

/**
 * Permission to run one job. [leaseNo] rises on every grant, so a stale holder can never report. The job runs on
 * task bundle [digest] with exactly [configuration], the settings saved in the cloud when the job was created.
 */
@Serializable data class JobLease(val jobId: String, val leaseNo: Long, val userId: String, val deviceId: String,
    val targetId: String, val taskId: String, val expiresAt: Long, val version: String, val digest: String, val configuration: JsonObject)
@Serializable data class JobReport(val leaseNo: Long, val state: JobState, val detail: String = "")

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
@Serializable data class CreateJobRequest(val deviceId: String, val targetId: String, val taskId: String)
/** A task's settings as the cloud stores them, with the field definitions of the version a device runs. */
@Serializable data class ConfigView(val targetId: String, val taskId: String, val taskName: String, val version: String,
    val fields: JsonArray, val values: JsonObject, val revision: Long)
/** [revision] is the one the editor started from; a newer save in between is refused rather than overwritten. */
@Serializable data class ConfigSave(val deviceId: String, val values: JsonObject, val revision: Long)
@Serializable data class DeviceView(val id: String, val name: String, val platform: String, val online: Boolean, val lastSeenAt: Long?,
    val phase: String?, val currentTask: String?, val queued: Int, val tasks: List<CloudTask>, val apps: List<AppStatus> = emptyList(), val concurrency: Int = 1)
/** First message of the console's event stream (`/api/events`), and what it falls back to after a gap. */
@Serializable data class ConsoleSnapshot(val devices: List<DeviceView>, val jobs: List<JobView>)
@Serializable data class RemovedDevice(val id: String)
/** A job's run log has new lines; consoles that show it fetch them. */
@Serializable data class JobLogUpdated(val id: String)
@Serializable data class JobView(val id: String, val deviceId: String, val deviceName: String, val targetId: String, val taskId: String,
    val taskName: String, val state: JobState, val detail: String, val cancelRequested: Boolean, val createdAt: Long, val updatedAt: Long)
