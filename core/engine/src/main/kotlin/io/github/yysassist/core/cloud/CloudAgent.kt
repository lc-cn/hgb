package io.github.yysassist.core.cloud

import io.github.yysassist.protocol.*

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
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.UUID

@Serializable data class ActiveCloudJob(val jobId: String, val leaseNo: Long, val targetId: String, val taskId: String, val runId: Long? = null,
    val digest: String = "", val configuration: JsonObject = JsonObject(emptyMap()),
    /** Whether the console last asked for this job to be paused; kept across restarts so only a change is acted on. */
    val paused: Boolean = false)
/** One action the device performed for a run, as kept in its local trace log. */
data class CloudTrace(val id: Long, val at: Long, val stepId: String, val actionId: String, val result: String, val reason: String?, val elapsedMillis: Long)
/** The active task bundle as text, with the digest the cloud uses to name it. */
data class CloudCatalog(val version: String, val digest: String, val text: String)
/** One game instance on this device that tasks can run in. [gameId] is the bundle's target id. */
data class CloudApp(val key: String, val gameId: String, val name: String)
@Serializable data class CloudCredentials(val serverUrl: String, val userId: String, val email: String?, val accessToken: String, val refreshToken: String,
    val accessExpiresAt: Long, val installId: String, val deviceId: String? = null, val activeJob: ActiveCloudJob? = null) {
    override fun toString() = "CloudCredentials(<redacted>)"
}
interface CloudCredentialStore {
    suspend fun read(): CloudCredentials?
    suspend fun write(value: CloudCredentials?)
}
/** Keeps the last data the server sent, so the app can show it and run with it offline. */
interface CloudDataStore {
    suspend fun read(): DeviceData?
    suspend fun write(value: DeviceData?)
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
    /** Game instances on this device that the active bundle supports and that are installed. */
    fun apps(): List<CloudApp> = emptyList()
    /** How many apps can run at the same time; a phone has one foreground, so one. */
    val concurrency: Int get() = 1
    /** The active task bundle, or null when none is usable. */
    suspend fun catalog(): CloudCatalog?
    /** Runs the task on bundle [digest] with exactly [configuration]; local settings are not read. */
    suspend fun enqueue(targetId: String, taskId: String, commandId: String, digest: String, configuration: JsonObject): CloudEnqueueResult
    fun remove(runId: Long)
    /** Pauses or resumes [runId] if it is the run in progress. */
    fun pause(runId: Long) {}
    fun resume(runId: Long) {}
    /** Actions recorded for [runId] after trace [after], oldest first. */
    suspend fun traces(runId: Long, after: Long): List<CloudTrace> = emptyList()
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

/**
 * Keeps this device signed in and connected, reports its state and runs the jobs leased to it, one at a time.
 * The state goes over one WebSocket, sent whole whenever it changes and at least every [heartbeatMillis].
 */
class CloudAgent(private val http: OkHttpClient, private val store: CloudCredentialStore, private val executor: CloudExecutorPort,
    private val deviceName: String, private val platform: String, private val appVersion: String, private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis, private val heartbeatMillis: Long = 15_000, private val retryMillis: Long = 5_000,
    private val progressMillis: Long = 1_500, private val statusMillis: Long = 1_000, private val dataStore: CloudDataStore? = null) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    /** A socket stays open for hours; pings keep it alive through NAT and let both ends notice a dead link. */
    private val sockets = http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CloudState>(CloudState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableData = MutableStateFlow<DeviceData?>(null)
    /** The user's accounts, settings and plans as the server last sent them; kept while offline, cleared on sign-out. */
    val data = mutableData.asStateFlow()
    private var credentials: CloudCredentials? = null
    private var worker: Job? = null
    /** The latest lease the server pushed, waiting for the job loop; a device has one valid lease at a time, so older ones are dropped. Replaced on each sign-in so nothing carries over. */
    @Volatile private var leases = Channel<JobLease>(Channel.CONFLATED)
    /** A bundle the server refused (too large, invalid); it is not uploaded again until the device runs another one. */
    @Volatile private var refusedCatalog: String? = null

    init {
        scope.launch {
            val saved = try { store.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            mutex.withLock { credentials = saved }
            if (saved != null) mutableData.value = runCatching { dataStore?.read() }.getOrNull()
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
        val access = result.accessToken; val refresh = result.refreshToken; val expires = result.accessExpiresAt
        if (access == null || refresh == null || expires == null) throw CloudRequestException(0, "invalid_reply", "服务器回复无效")
        val saved = CloudCredentials(server.trimEnd('/'), result.userId, result.email, access, refresh, expires, UUID.randomUUID().toString())
        clearData() // whoever was signed in before, their accounts and settings are not shown or run for this user
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
        clearData()
        mutableState.value = CloudState.SignedOut(message)
    }
    private suspend fun clearData() { mutableData.value = null; runCatching { dataStore?.write(null) } }

    /** Requests the app makes for the user. They fail with [IOException] when the server cannot be reached. */
    private suspend inline fun <reified R> call(method: String, path: String, body: String?): R? =
        authorized { send<R>(it.serverUrl, path, body, it.accessToken, method) }
    private suspend fun deviceId(): String = mutex.withLock { credentials?.deviceId } ?: throw CloudRequestException(0, "not_registered", "设备还没有连上服务器")
    suspend fun saveConfig(accountId: String, targetId: String, taskId: String, values: JsonObject, revision: Long): ConfigView =
        checkNotNull(call<ConfigView>("PUT", "/api/accounts/$accountId/configs/$targetId/$taskId", json.encodeToString(ConfigSave(values, revision))))
    suspend fun createPlan(accountId: String, save: PlanSave): PlanView = checkNotNull(call<PlanView>("POST", "/api/accounts/$accountId/plans", json.encodeToString(save)))
    suspend fun updatePlan(planId: String, save: PlanSave): PlanView = checkNotNull(call<PlanView>("PUT", "/api/plans/$planId", json.encodeToString(save)))
    suspend fun deletePlan(planId: String) { call<Unit>("DELETE", "/api/plans/$planId", null) }
    suspend fun runPlan(planId: String) { call<Unit>("POST", "/api/plans/$planId/run", "{}") }
    /** Says which account the app on this device is signed in to, until the app can tell by itself. */
    suspend fun setAppAccounts(appKey: String, accounts: AppAccounts) {
        call<Unit>("PUT", "/api/devices/${deviceId()}/apps/$appKey", json.encodeToString(accounts))
    }
    /** Queues the task here as a job of [accountId], so the account is not run on two devices at once. */
    suspend fun runHere(targetId: String, taskId: String, accountId: String, appKey: String): JobView =
        checkNotNull(call<JobView>("POST", "/api/jobs", json.encodeToString(CreateJobRequest(targetId, taskId, accountId, deviceId(), appKey))))

    private fun startWorking(saved: CloudCredentials) {
        worker?.cancel()
        leases = Channel(Channel.CONFLATED)
        mutableState.value = CloudState.SignedIn(saved.email, saved.serverUrl, false)
        worker = scope.launch { supervisorScope {
            launch { connections() }
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
            if (rejected.status == 401) { save(null); clearData(); mutableState.value = CloudState.SignedOut("登录已失效，请重新登录"); worker?.cancel() }
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
    /** Keeps one socket open, reconnecting after [retryMillis]; a socket refused for an expired token is retried once renewed. */
    private suspend fun connections() {
        while (currentCoroutineContext().isActive) {
            try {
                ensureDevice()
                executor.awaitRestored()
                val current = mutex.withLock { credentials } ?: throw CancellationException("已退出登录")
                val usable = if (current.accessExpiresAt - clock() < RENEW_MARGIN) renew(current) else current
                val code = connectOnce(usable)
                publish(false, "与服务器的连接已断开，正在重连")
                if (code == SocketClose.INVALID_SESSION.toInt()) { renew(usable); continue }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) { publish(false, error.message ?: "无法连接服务器") }
            delay(retryMillis)
        }
    }
    private sealed interface SocketEvent {
        data object Opened : SocketEvent
        data class Text(val text: String) : SocketEvent
        data class Closed(val code: Int) : SocketEvent
    }
    /** Runs one connection until it closes and returns its close code (-1 when it failed without one). */
    private suspend fun connectOnce(saved: CloudCredentials): Int = coroutineScope {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val request = Request.Builder().url(url(saved.serverUrl, "/api/device/socket")).header("X-YYS-Client", "device")
            .header("Authorization", "Bearer ${saved.accessToken}").build()
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(SocketEvent.Opened) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(SocketEvent.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); events.trySend(SocketEvent.Closed(code)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(SocketEvent.Closed(code)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(SocketEvent.Closed(if (response?.code == 401) SocketClose.INVALID_SESSION.toInt() else -1))
            }
        })
        var reporter: Job? = null
        try {
            for (event in events) when (event) {
                SocketEvent.Opened -> { publish(true); reporter = launch { reportStatus(socket) } }
                is SocketEvent.Text -> handle(event.text)
                is SocketEvent.Closed -> return@coroutineScope event.code
            }
            -1
        } finally { reporter?.cancel(); socket.cancel() }
    }
    /** Sends the device's state when it changes, and unchanged at least every [heartbeatMillis] so the server keeps its leases. */
    private suspend fun reportStatus(socket: WebSocket) {
        var catalog = executor.catalog(); var catalogAt = clock()
        var last: String? = null; var sentAt = 0L
        while (currentCoroutineContext().isActive) {
            if (clock() - catalogAt >= heartbeatMillis) { catalog = executor.catalog(); catalogAt = clock() }
            val text = json.encodeToString(DeviceMessage.serializer(), DeviceMessage.Status(status(catalog)))
            if (text != last || clock() - sentAt >= heartbeatMillis) {
                if (!socket.send(text)) return
                last = text; sentAt = clock()
            }
            delay(statusMillis)
        }
    }
    private fun status(catalog: CloudCatalog?): DeviceStatus {
        val snapshot = executor.state.value
        val apps = executor.apps().map { app ->
            val running = snapshot.current?.takeIf { it.request.context.targetId == app.gameId }
            AppStatus(app.key, app.gameId, app.name, if (running != null) snapshot.phase.name else "IDLE", running?.task?.name,
                snapshot.waiting.count { it.request.context.targetId == app.gameId })
        }
        return DeviceStatus(snapshot.phase.name, snapshot.current?.task?.name, snapshot.waiting.size, executor.tasks(), appVersion,
            catalog?.let { CatalogRef(it.version, it.digest) }, apps, executor.concurrency)
    }
    private suspend fun handle(text: String) {
        val message = runCatching { json.decodeFromString(ServerMessage.serializer(), text) }.getOrNull() ?: return
        when (message) {
            // A failed upload must not take the socket down: the server asks again, and a refusal is not retried for this bundle.
            ServerMessage.CatalogNeeded -> executor.catalog()?.takeIf { it.digest != refusedCatalog }?.let { catalog ->
                try {
                    authorized { send<Unit>(it.serverUrl, "/api/device/catalog", json.encodeToString(CatalogUpload(catalog.version, catalog.digest, catalog.text)), it.accessToken) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (rejected: CloudRequestException) { if (rejected.status in 400..499) refusedCatalog = catalog.digest }
                catch (_: IOException) { }
            }
            is ServerMessage.Cancel -> {
                val active = mutex.withLock { credentials?.activeJob }
                if (active?.runId != null && active.jobId in message.jobIds) executor.remove(active.runId)
            }
            is ServerMessage.Job -> leases.trySend(message.lease)
            is ServerMessage.Data -> { mutableData.value = message.data; runCatching { dataStore?.write(message.data) } }
            // The server repeats the wanted state with every status reply, so only a change is acted on, and a pause or
            // resume on the phone itself is not undone. A pause that comes before the run has started is kept with the
            // job and applied by the job loop once it has; the lock makes one of the two act, never both or neither.
            is ServerMessage.Pause, is ServerMessage.Resume -> {
                val pause = message is ServerMessage.Pause
                val jobId = if (message is ServerMessage.Pause) message.jobId else (message as ServerMessage.Resume).jobId
                val runId = mutex.withLock {
                    val active = credentials?.activeJob?.takeIf { it.jobId == jobId && it.paused != pause } ?: return
                    credentials?.let { save(it.copy(activeJob = active.copy(paused = pause))) }
                    active.runId
                } ?: return
                if (pause) executor.pause(runId) else executor.resume(runId)
            }
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
                // The server pushes leases over the socket, one at a time; a resend after a lost reply comes the same way.
                val lease = leases.receive()
                val mine = mutex.withLock { credentials }
                // A lease for anyone else is never acted on, whatever the server says.
                if (lease.userId != mine?.userId || lease.deviceId != deviceId) continue
                val job = ActiveCloudJob(lease.jobId, lease.leaseNo, lease.targetId, lease.taskId, digest = lease.digest, configuration = lease.configuration)
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
        if (job.runId == null && executor.catalog()?.digest != job.digest) {
            // The settings were checked against another version: running them here could mean something else.
            authorized { report(it, job, JobState.FAILED, "设备当前的任务版本与作业不一致，请等设备上报新版本后重新下发") }
            remember(null); return
        }
        // Nothing starts until the server accepts this lease; repeating the report after a restart is harmless.
        if (job.runId == null) authorized { report(it, job, JobState.RUNNING, "") }
        // The same command id returns the original run after a restart, so a job can never start twice.
        val runId = job.runId ?: when (val result = executor.enqueue(job.targetId, job.taskId, "cloud/${job.jobId}/${job.leaseNo}", job.digest, job.configuration)) {
            is CloudEnqueueResult.Accepted -> result.runId.also { runId ->
                val paused = mutex.withLock { credentials?.let { saved ->
                    val latest = saved.activeJob?.takeIf { it.jobId == job.jobId } ?: job
                    save(saved.copy(activeJob = latest.copy(runId = runId))); latest.paused } } == true
                if (paused) executor.pause(runId)
            }
            is CloudEnqueueResult.Rejected -> { authorized { report(it, job, JobState.FAILED, result.reason) }; remember(null); return }
            is CloudEnqueueResult.Unconfirmed -> { authorized { report(it, job, JobState.UNKNOWN, result.reason) }; remember(null); return }
        }
        val log = RunLog(job.copy(runId = runId), runId)
        val outcome = coroutineScope {
            // The log is sent while the run goes on, so the console shows where it is, and where it stopped.
            val sender = launch { while (isActive) { log.flush(); delay(progressMillis) } }
            executor.state.first { cloudOutcome(it, runId) != null }.let { checkNotNull(cloudOutcome(it, runId)) }.also { sender.cancel() }
        }
        // A job that ends with a step awaiting a check is over for the server; once the user settles the step on the
        // phone, the run ends there too instead of carrying on with nobody having asked for it.
        if (outcome.first == JobState.UNKNOWN && executor.state.value.unresolved.any { it.attempt.ticket.runId == runId }) executor.remove(runId)
        delay(TRACE_SETTLE_MILLIS) // the trace writer stores the last actions just after the run ends
        log.flush()
        authorized { report(it, job.copy(runId = runId), outcome.first, outcome.second) }
        remember(null)
    }

    /** Remembers what was already sent for one run; a failed send is retried with the same keys next time. */
    private inner class RunLog(private val job: ActiveCloudJob, private val runId: Long) {
        private var factAfter = 0L; private var traceAfter = 0L
        suspend fun flush() {
            val facts = executor.state.value.facts.filter { it.runId == runId && it.id > factAfter }
            val traces = try { executor.traces(runId, traceAfter) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            if (facts.isEmpty() && traces.isEmpty()) return
            val events = facts.map { JobEvent("fact:${it.id}", it.occurredAtEpochMillis, it.type, it.stepId.orEmpty(), detail = it.detail.take(DETAIL_CHARS)) } +
                traces.map { JobEvent("action:${it.id}", it.at, "action", it.stepId, it.actionId,
                    (it.result + (it.reason?.let { reason -> "：$reason" } ?: "")).take(DETAIL_CHARS), it.elapsedMillis) }
            try {
                authorized { send<Unit>(it.serverUrl, "/api/device/jobs/${job.jobId}/events", json.encodeToString(JobEvents(job.leaseNo, events.sortedBy(JobEvent::at))), it.accessToken) }
                factAfter = facts.maxOfOrNull { it.id } ?: factAfter; traceAfter = traces.maxOfOrNull { it.id } ?: traceAfter
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { } // the log is best effort; the final report still decides the job
        }
    }

    private companion object {
        const val DETAIL_CHARS = 300
        const val TRACE_SETTLE_MILLIS = 600L
        val JSON_TYPE = "application/json".toMediaType()
        const val RENEW_MARGIN = 60_000L
    }
}
