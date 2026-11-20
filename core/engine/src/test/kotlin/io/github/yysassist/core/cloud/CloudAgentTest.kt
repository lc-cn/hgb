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
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val statuses = CopyOnWriteArrayList<DeviceStatus>()
    @Volatile private var needCatalog = false
    @Volatile private var socket: WebSocket? = null
    private val paused = CopyOnWriteArrayList<Long>()
    private val accountRuns = CopyOnWriteArrayList<String>()
    private val accountCommands = CopyOnWriteArrayList<String>()
    @Volatile private var signedInUid = "111"
    @Volatile private var signedIn: ObservedAccount? = null
    @Volatile private var switchesTo: String? = null
    private val resumed = CopyOnWriteArrayList<Long>()
    private val events = CopyOnWriteArrayList<JobEvent>()
    @Volatile private var blockedReason: String? = null
    private val notified = CopyOnWriteArrayList<String>()
    private val noticeBatches = CopyOnWriteArrayList<DeviceEvents>()
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
    /** Holds a submission until completed, to deliver messages while the job has no run yet. */
    @Volatile private var enqueueGate = CompletableDeferred(Unit)
    private val port = object : CloudExecutorPort {
        override val state = executorState
        override suspend fun awaitRestored() { restored.await() }
        override fun tasks() = listOf(CloudTask("game", "gift", "每日礼包"))
        override fun apps() = listOf(CloudApp("test.game", "game", "游戏"))
        override suspend fun catalog() = CloudCatalog("v1", "abc", """{"version":"v1"}""")
        override suspend fun enqueue(targetId: String, taskId: String, commandId: String, digest: String, configuration: kotlinx.serialization.json.JsonObject): CloudEnqueueResult {
            enqueued += commandId; configurations += configuration
            enqueueGate.await()
            finishedRun?.let { run ->
                fun fact(id: Long, type: String, step: String?) = ExecutionFact(type, run, "gift", "", "每日礼包", ExecutionContext("game", Principal.Anonymous), "v1", 1_000 + id, id, step)
                executorState.value = executorState.value.copy(facts = listOf(fact(1, "step-started", "claim"), fact(2, "task-completed", null)))
            }
            return enqueueResult }
        override suspend fun traces(runId: Long, after: Long) =
            listOf(CloudTrace(9, 1_001, "claim", "tap", "PRODUCED", null, 80)).filter { it.id > after && runId == finishedRun }
        override fun remove(runId: Long) = Unit
        override fun pause(runId: Long) { paused += runId }
        override fun observedAccount(targetId: String) = signedIn
        /** Identify reports [signedIn]; switching signs in to [switchesTo] (a wrong one when set so). */
        override suspend fun enqueueAccountTask(targetId: String, task: AccountTask, commandId: String, digest: String, nickname: String?): CloudEnqueueResult {
            accountRuns += "$task:${nickname.orEmpty()}"; accountCommands += commandId
            val run = 100L + accountRuns.size
            if (task == AccountTask.SWITCH) signedInUid = switchesTo ?: nickname!!.removePrefix("昵称")
            signedIn = ObservedAccount(signedInUid, "昵称$signedInUid", System.currentTimeMillis() + 1)
            fun fact(id: Long, type: String) = ExecutionFact(type, run, "a", "", "账号", ExecutionContext("game", Principal.Anonymous), "v1", 1_000 + id, run * 10 + id, null)
            executorState.value = executorState.value.copy(facts = executorState.value.facts + fact(1, "task-completed"))
            return CloudEnqueueResult.Accepted(run)
        }
        override fun resume(runId: Long) { resumed += runId }
        override fun blocked() = blockedReason
        override fun notify(key: String, title: String, text: String): Boolean { notified += title; return true }
    }
    private val dataStore = object : CloudDataStore {
        @Volatile var value: DeviceData? = null
        override suspend fun read() = value
        override suspend fun write(value: DeviceData?) { this.value = value }
    }
    private fun agent() = CloudAgent(OkHttpClient(), store, port, "手机", "android", "1", scope, heartbeatMillis = 50, retryMillis = 50, dataStore = dataStore,
        watchMillis = 30, conditionGraceMillis = 0)
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
                    path == "/api/device/socket" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { socket = webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            statuses += (json.decodeFromString(DeviceMessage.serializer(), text) as DeviceMessage.Status).status
                            if (needCatalog) webSocket.send(json.encodeToString(ServerMessage.serializer(), ServerMessage.CatalogNeeded))
                            // Leases are pushed over the socket, as the server does.
                            lease?.also { lease = null }?.let { webSocket.send("""{"type":"job","lease":$it}""") }
                        }
                    }).build()
                    path == "/api/device/catalog" -> { uploads += json.decodeFromString(CatalogUpload.serializer(), request.body!!.utf8()); needCatalog = false; ok("{}") }
                    path.endsWith("/events") -> { events += json.decodeFromString(JobEvents.serializer(), request.body!!.utf8()).events; ok("{}") }
                    path == "/api/device/notices" -> { noticeBatches += json.decodeFromString(DeviceEvents.serializer(), request.body!!.utf8()); ok("{}") }
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
    @Test fun statusReportsNameTheTaskVersionAndAppsAndUploadTheBundleWhenAsked() = runBlocking<Unit> {
        needCatalog = true; signedIn()
        eventually { uploads.isNotEmpty() }
        assertEquals(CatalogRef("v1", "abc"), statuses.first().catalog)
        assertEquals(listOf(AppStatus("test.game", "game", "游戏")), statuses.first().apps)
        assertEquals("abc", uploads.single().digest)
        delay(200)
        assertEquals(1, uploads.size)
    }
    @Test fun aPauseForTheRunningJobReachesTheExecutorAndOneForAnotherJobDoesNot() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Accepted(5); lease = leaseJson(); signedIn()
        eventually { store.value?.activeJob?.runId == 5L }
        socket!!.send("""{"type":"pause","jobId":"other"}""")
        socket!!.send("""{"type":"pause","jobId":"j1"}""")
        eventually { paused.isNotEmpty() }
        assertEquals(listOf(5L), paused)
    }
    @Test fun aPauseThatArrivesBeforeTheRunStartsIsAppliedOnceItHasAndRepeatsAreIgnored() = runBlocking<Unit> {
        enqueueGate = CompletableDeferred(); enqueueResult = CloudEnqueueResult.Accepted(5); lease = leaseJson(); signedIn()
        eventually { enqueued.isNotEmpty() }
        socket!!.send("""{"type":"pause","jobId":"j1"}""")
        delay(200)
        assertTrue(paused.isEmpty())
        enqueueGate.complete(Unit)
        eventually { paused.isNotEmpty() }
        socket!!.send("""{"type":"pause","jobId":"j1"}""")
        delay(200)
        assertEquals(listOf(5L), paused)
    }
    @Test fun theConsolesPauseIsKeptWithTheJobSoAResumeIsActedOnAfterARestart() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Accepted(5); lease = leaseJson(); signedIn()
        eventually { store.value?.activeJob?.runId == 5L }
        socket!!.send("""{"type":"resume","jobId":"j1"}""")
        socket!!.send("""{"type":"pause","jobId":"j1"}""")
        eventually { store.value?.activeJob?.paused == true }
        socket!!.send("""{"type":"resume","jobId":"j1"}""")
        eventually { resumed.isNotEmpty() }
        assertEquals(listOf(5L), paused); assertEquals(listOf(5L), resumed)
        assertEquals(false, store.value?.activeJob?.paused)
    }
    @Test fun theServersDataIsKeptForOfflineUseAndErasedOnSignOut() = runBlocking<Unit> {
        val agent = signedIn()
        eventually { socket != null }
        val data = DeviceData(listOf(GameAccountView("a1", "game", "主号", createdAt = 1)), emptyList(), emptyList(), emptyList(), emptyList(), "Asia/Shanghai")
        socket!!.send(json.encodeToString(ServerMessage.serializer(), ServerMessage.Data(data)))
        eventually { agent.data.value == data }
        assertEquals(data, dataStore.value)
        agent.signOut()
        assertEquals(null, agent.data.value); assertEquals(null, dataStore.value)
    }
    private fun accountLease(uid: String) = leaseJson().dropLast(1) + ""","account":{"accountId":"a","name":"小号","uid":"$uid","nickname":"昵称$uid"}}"""
    @Test fun aJobOnTheSignedInAccountOnlyIdentifiesItFirst() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Rejected("止于此"); lease = accountLease("111"); signedIn()
        eventually { reports.size == 2 }
        assertEquals(listOf("IDENTIFY:"), accountRuns); assertEquals(listOf("cloud/j1/3"), enqueued)
    }
    @Test fun aJobOnAnotherAccountSwitchesAndChecksTheNumberBeforeRunning() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Rejected("止于此"); lease = accountLease("222"); signedIn()
        eventually { reports.size == 2 }
        assertEquals(listOf("IDENTIFY:", "SWITCH:昵称222"), accountRuns); assertEquals(listOf("cloud/j1/3"), enqueued)
    }
    @Test fun aSwitchThatLandsOnAnotherAccountNeverRunsTheJob() = runBlocking<Unit> {
        switchesTo = "333"; lease = accountLease("222"); signedIn()
        eventually { reports.size == 2 }
        assertEquals(JobState.FAILED, reports.last().state); assertTrue(reports.last().detail.contains("333"))
        assertTrue(enqueued.isEmpty())
    }
    @Test fun theAppReturnsToItsDefaultAccountBeforeTheJobIsReported() = runBlocking<Unit> {
        finishedRun = 5; enqueueResult = CloudEnqueueResult.Accepted(5)
        lease = accountLease("222").dropLast(1) + ""","restore":{"accountId":"m","name":"主号","uid":"111","nickname":"昵称111"}}"""
        signedIn()
        eventually { reports.size == 2 }
        // The server sends the next job once this one is reported, so the way back is already done by then.
        assertEquals(listOf("IDENTIFY:", "SWITCH:昵称222", "IDENTIFY:", "SWITCH:昵称111"), accountRuns)
        assertEquals(JobState.COMPLETED, reports.last().state); assertEquals("111", signedInUid)
    }
    @Test fun aRestartWhileSwitchingRunsTheAccountTasksAgainUnderNewIds() = runBlocking<Unit> {
        enqueueResult = CloudEnqueueResult.Rejected("止于此")
        store.value = CloudCredentials(base, "me", "a@example.test", "a1", "r-a1", System.currentTimeMillis() + 3_600_000, "install", "d1",
            ActiveCloudJob("j1", 3, "game", "gift", digest = "abc", account = AccountTarget("a", "小号", "222", "昵称222"), prepRunId = 101, attempt = 1))
        agent()
        eventually { reports.size == 2 }
        assertEquals(listOf("IDENTIFY:", "SWITCH:昵称222"), accountRuns)
        assertEquals(listOf("cloud/j1/3/2/account-identify", "cloud/j1/3/2/account-switch"), accountCommands)
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
        signedIn(); eventually { store.value?.deviceId != null && socket != null }
        // The open socket keeps working; the expired token shows when the device reconnects.
        accessRejected = true; socket!!.close(1001, "restart")
        eventually { store.value?.accessToken == "a2" }
        assertEquals("r-a2", store.value?.refreshToken)
    }
    @Test fun aRejectedRefreshSignsOutAndErasesCredentials() = runBlocking<Unit> {
        val agent = signedIn(); eventually { store.value?.deviceId != null && socket != null }
        refreshRejected = true; accessRejected = true; socket!!.close(1001, "restart")
        withTimeout(5_000) { agent.state.first { it is CloudState.SignedOut } }
        assertNull(store.value)
    }
    @Test fun signingOutErasesCredentialsEvenWhenTheServerIsGone() = runBlocking<Unit> {
        val agent = signedIn(); eventually { store.value?.deviceId != null }
        server.close()
        agent.signOut()
        assertNull(store.value); assertTrue(agent.state.value is CloudState.SignedOut)
    }

    private fun data(rules: List<DeviceRule>) = json.encodeToString(ServerMessage.serializer(),
        ServerMessage.Data(DeviceData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "Asia/Shanghai", rules)))
    @Test fun whatTheDeviceNoticesIsShownHereWhenSubscribedAndReported() = runBlocking<Unit> {
        signedIn(); eventually { socket != null }
        // A run waits on a lost condition.
        executorState.value = executorState.value.copy(phase = io.github.yysassist.core.execution.ExecutorPhase.RUNNING,
            waiting = listOf(io.github.yysassist.core.execution.RunProgress(3, 1, io.github.yysassist.core.execution.ExecutionRequest(
                io.github.yysassist.core.execution.SubmissionRef(io.github.yysassist.core.execution.SubmissionKind.TASK, "gift"),
                ExecutionContext("game", Principal.Anonymous), "v1", listOf(io.github.yysassist.core.execution.TaskSnapshot("gift", "每日礼包", 1, listOf("a")))), 1)))
        blockedReason = "等待无障碍连接"
        socket!!.send(data(listOf(DeviceRule("condition-lost", local = true))))
        eventually { noticeBatches.isNotEmpty() }
        val event = noticeBatches.first().events.single()
        assertEquals("condition-lost", event.ruleId); assertTrue(event.shown)
        assertEquals(1, notified.size); assertTrue(notified.single().contains("运行条件中断"))
        eventually { store.value?.pendingNotices?.isEmpty() == true }
    }
    @Test fun aPushedNoticeIsShownOnceEvenWhenSentAgain() = runBlocking<Unit> {
        signedIn(); eventually { socket != null }
        val push = json.encodeToString(ServerMessage.serializer(), ServerMessage.Notify(DeviceNotice("n1", "任务成功：主号 · 返回庭院", "", 1)))
        socket!!.send(push); socket!!.send(push)
        eventually { notified.isNotEmpty() }
        delay(200)
        assertEquals(listOf("【护肝宝】任务成功：主号 · 返回庭院"), notified.toList())
    }
}
