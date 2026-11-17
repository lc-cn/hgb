package io.github.yysassist.actions

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.*
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.*
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.device.*
import io.github.yysassist.storage.*
import io.github.yysassist.vision.*
import io.github.yysassist.vision.maa.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Actual executor -> platform screenshot -> version blobs -> native child -> explicit text check. */
@RunWith(AndroidJUnit4::class)
class TextObservationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var database: AssistDatabase? = null
    private var scope: CoroutineScope? = null
    private lateinit var folder: File
    private lateinit var name: String
    private lateinit var library: TaskVersionLibrary
    private lateinit var runtime: ExecutionRuntime
    private lateinit var surface: OwnSurface
    private lateinit var automation: UiAutomation
    private val address = TaskAddress("fixture", "read")
    private class OwnSurface(activity: ComponentActivity) : View(activity) {
        var touches = 0
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            canvas.drawText("领取物资", 90f, 400f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
        }
        override fun onTouchEvent(event: MotionEvent): Boolean { touches++; return true }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { it.readText().trim() } }

    @Before fun open(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit screenshot/native integration on isolated emulator only", Build.HARDWARE == "ranchu" &&
            args.getString("allowDeviceGrantChanges") == "true" && args.getString("allowOcrIntegration") == "true")
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "${context.packageName}/${GameAccessibilityService::class.java.name}"
        val current = shell("settings get secure enabled_accessibility_services")
        check(current in listOf("null", "", component)) { "Do not modify other accessibility services" }
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(5_000) { GameAccessibilityService.connection.first { it == null } }
        shell("settings put secure enabled_accessibility_services $component"); shell("settings put secure accessibility_enabled 1")
        withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first() }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            surface = OwnSurface(activity); activity.setContentView(surface)
        }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { surface.width <= 0 || surface.width > surface.height }) delay(20) }
        delay(400)
        name = "text-runtime-${UUID.randomUUID()}.db"; folder = File(context.noBackupFilesDir, name)
        database = AssistDatabase.open(context, name)
    }
    @After fun close(): Unit = runBlocking {
        scope?.let { it.cancel(); it.coroutineContext[Job]!!.join() }
        database?.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        if (::folder.isInitialized) assertTrue(folder.deleteRecursively())
        scenario?.close()
    }

    private suspend fun prepare(expected: String, corrupt: Boolean = false, backend: TextBackend = MaaTextBackend(context),
        device: () -> ObservationDevice? = { GameAccessibilityService.connection.value?.bridge },
        beforePersist: suspend (PreparedExecutorTransition) -> Unit = {}, onWorkFinished: (StepOutcome) -> Unit = {}) {
        lateinit var observer: VersionedTextObserver
        val factory = BuiltInStepWorkFactory(TextObservationActions({ observer }, { runtime.permitsInput(it) }, device))
        val codec = BundleCodec(factory.contracts)
        library = TaskVersionLibrary(database!!, codec, TaskResourceFiles(folder))
        observer = VersionedTextObserver(library, backend)
        val manifest = JSONObject(instrumentation.context.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        val resources = (0 until manifest.length()).map { index ->
            val pin = manifest.getJSONObject(index); val id = pin.getString("path")
            if (!corrupt) TaskResource(id, id, pin.getLong("bytes"), pin.getString("sha256"))
            else {
                val bytes = if (id == "keys.txt") "a\nb\n".toByteArray() else byteArrayOf(1, 2, 3)
                TaskResource(id, id, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }
        val read = ActionDefinition("read", TextObservationActions.READ, 1, buildJsonObject {
            put("packageName", context.packageName); put("detector", "det.onnx"); put("recognizer", "rec.onnx"); put("dictionary", "keys.txt")
        })
        val verify = ActionDefinition("verify", TextObservationActions.EXPECT, 1,
            buildJsonObject { put("text", expected); put("minimumConfidence", 0.75) },
            mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        val task = TaskDefinition(address.taskId, address.targetId, "自有画面核对", "tools", 1,
            steps = listOf(StepDefinition("read", "观察后核对", listOf(read, verify))))
        val target = TargetProfile(address.targetId, "测试画面", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val bundle = TaskBundle(1, "text-runtime", "2026-11-16T00:00:00Z", "独立只读夹具",
            listOf(task, task.copy(id = "read-next", name = "后续自有画面核对")), listOf(target), resources)
        val text = codec.write(bundle)
        assertEquals(VersionChange.Applied, library.install(text, bundle.version, TaskVersionLibrary.sha256(text)) { resource, output ->
            if (!corrupt) instrumentation.context.assets.open("maa-model/${resource.id}").use { it.copyTo(output) }
            else output.write(if (resource.id == "keys.txt") "a\nb\n".toByteArray() else byteArrayOf(1, 2, 3))
        })
        assertEquals(VersionChange.Applied, library.activate(bundle.version))
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val actualStore = ExecutionStateStore(database!!, library)
        val store = object : ExecutionPersistence {
            override suspend fun load() = actualStore.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                beforePersist(prepared); return actualStore.persist(expectedRevision, prepared)
            }
        }
        val watched = object : StepWorkFactory by factory {
            override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
                val actual = factory.create(attempt, bundle)
                return object : StepWork by actual {
                    override suspend fun execute() = actual.execute().also(onWorkFinished)
                }
            }
        }
        runtime = ExecutionRuntime(store, library, TaskAdmissionRepository(database!!, library), watched, runScope)
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
    }
    @Test fun actualScreenAndVersionModelsProduceAVerifiedRuntimeResultWithoutInput(): Unit = runBlocking {
        prepare("领取物资")
        assertTrue(runtime.enqueueTask(address).await() is RuntimeCommandResult.Applied)
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
        assertTrue(state.facts.any { it.type == "step-completed" && it.detail.contains("核对目标文字") })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
        assertEquals(0, withContext(Dispatchers.Main) { surface.touches })
        assertTrue(ExecutionStateStore(database!!, library).load().decode().state.value.facts.any { it.type == "task-completed" })
    }
    @Test fun absentTextIsUnknownRatherThanAnInventedSuccessOrConfirmedBusinessFailure(): Unit = runBlocking {
        prepare("此文字不在测试画面")
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, state.phase)
        assertTrue(state.unresolved.single().reason.contains("未能确认目标文字"))
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        assertEquals(0, withContext(Dispatchers.Main) { surface.touches })
        // The unresolved execution reference remains; foreign model use itself has ended.
        assertEquals(1, library.snapshot().versions.single().referenceCount)
    }
    @Test fun realBadModelChildCrashReachesTheRuntimeAsUnknownWithItsCause(): Unit = runBlocking {
        Assume.assumeTrue("Explicit child-native fault only", InstrumentationRegistry.getArguments().getString("allowChildNativeFaults") == "true")
        prepare("领取物资", corrupt = true)
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertTrue(state.unresolved.single().reason, state.unresolved.single().reason.contains("PROCESS_DIED"))
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, state.phase)
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        assertEquals(0, withContext(Dispatchers.Main) { surface.touches })
    }

    /** Same real subprocess protocol. Only the first call deliberately enters the debug C++ wait. */
    private inner class FirstNativeBlock : TextBackend {
        override val modelContract = MaaTextBackend.CONTRACT
        private val normal = MaaTextBackend(context)
        val first = CompletableFuture<MaaProcessCall>()
        @Volatile var calls = 0
        @Volatile var resumedAt: Long? = null
        override fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int,
            onQuiet: () -> Unit): TextBackendCall {
            if (calls++ > 0) {
                resumedAt = SystemClock.elapsedRealtimeNanos()
                return normal.start(models, imageRead, width, height, onQuiet)
            }
            val names = mapOf("det.onnx" to "detector", "rec.onnx" to "recognizer", "keys.txt" to "dictionary")
            val files = names.mapValues { (_, role) -> models.getValue(role).let { MaaModelFile(it.file, it.bytes, it.sha256) } }
            val quiet = CompletableFuture<Unit>()
            val call = MaaProcessCall(context, files, imageRead, nativeBlock = true) {
                try { onQuiet() } finally { quiet.complete(Unit) }
            }
            first.complete(call)
            return object : TextBackendCall {
                override val result: CompletableFuture<BackendTextResult> = call.completion.thenApply { outcome ->
                    check(outcome is MaaProcessOutcome.Missing)
                    BackendTextResult.Unavailable(TextReadProblem.ENGINE_UNAVAILABLE, outcome.reason.name)
                }
                override val quiet = quiet
                override fun requestStop() = call.requestStop()
            }
        }
    }
    private fun requireBlockFault() = Assume.assumeTrue("Explicit child native block only",
        InstrumentationRegistry.getArguments().getString("allowChildNativeFaults") == "true")
    private fun blockEvidence(case: String, backend: FirstNativeBlock, call: MaaProcessCall) {
        assertEquals("binder-death", call.quietEvidence)
        assertNotNull(call.binderDeathObservedAt); assertNotNull(backend.resumedAt)
        assertTrue(backend.resumedAt!! >= call.binderDeathObservedAt!!)
        val file = File(context.filesDir, "qa/observation-actions/native-control.json").apply { parentFile!!.mkdirs() }
        val previous = if (file.isFile) org.json.JSONArray(file.readText()) else org.json.JSONArray()
        previous.put(JSONObject().put("case", case).put("nativeEntered", true).put("quietEvidence", call.quietEvidence)
            .put("binderDeathAt", call.binderDeathObservedAt).put("nextObservationStartedAt", backend.resumedAt)
            .put("touches", surface.touches).put("calls", backend.calls))
        file.writeText(previous.toString(2))
    }
    @Test fun pauseStopsActualBlockedNativeUseAndContinueObservesAgain(): Unit = runBlocking {
        requireBlockFault(); val backend = FirstNativeBlock(); prepare("领取物资", backend = backend)
        runtime.enqueueTask(address).await()
        val call = withTimeout(10_000) { backend.first.awaitValue() }
        withTimeout(10_000) { call.nativeEntered.awaitValue() }
        assertEquals(2, library.snapshot().versions.single().referenceCount) // Run + live model lease.
        runtime.submit(ExecutorCommand.Pause).await()
        val paused = withTimeout(10_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED } }
        assertTrue(paused.manualHold); assertTrue(paused.unresolved.isEmpty())
        assertEquals(1, library.snapshot().versions.single().referenceCount)
        assertEquals(NoObservation.CANCELLED, (call.completion.awaitValue() as MaaProcessOutcome.Missing).reason)
        runtime.submit(ExecutorCommand.Resume(ResumeMode.CONTINUE)).await()
        val complete = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(complete.toString(), complete.unresolved.isEmpty()); assertNull(complete.current)
        assertEquals(0, library.snapshot().versions.single().referenceCount)
        assertEquals(0, withContext(Dispatchers.Main) { surface.touches })
        blockEvidence("pause-continue", backend, call)
    }
    @Test fun removingActualBlockedNativeUseRunsTheQueuedTaskAfterDeathEvidence(): Unit = runBlocking {
        requireBlockFault(); val backend = FirstNativeBlock(); prepare("领取物资", backend = backend)
        runtime.enqueueTask(address).await()
        val call = withTimeout(10_000) { backend.first.awaitValue() }
        withTimeout(10_000) { call.nativeEntered.awaitValue() }
        runtime.enqueueTask(address.copy(taskId = "read-next")).await()
        val queued = withTimeout(5_000) { runtime.state.first { it.waiting.size == 1 } }
        runtime.submit(ExecutorCommand.Remove(queued.current!!.runId)).await()
        val complete = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" && fact.taskId == "read-next" } || it.unresolved.isNotEmpty() } }
        assertTrue(complete.toString(), complete.unresolved.isEmpty()); assertNull(complete.current); assertTrue(complete.waiting.isEmpty())
        assertFalse(complete.manualHold)
        assertFalse(complete.facts.any { it.type == "task-completed" && it.taskId == address.taskId })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
        assertEquals(0, withContext(Dispatchers.Main) { surface.touches })
        blockEvidence("remove-queued", backend, call)
    }
    @Test fun pauseClosesNativeAdmissionBeforeItsDatabaseCommitFinishes(): Unit = runBlocking {
        val captured = CompletableDeferred<Unit>(); val deliver = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>(); val commitRelease = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<StepOutcome>()
        val actual = GameAccessibilityService.connection.value!!.bridge
        val source = object : ObservationDevice {
            override suspend fun capture(expectedPackage: String): FrameResult {
                val frame = actual.capture(expectedPackage); captured.complete(Unit)
                try { deliver.await(); return frame }
                catch (error: CancellationException) { (frame as? FrameResult.Ready)?.frame?.close(); throw error }
            }
            override suspend fun validateObservation(stamp: FrameStamp) = actual.validateObservation(stamp)
        }
        val normal = MaaTextBackend(context)
        var nativeStarts = 0
        val counted = object : TextBackend {
            override val modelContract = normal.modelContract
            override fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int,
                onQuiet: () -> Unit): TextBackendCall {
                nativeStarts++; return normal.start(models, imageRead, width, height, onQuiet)
            }
        }
        prepare("领取物资", backend = counted, device = { source }, beforePersist = {
            if (it.snapshot.manualHold) { commitEntered.complete(Unit); commitRelease.await() }
        }, onWorkFinished = { finished.complete(it) })
        try {
            runtime.enqueueTask(address).await(); withTimeout(10_000) { captured.await() }
            val pause = runtime.submit(ExecutorCommand.Pause)
            withTimeout(5_000) { commitEntered.await() }
            assertFalse(runtime.state.value.manualHold) // Pause has not committed yet.
            deliver.complete(Unit)
            val result = withTimeout(15_000) { finished.await() }
            assertEquals("Revoked execution must not start native work after the capture handoff", 0, nativeStarts)
            assertEquals(StepOutcome.Interrupted, result)
            commitRelease.complete(Unit); pause.await()
            val paused = withTimeout(5_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED || it.unresolved.isNotEmpty() } }
            assertTrue(paused.toString(), paused.unresolved.isEmpty()); assertTrue(paused.manualHold)
        } finally { deliver.complete(Unit); commitRelease.complete(Unit) }
    }
}
