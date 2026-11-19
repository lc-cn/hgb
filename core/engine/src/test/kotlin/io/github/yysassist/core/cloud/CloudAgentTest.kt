package io.github.yysassist.core.cloud

import io.github.yysassist.protocol.*

import io.github.yysassist.core.execution.ExecutionFact
import io.github.yysassist.core.execution.TaskExecutor
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CloudAgentTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reports = CopyOnWriteArrayList<JobReport>()
    private val enqueued = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile private var lease: String? = null
    @Volatile private var accessRejected = false
    @Volatile private var refreshRejected = false
    private fun auth(token: String) = """{"userId":"me","email":"a@example.test","accessToken":"$token","refreshToken":"r-$token","accessExpiresAt":${System.currentTimeMillis() + 3_600_000}}"""
    private fun leaseJson(user: String = "me", device: String = "d1", digest: String = "abc") =
        """{"jobId":"j1","leaseNo":3,"userId":"$user","deviceId":"$device","targetId":"game","taskId":"gift","expiresAt":1,"version":"v1","digest":"$digest","configuration":{"box":"currency"}}"""
    private val configurations = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
    private val uploads = CopyOnWriteArrayList<CatalogUpload>()
    private val heartbeats = CopyOnWriteArrayList<DeviceHeartbeat>()
    @Volatile private var needCatalog = false
    private val events = CopyOnWriteArrayList<JobEvent>()
    /** When set, an accepted submission immediately shows this run as finished with a few facts. */
    @Volatile private var finishedRun: Long? = null

    private val store = object : CloudCredentialStore {
        @Volatile var value: CloudCredentials? = null
        override suspend fun read() = value
        override suspend fun write(value: CloudCredentials?) { this.value = value }
    }
    private val executorState = MutableStateFlow(TaskExecutor().state.value)
    private var restored = CompletableDeferred(Unit)
    private var enqueueResult: CloudEnqueueResult = CloudEnqueueResult.Rejected("当前不能运行")
    private val port = object : CloudExecutorPort {
        override val state = executorState
        override suspend fun awaitRestored() { restored.await() }
        override fun tasks() = listOf(CloudTask("game", "gift", "每日礼包"))
        override suspend fun catalog() = CloudCatalog("v1", "abc", """{"version":"v1"}""")
        override suspend fun enqueue(targetId: String, taskId: String, commandId: String, digest: String, configuration: kotlinx.serialization.json.JsonObject): CloudEnqueueResult {
            enqueued += commandId; configurations += configuration
            finishedRun?.let { run ->
                fun fact(id: Long, type: String, step: String?) = ExecutionFact(type, run, "gift", "", "每日礼包", ExecutionContext("game", Principal.Anonymous), "v1", 1_000 + id, id, step)
                executorState.value = executorState.value.copy(facts = listOf(fact(1, "step-started", "claim"), fact(2, "task-completed", null)))
            }
            return enqueueResult }
        override suspend fun traces(runId: Long, after: Long) =
            listOf(CloudTrace(9, 1_001, "claim", "tap", "PRODUCED", null, 80)).filter { it.id > after && runId == finishedRun }
        override fun remove(runId: Long) = Unit
    }
    private fun agent() = CloudAgent(OkHttpClient(), store, port, "手机", "android", "1", scope, heartbeatMillis = 50, retryMillis = 50, pollMillis = 0)
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath; paths += path
                fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
                val denied = MockResponse.Builder().code(401).body("""{"code":"invalid_session","message":"请先登录"}""").build()
                return when {
                    path == "/api/auth/login" -> ok(auth("a1"))
                    path == "/api/auth/refresh" -> if (refreshRejected) denied else { accessRejected = false; ok(auth("a2")) }
                    accessRejected -> denied
                    path == "/api/device/register" -> ok("""{"deviceId":"d1","userId":"me"}""")
                    path == "/api/device/heartbeat" -> { heartbeats += json.decodeFromString(DeviceHeartbeat.serializer(), request.body!!.utf8())
                        ok(if (needCatalog) """{"needCatalog":true}""" else "{}") }
                    path == "/api/device/catalog" -> { uploads += json.decodeFromString(CatalogUpload.serializer(), request.body!!.utf8()); needCatalog = false; ok("{}") }
                    path == "/api/device/jobs/next" -> lease?.also { lease = null }?.let(::ok) ?: MockResponse.Builder().code(204).build()
                    path.endsWith("/events") -> { events += json.decodeFromString(JobEvents.serializer(), request.body!!.utf8()).events; ok("{}") }
                    path.endsWith("/report") -> { reports += json.decodeFromString(JobReport.serializer(), request.body!!.utf8()); ok("{}") }
                    else -> ok("{}")
                }
            }
        }
        server.start()
    }
    @After fun stop() { scope.cancel(); server.close() }
    private fun eventually(check: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!check()) delay(20) } }
    private suspend fun signedIn() = agent().also { it.state.first { state -> state !is CloudState.Loading }; it.signIn(base, "a@example.test", "password") }

    @Test fun signingInRegistersTheDeviceAndGoesOnline() = runBlocking<Unit> {
        val agent = signedIn()
        withTimeout(5_000) { agent.state.first { it is CloudState.SignedIn && it.online } }
        assertEquals("d1", store.value?.deviceId)
        assertFalse(store.value.toString().contains("a1"))
    }
    @Test fun aRejectedTaskIsReportedAsFailedAfterStarting() = runBlocking<Unit> {
        lease = leaseJson(); signedIn()
        eventually { reports.size == 2 }
        assertEquals(listOf(JobState.RUNNING, JobState.FAILED), reports.map { it.state })
        assertEquals("当前不能运行", reports.last().detail)
        assertEquals(listOf("cloud/j1/3"), enqueued)
    }
    @Test fun aJobRunsWithTheSettingsItCarries() = runBlocking<Unit> {
        lease = leaseJson(); signedIn()
        eventually { reports.size == 2 }
        assertEquals("currency", configurations.single()["box"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }
    @Test fun aRunsLogIsSentOnceBeforeItsResult() = runBlocking<Unit> {
        finishedRun = 7; enqueueResult = CloudEnqueueResult.Accepted(7); lease = leaseJson(); signedIn()
        eventually { reports.size == 2 }
        assertEquals(JobState.COMPLETED, reports.last().state)
        assertEquals(listOf("fact:1", "action:9", "fact:2"), events.map { it.key })
        assertEquals("claim", events.first().step)
    }
    @Test fun aJobForAnotherTaskVersionIsRefusedBeforeItStarts() = runBlocking<Unit> {
        lease = leaseJson(digest = "other"); signedIn()
        eventually { reports.isNotEmpty() }
        delay(200)
        assertEquals(listOf(JobState.FAILED), reports.map { it.state })
        assertTrue(enqueued.isEmpty())
    }
    @Test fun heartbeatsNameTheTaskVersionAndUploadItWhenAsked() = runBlocking<Unit> {
        needCatalog = true; signedIn()
        eventually { uploads.isNotEmpty() }
        assertEquals(CatalogRef("v1", "abc"), heartbeats.first().catalog)
        assertEquals("abc", uploads.single().digest)
        delay(200)
        assertEquals(1, uploads.size)
    }
    @Test fun anUnconfirmedSubmissionIsNeverReportedAsAFailure() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Unconfirmed("提交结果未确认"); lease = leaseJson(); signedIn()
        eventually { reports.size == 2 }
        assertEquals(JobState.UNKNOWN, reports.last().state)
    }
    @Test fun aLeaseForAnotherUserOrDeviceIsIgnored() = runBlocking<Unit> {
        lease = leaseJson(user = "someone-else"); signedIn()
        eventually { lease == null }
        lease = leaseJson(device = "other-device")
        eventually { lease == null }
        delay(300)
        assertTrue(enqueued.isEmpty()); assertTrue(reports.isEmpty())
    }
    @Test fun aJobInterruptedByARestartWaitsForSavedStateBeforeJudgingTheResult() = runBlocking<Unit> {
        restored = CompletableDeferred()
        store.value = CloudCredentials(base, "me", "a@example.test", "a1", "r-a1", System.currentTimeMillis() + 3_600_000, "install", "d1", ActiveCloudJob("j1", 3, "game", "gift", runId = 9, digest = "abc"))
        agent(); delay(400)
        assertTrue("nothing may be claimed before state is restored", reports.isEmpty())
        restored.complete(Unit)
        eventually { reports.isNotEmpty() }
    }
    @Test fun anExpiredAccessTokenIsRenewedOnce() = runBlocking<Unit> {
        signedIn(); eventually { store.value?.deviceId != null }
        accessRejected = true
        eventually { store.value?.accessToken == "a2" }
        assertEquals("r-a2", store.value?.refreshToken)
    }
    @Test fun aRejectedRefreshSignsOutAndErasesCredentials() = runBlocking<Unit> {
        val agent = signedIn(); eventually { store.value?.deviceId != null }
        refreshRejected = true; accessRejected = true
        withTimeout(5_000) { agent.state.first { it is CloudState.SignedOut } }
        assertNull(store.value)
    }
    @Test fun signingOutErasesCredentialsEvenWhenTheServerIsGone() = runBlocking<Unit> {
        val agent = signedIn(); eventually { store.value?.deviceId != null }
        server.close()
        agent.signOut()
        assertNull(store.value); assertTrue(agent.state.value is CloudState.SignedOut)
    }
}
