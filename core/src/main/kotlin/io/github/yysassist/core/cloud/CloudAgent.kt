package io.github.yysassist.core.cloud

import io.github.yysassist.core.execution.ExecutorSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

@Serializable data class ActiveCloudJob(val jobId: String, val leaseNo: Long, val targetId: String, val taskId: String, val runId: Long? = null)
@Serializable data class CloudCredentials(val serverUrl: String, val userId: String, val email: String?, val accessToken: String, val refreshToken: String,
    val accessExpiresAt: Long, val installId: String, val deviceId: String? = null, val activeJob: ActiveCloudJob? = null) {
    override fun toString() = "CloudCredentials(<redacted>)"
}
interface CloudCredentialStore {
    suspend fun read(): CloudCredentials?
    suspend fun write(value: CloudCredentials?)
}
sealed interface CloudEnqueueResult {
    data class Accepted(val runId: Long) : CloudEnqueueResult
    data class Rejected(val reason: String) : CloudEnqueueResult
    /** The executor may or may not have taken the run, so the job's outcome cannot be claimed. */
    data class Unconfirmed(val reason: String) : CloudEnqueueResult
}
/** The agent's only way into execution: it submits work and reads state like any other caller. */
interface CloudExecutorPort {
    val state: StateFlow<ExecutorSnapshot>
    /** Suspends until saved execution state has been restored; before that, [state] says nothing about past runs. */
    suspend fun awaitRestored()
    fun tasks(): List<CloudTask>
    suspend fun enqueue(targetId: String, taskId: String, commandId: String): CloudEnqueueResult
    fun remove(runId: Long)
}
sealed interface CloudState {
    data object Loading : CloudState
    data class SignedOut(val message: String? = null) : CloudState
    data class SignedIn(val email: String?, val serverUrl: String, val online: Boolean, val message: String? = null) : CloudState
}
class CloudRequestException(val status: Int, val code: String, override val message: String) : IOException(message)

/** What a finished run means for its job; null while the executor is still working on it. */
fun cloudOutcome(state: ExecutorSnapshot, runId: Long): Pair<JobState, String>? {
    state.unresolved.firstOrNull { it.attempt.ticket.runId == runId }?.let { return JobState.UNKNOWN to it.reason }
    if (state.current?.runId == runId || state.waiting.any { it.runId == runId }) return null
    val facts = state.facts.filter { it.runId == runId }
    facts.lastOrNull { it.type == "cancelled" }?.let { return JobState.CANCELLED to "已停止" }
    facts.lastOrNull { it.type == "task-failed" }?.let { return JobState.FAILED to it.detail }
    if (facts.any { it.type == "task-completed" }) return JobState.COMPLETED to ""
    return JobState.UNKNOWN to "运行记录不可用，结果未确认"
}

/** Keeps this device signed in, reports its state and runs the jobs leased to it, one at a time. */
class CloudAgent(private val http: OkHttpClient, private val store: CloudCredentialStore, private val executor: CloudExecutorPort,
    private val deviceName: String, private val platform: String, private val appVersion: String, private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis, private val heartbeatMillis: Long = 15_000, private val retryMillis: Long = 5_000, private val pollMillis: Long = 20_000) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CloudState>(CloudState.Loading)
    val state = mutableState.asStateFlow()
    private var credentials: CloudCredentials? = null
    private var worker: Job? = null

    init {
        scope.launch {
            val saved = try { store.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            mutex.withLock { credentials = saved }
            if (saved == null) mutableState.value = CloudState.SignedOut() else startWorking(saved)
        }
    }

    private fun url(server: String, path: String): HttpUrl = (server.trimEnd('/') + path).toHttpUrlOrNull() ?: throw CloudRequestException(0, "invalid_server", "服务器地址无效")
    private suspend inline fun <reified R> send(server: String, path: String, body: String?, token: String? = null, method: String = "POST"): R? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(server, path)).header("X-YYS-Client", "device").apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, body?.toRequestBody(JSON_TYPE) ?: if (method == "POST") ByteArray(0).toRequestBody(JSON_TYPE) else null).build()
        http.newCall(request).execute().use { reply ->
            val text = reply.body.string()
            if (!reply.isSuccessful) throw runCatching { json.decodeFromString<ApiError>(text) }.getOrNull()?.let { CloudRequestException(reply.code, it.code, it.message) }
                ?: CloudRequestException(reply.code, "http", "服务器暂时不可用")
            if (reply.code == 204 || text.isBlank()) null else json.decodeFromString<R>(text)
        }
    }
    private suspend fun save(next: CloudCredentials?) { store.write(next); credentials = next }
    private suspend fun adopt(server: String, result: AuthResult) {
        val access = result.accessToken; val refresh = result.refreshToken
        if (access == null || refresh == null || result.accessExpiresAt == null) throw CloudRequestException(0, "invalid_reply", "服务器回复无效")
        val saved = CloudCredentials(server.trimEnd('/'), result.userId, result.email, access, refresh, result.accessExpiresAt, UUID.randomUUID().toString())
        mutex.withLock { save(saved) }
        startWorking(saved)
    }
    suspend fun signIn(server: String, email: String, password: String) =
        adopt(server, checkNotNull(send<AuthResult>(server, "/api/auth/login", json.encodeToString(PasswordLoginRequest(email, password)))))
    suspend fun requestCode(server: String, email: String) { send<Unit>(server, "/api/auth/code/request", json.encodeToString(CodeRequest(email))) }
    suspend fun signInWithCode(server: String, email: String, code: String) =
        adopt(server, checkNotNull(send<AuthResult>(server, "/api/auth/code/login", json.encodeToString(CodeLoginRequest(email, code)))))

    /** Local work is stopped and credentials are erased even when the server cannot be reached. */
    suspend fun signOut(message: String? = null) {
        worker?.cancelAndJoin(); worker = null
        val saved = mutex.withLock { credentials }
        saved?.activeJob?.runId?.let(executor::remove)
        if (saved != null) runCatching {
            saved.activeJob?.let { report(saved, it, JobState.CANCELLED, "设备已退出登录") }
            send<Unit>(saved.serverUrl, "/api/auth/logout", "{}", saved.accessToken)
        }
        mutex.withLock { save(null) }
        mutableState.value = CloudState.SignedOut(message)
    }

    private fun startWorking(saved: CloudCredentials) {
        worker?.cancel()
        mutableState.value = CloudState.SignedIn(saved.email, saved.serverUrl, false)
        worker = scope.launch { supervisorScope {
            launch { heartbeats() }
            launch { jobs() }
        } }
    }
    private fun publish(online: Boolean, message: String? = null) {
        val saved = credentials ?: return
        mutableState.value = CloudState.SignedIn(saved.email, saved.serverUrl, online, message)
    }
    /** Runs a request with a valid token, renewing it once when the server says it has expired. */
    private suspend fun <T> authorized(block: suspend (CloudCredentials) -> T): T {
        val current = mutex.withLock { credentials } ?: throw CancellationException("已退出登录")
        val usable = if (current.accessExpiresAt - clock() < RENEW_MARGIN) renew(current) else current
        return try { block(usable) } catch (rejected: CloudRequestException) {
            if (rejected.status != 401) throw rejected
            block(renew(usable))
        }
    }
    private suspend fun renew(used: CloudCredentials): CloudCredentials = mutex.withLock {
        val latest = credentials ?: throw CancellationException("已退出登录")
        if (latest.accessToken != used.accessToken) return@withLock latest
        val result = try { checkNotNull(send<AuthResult>(latest.serverUrl, "/api/auth/refresh", json.encodeToString(RefreshRequest(latest.refreshToken)))) }
        catch (rejected: CloudRequestException) {
            if (rejected.status == 401) { save(null); mutableState.value = CloudState.SignedOut("登录已失效，请重新登录"); worker?.cancel() }
            throw rejected
        }
        latest.copy(accessToken = checkNotNull(result.accessToken), refreshToken = checkNotNull(result.refreshToken), accessExpiresAt = checkNotNull(result.accessExpiresAt)).also { save(it) }
    }
    private suspend fun ensureDevice(): String {
        mutex.withLock { credentials?.deviceId }?.let { return it }
        val registered = checkNotNull(authorized { send<DeviceRegistered>(it.serverUrl, "/api/device/register", json.encodeToString(DeviceRegistration(it.installId, deviceName, platform)), it.accessToken) })
        mutex.withLock { credentials?.let { save(it.copy(deviceId = registered.deviceId)) } }
        return registered.deviceId
    }
    private suspend fun heartbeats() {
        while (currentCoroutineContext().isActive) {
            try {
                ensureDevice()
                executor.awaitRestored()
                val snapshot = executor.state.value
                val beat = DeviceHeartbeat(snapshot.phase.name, snapshot.current?.task?.name, snapshot.waiting.size, executor.tasks(), appVersion)
                val reply = authorized { send<HeartbeatReply>(it.serverUrl, "/api/device/heartbeat", json.encodeToString(beat), it.accessToken) }
                val active = mutex.withLock { credentials?.activeJob }
                if (active?.runId != null && reply?.cancelJobs?.contains(active.jobId) == true) executor.remove(active.runId)
                publish(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) { publish(false, error.message ?: "无法连接服务器") }
            delay(heartbeatMillis)
        }
    }
    private suspend fun report(saved: CloudCredentials, job: ActiveCloudJob, state: JobState, detail: String) {
        send<Unit>(saved.serverUrl, "/api/device/jobs/${job.jobId}/report", json.encodeToString(JobReport(job.leaseNo, state, detail.take(400))), saved.accessToken)
    }
    private suspend fun remember(job: ActiveCloudJob?) = mutex.withLock { credentials?.let { save(it.copy(activeJob = job)) } }
    private suspend fun jobs() {
        while (currentCoroutineContext().isActive) {
            try {
                val deviceId = ensureDevice()
                val resumed = mutex.withLock { credentials?.activeJob }
                if (resumed != null) { finish(resumed); continue }
                val lease = authorized { send<JobLease>(it.serverUrl, "/api/device/jobs/next?waitMs=$pollMillis", "{}", it.accessToken) } ?: continue
                val mine = mutex.withLock { credentials }
                // A lease for anyone else is never acted on, whatever the server says.
                if (lease.userId != mine?.userId || lease.deviceId != deviceId) continue
                val job = ActiveCloudJob(lease.jobId, lease.leaseNo, lease.targetId, lease.taskId)
                remember(job)
                finish(job)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (rejected: CloudRequestException) {
                // The server no longer recognizes this lease: nothing further can be reported for it.
                if (rejected.code == "stale_lease" || rejected.code == "not_found" || rejected.code == "invalid_transition") remember(null)
                publish(false, rejected.message); delay(retryMillis)
            }
            catch (error: IOException) { publish(false, error.message ?: "无法连接服务器"); delay(retryMillis) }
        }
    }
    private suspend fun finish(job: ActiveCloudJob) {
        executor.awaitRestored()
        // Nothing starts until the server accepts this lease; repeating the report after a restart is harmless.
        if (job.runId == null) authorized { report(it, job, JobState.RUNNING, "") }
        // The same command id returns the original run after a restart, so a job can never start twice.
        val runId = job.runId ?: when (val result = executor.enqueue(job.targetId, job.taskId, "cloud/${job.jobId}/${job.leaseNo}")) {
            is CloudEnqueueResult.Accepted -> result.runId.also { remember(job.copy(runId = it)) }
            is CloudEnqueueResult.Rejected -> { authorized { report(it, job, JobState.FAILED, result.reason) }; remember(null); return }
            is CloudEnqueueResult.Unconfirmed -> { authorized { report(it, job, JobState.UNKNOWN, result.reason) }; remember(null); return }
        }
        val outcome = executor.state.first { cloudOutcome(it, runId) != null }.let { checkNotNull(cloudOutcome(it, runId)) }
        authorized { report(it, job.copy(runId = runId), outcome.first, outcome.second) }
        remember(null)
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        const val RENEW_MARGIN = 60_000L
    }
}
