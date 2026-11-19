package io.github.yysassist.protocol

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
@Serializable data class DeviceHeartbeat(val phase: String, val currentTask: String? = null, val queued: Int = 0,
    val tasks: List<CloudTask> = emptyList(), val appVersion: String = "", val catalog: CatalogRef? = null)
@Serializable data class HeartbeatReply(val cancelJobs: List<String> = emptyList(), val needCatalog: Boolean = false)

@Serializable enum class JobState { QUEUED, LEASED, RUNNING, COMPLETED, FAILED, UNKNOWN, CANCELLED }

/**
 * Permission to run one job. [leaseNo] rises on every grant, so a stale holder can never report. The job runs on
 * task bundle [digest] with exactly [configuration], the settings saved in the cloud when the job was created.
 */
@Serializable data class JobLease(val jobId: String, val leaseNo: Long, val userId: String, val deviceId: String,
    val targetId: String, val taskId: String, val expiresAt: Long, val version: String, val digest: String, val configuration: JsonObject)
@Serializable data class JobReport(val leaseNo: Long, val state: JobState, val detail: String = "")

@Serializable data class CreateJobRequest(val deviceId: String, val targetId: String, val taskId: String)
/** A task's settings as the cloud stores them, with the field definitions of the version a device runs. */
@Serializable data class ConfigView(val targetId: String, val taskId: String, val taskName: String, val version: String,
    val fields: JsonArray, val values: JsonObject, val revision: Long)
/** [revision] is the one the editor started from; a newer save in between is refused rather than overwritten. */
@Serializable data class ConfigSave(val deviceId: String, val values: JsonObject, val revision: Long)
@Serializable data class DeviceView(val id: String, val name: String, val platform: String, val online: Boolean, val lastSeenAt: Long?,
    val phase: String?, val currentTask: String?, val queued: Int, val tasks: List<CloudTask>)
@Serializable data class JobView(val id: String, val deviceId: String, val deviceName: String, val targetId: String, val taskId: String,
    val taskName: String, val state: JobState, val detail: String, val cancelRequested: Boolean, val createdAt: Long, val updatedAt: Long)
