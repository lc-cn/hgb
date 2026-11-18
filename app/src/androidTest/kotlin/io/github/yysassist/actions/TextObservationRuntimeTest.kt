package io.github.yysassist.actions

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.Intent
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
import io.github.yysassist.core.execution.ActionProvider
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
        var downs = 0
        var claimed = false
        var duplicate = false
        var bothStates = false
        var textLeft = 90f
        var textBaseline = 400f
        var lastDownY: Float? = null
        /** Optional claim rules: a long hold, or a drag of at least this many pixels downward. */
        var holdMillis: Long? = null
        var dragPixels: Float? = null
        /** Claim only when the press lands inside this blank area (tap-outside dismissal). */
        var blankClaim: RectF? = null
        /** Extra fixed texts (text, left, baseline) and an optional solid red box, drawn every frame. */
        var extras: List<Triple<String, Float, Float>> = emptyList()
        var redBox: RectF? = null
        var downPoint: PointF? = null
        var upPoint: PointF? = null
        var heldMillis: Long? = null
        var moves = 0
        private var downOnTarget = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
        private fun text() = if (claimed) "已领取" else "领取物资"
        private fun targetBounds() = RectF(textLeft, textBaseline + paint.fontMetrics.top,
            textLeft + paint.measureText(text()), textBaseline + paint.fontMetrics.bottom)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            canvas.drawText(text(), textLeft, textBaseline, paint)
            if (duplicate) canvas.drawText("领取物资", 90f, 650f, paint)
            if (bothStates) canvas.drawText("已领取", 90f, 800f, paint)
            extras.forEach { (text, x, y) -> canvas.drawText(text, x, y, paint) }
            redBox?.let { canvas.drawRect(it, Paint().apply { color = Color.rgb(230, 20, 20) }) }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touches++
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downs++; lastDownY = event.y; downOnTarget = targetBounds().contains(event.x, event.y); downPoint = PointF(event.x, event.y)
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) moves++
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                upPoint = PointF(event.x, event.y); heldMillis = event.eventTime - event.downTime
                val hold = holdMillis; val drag = dragPixels
                val accepted = when {
                    blankClaim != null -> blankClaim!!.contains(event.x, event.y) && blankClaim!!.contains(downPoint!!.x, downPoint!!.y)
                    drag != null -> downOnTarget && event.y - downPoint!!.y >= drag
                    hold != null -> downOnTarget && targetBounds().contains(event.x, event.y) && heldMillis!! >= hold
                    else -> downOnTarget && targetBounds().contains(event.x, event.y)
                }
                if (accepted) { claimed = true; invalidate() }
            }
            return true
        }
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
        beforePersist: suspend (PreparedExecutorTransition) -> Unit = {}, onWorkFinished: (StepOutcome) -> Unit = {},
        withInput: Boolean = false, durationMs: Long = 80,
        submit: suspend (ExecutorCommand) -> RuntimeCommandResult = { runtime.submit(it).await() },
        onActionFinished: suspend (ActionDefinition, ActionResult) -> Unit = { _, _ -> },
        input: (ActionDefinition) -> ActionDefinition = { it },
        transform: (TaskBundle) -> TaskBundle = { it }) {
        lateinit var observer: VersionedTextObserver
        val provider = ScreenActions({ observer }, { runtime.permitsInput(it) }, device, submit)
        val watchedProvider = object : ActionProvider by provider {
            override fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession {
                val actual = provider.open(attempt, bundle)
                return object : ActionSession by actual {
                    override suspend fun perform(action: ActionDefinition, inputs: JsonObject) = actual.perform(action, inputs).also { onActionFinished(action, it) }
                }
            }
        }
        val factory = BuiltInStepWorkFactory(watchedProvider)
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
        val read = ActionDefinition("read", ScreenActions.READ, 2, buildJsonObject { put("profile", "own") })
        val verify = ActionDefinition("verify", ScreenActions.EXPECT, 1,
            buildJsonObject { put("text", expected); put("minimumConfidence", 0.75) },
            mapOf("observation" to ActionInputBinding.Output(if (withInput) "after" else "read", "observation")))
        val locate = ActionDefinition("locate", ScreenActions.LOCATE, 1,
            buildJsonObject { put("text", "领取物资"); put("minimumConfidence", 0.75) },
            mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        val tap = input(ActionDefinition("tap", ScreenActions.TAP, 1, buildJsonObject { put("durationMs", durationMs) },
            mapOf("target" to ActionInputBinding.Output("locate", "target"))))
        val task = TaskDefinition(address.taskId, address.targetId, "自有画面核对", "tools", 1,
            steps = listOf(StepDefinition("read", "观察后核对", if (withInput) listOf(read, locate, tap, read.copy(id = "after"), verify) else listOf(read, verify))))
        val target = TargetProfile(address.targetId, "测试画面", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val bundle = transform(TaskBundle(1, "text-runtime", "2026-11-16T00:00:00Z", "独立画面夹具",
            listOf(task, task.copy(id = "read-next", name = "后续自有画面核对")), listOf(target), resources,
            listOf(VisionProfile("own", address.targetId, context.packageName, MaaTextBackend.CONTRACT,
                mapOf("detector" to "det.onnx", "recognizer" to "rec.onnx", "dictionary" to "keys.txt")))))
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
        runtime = ExecutionRuntime(store, library, TaskAdmissionRepository(database!!, library), watched, runScope,
            startReadiness = DeviceExecutionReadiness(factory.contracts))
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
    @Test fun aDisconnectedDeviceBeforeExecutionWaitsWithoutInventingBusinessUncertaintyAndRunsAfterConnection(): Unit = runBlocking {
        val workFinished = CompletableDeferred<StepOutcome>()
        prepare("领取物资", onWorkFinished = { workFinished.complete(it) })
        val component = "${context.packageName}/${GameAccessibilityService::class.java.name}"
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { GameAccessibilityService.connection.first { it == null } }
        assertTrue(runtime.enqueueTask(address).await() is RuntimeCommandResult.Applied)
        val waiting = withTimeout(10_000) { combine(runtime.state, runtime.status) { state, status -> state to status }
            .first { (state, status) -> state.unresolved.isNotEmpty() || status.toString().contains("无障碍") } }
        assertTrue(waiting.toString(), waiting.first.unresolved.isEmpty())
        assertNotNull(waiting.first.current); assertFalse(workFinished.isCompleted)
        assertTrue(waiting.first.deviceActions.isEmpty()); assertEquals(1, library.snapshot().versions.single().referenceCount)
        shell("settings put secure enabled_accessibility_services $component"); shell("settings put secure accessibility_enabled 1")
        withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first() }
        val done = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(done.toString(), done.unresolved.isEmpty()); assertNull(done.current)
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
    }
    @Test fun anOffscreenTaskWaitsAndFocusReturningRechecksWithoutLaunchingOrTouchingTheTarget(): Unit = runBlocking {
        val workFinished = CompletableDeferred<StepOutcome>()
        prepare("领取物资", onWorkFinished = { workFinished.complete(it) })
        val bridge = GameAccessibilityService.connection.value!!.bridge
        shell("input keyevent KEYCODE_HOME")
        withTimeout(10_000) { while (bridge.availability(setOf(context.packageName)) != DeviceAvailability.WRONG_FOREGROUND) delay(30) }
        runtime.enqueueTask(address).await()
        withTimeout(10_000) { runtime.status.first { it is RuntimeStatus.Waiting } }
        assertFalse(workFinished.isCompleted); assertTrue(runtime.state.value.unresolved.isEmpty())
        context.startActivity(Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        val done = try { withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } } }
        catch (error: TimeoutCancellationException) {
            val focus = shell("dumpsys window").lineSequence().filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }.joinToString("\n")
            File(context.filesDir, "qa/offscreen-return.txt").apply { parentFile!!.mkdirs() }.writeText(
                "availability=${bridge.availability(setOf(context.packageName))}\nstatus=${runtime.status.value}\nstate=${runtime.state.value}\n$focus\n")
            throw error
        }
        assertTrue(done.toString(), done.unresolved.isEmpty()); assertNull(done.current)
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
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
        override val rawPixels = true // Same pixel transport as the real backend it delegates to.
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
            val call = MaaProcessCall(context, files, imageRead, nativeBlock = true, rawSize = width to height) {
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

    @Test fun semanticTargetProducesOneRealGestureAndFreshBusinessProof(): Unit = runBlocking {
        prepare("已领取", withInput = true)
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
        assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(ScreenActions.TAP, state.deviceActions.single().intent.capability)
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
        assertEquals(0, library.snapshot().versions.single().referenceCount)
        assertEquals(state.deviceActions, ExecutionStateStore(database!!, library).load().decode().state.value.deviceActions)
    }
    @Test fun longPressHoldsTheReresolvedTargetForTheDeclaredDuration(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.holdMillis = 600 }
        prepare("已领取", withInput = true, input = { it.copy(capability = ScreenActions.LONG_PRESS, inputs = buildJsonObject { put("durationMs", 900) }) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        withContext(Dispatchers.Main) { assertEquals(1, surface.downs); assertTrue("held=${surface.heldMillis}", surface.heldMillis!! >= 800) }
        assertEquals(ScreenActions.LONG_PRESS, state.deviceActions.single().intent.capability)
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
    }
    @Test fun swipeDragsFromTheReresolvedTargetByTheScaledOffsetInOneStroke(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.dragPixels = 150f }
        val width = withContext(Dispatchers.Main) { surface.rootView.width }; val height = withContext(Dispatchers.Main) { surface.rootView.height }
        prepare("已领取", withInput = true, input = { it.copy(capability = ScreenActions.SWIPE, inputs = buildJsonObject {
            put("durationMs", 400); put("dx", 0); put("dy", 300); put("referenceWidth", width); put("referenceHeight", height)
            put("variation", buildJsonObject { put("startJitter", 0); put("curve", 0); put("durationJitter", 0); put("points", 2) }) }) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        withContext(Dispatchers.Main) {
            assertEquals(1, surface.downs); assertTrue(surface.moves > 0)
            val travelled = surface.upPoint!!.y - surface.downPoint!!.y
            assertTrue("travelled=$travelled", travelled in 250f..350f)
            assertTrue(kotlin.math.abs(surface.upPoint!!.x - surface.downPoint!!.x) < 5f)
        }
        assertEquals(ScreenActions.SWIPE, state.deviceActions.single().intent.capability)
    }
    @Test fun aSeededVariedSwipeBendsWithinBoundsAndRecordsItsSeed(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.dragPixels = 150f }
        val width = withContext(Dispatchers.Main) { surface.rootView.width }; val height = withContext(Dispatchers.Main) { surface.rootView.height }
        val traces = mutableListOf<JsonObject>()
        prepare("已领取", withInput = true, onActionFinished = { action, result ->
            if (action.capability == ScreenActions.SWIPE) (result as? ActionResult.Produced)?.trace?.let { traces += it } },
            input = { it.copy(capability = ScreenActions.SWIPE, inputs = buildJsonObject {
                put("durationMs", 500); put("dx", 0); put("dy", 300); put("referenceWidth", width); put("referenceHeight", height)
                put("variation", buildJsonObject { put("startJitter", 0.5); put("curve", 0.2); put("durationJitter", 0.2); put("points", 16); put("seed", 7) }) }) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        val trace = traces.single()
        assertEquals(7L, trace.getValue("seed").jsonPrimitive.long); assertEquals(16, trace.getValue("points").jsonPrimitive.int)
        assertTrue(trace.getValue("durationMs").jsonPrimitive.long in 400L..600L)
        withContext(Dispatchers.Main) { assertTrue(surface.moves > 2) }
        assertTrue(state.deviceActions.single().receipt!!.evidence.contains("滑动种子 7"))
    }
    @Test fun backKeyGoesThroughTheRealBridgeAndNeedsFreshProof(): Unit = runBlocking {
        scenario!!.onActivity { activity ->
            activity.onBackPressedDispatcher.addCallback(activity, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { surface.claimed = true; surface.invalidate() }
            })
        }
        prepare("已领取", withInput = true, input = { it.copy(capability = ScreenActions.BACK, inputs = JsonObject(emptyMap()),
            bindings = mapOf("observation" to ActionInputBinding.Output("read", "observation"))) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(ScreenActions.BACK, state.deviceActions.single().intent.capability)
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
    }
    private fun regionProgram(requireNoText: Boolean, dx: Int, dy: Int, width: Int, height: Int, w: Int, h: Int): (TaskBundle) -> TaskBundle = { value ->
        val steps = value.tasks.first().steps.single()
        val read = steps.actions.first { it.id == "read" }; val locate = steps.actions.first { it.id == "locate" }
        val after = steps.actions.first { it.id == "after" }; val verify = steps.actions.first { it.id == "verify" }
        val derive = ActionDefinition("derive", ScreenActions.DERIVE, 1, buildJsonObject {
            put("dx", dx); put("dy", dy); put("width", width); put("height", height); put("referenceWidth", w); put("referenceHeight", h)
        }, mapOf("target" to ActionInputBinding.Output("locate", "target")))
        val press = ActionDefinition("tap", ScreenActions.TAP_REGION, 1, buildJsonObject { put("durationMs", 80); put("requireNoText", requireNoText) },
            mapOf("region" to ActionInputBinding.Output("derive", "region")))
        value.copy(tasks = value.tasks.map { it.copy(steps = listOf(steps.copy(actions = listOf(read, locate, derive, press, after, verify)))) })
    }
    @Test fun tapRegionPressesBlankSpaceDerivedFromAFreshAnchor(): Unit = runBlocking {
        val width = withContext(Dispatchers.Main) { surface.width }; val height = withContext(Dispatchers.Main) { surface.height }
        // Blank area below the anchor text; the surface claims only when pressed there.
        withContext(Dispatchers.Main) { surface.blankClaim = RectF(0f, 520f, width.toFloat(), 680f) }
        prepare("已领取", withInput = true, transform = regionProgram(true, -100, 120, 200, 140, width, height))
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        withContext(Dispatchers.Main) { assertEquals(1, surface.downs); assertTrue(surface.lastDownY!! in 520f..680f) }
        assertEquals(ScreenActions.TAP_REGION, state.deviceActions.single().intent.capability)
    }
    @Test fun tapRegionRefusesARegionThatShowsText(): Unit = runBlocking {
        val width = withContext(Dispatchers.Main) { surface.width }; val height = withContext(Dispatchers.Main) { surface.height }
        // A region around the anchor itself contains text: no intent, no touch.
        prepare("已领取", withInput = true, transform = regionProgram(true, -150, -120, 300, 160, width, height))
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() || it.facts.any { fact -> fact.type == "task-completed" } } }
        assertTrue(state.deviceActions.isEmpty())
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertTrue(state.toString(), (state.facts.map { it.detail } + state.unresolved.map { it.reason }).any { it.contains("文字，不点击") })
    }
    /** Replaces the step with [actions]; read/after/verify keep their prepared definitions. */
    private fun steps(vararg extra: (Map<String, ActionDefinition>) -> List<ActionDefinition>): (TaskBundle) -> TaskBundle = { value ->
        val step = value.tasks.first().steps.single(); val by = step.actions.associateBy { it.id }
        value.copy(tasks = value.tasks.map { it.copy(steps = listOf(step.copy(actions = extra.flatMap { f -> f(by) }))) })
    }
    private fun out(action: String, field: String) = ActionInputBinding.Output(action, field)
    @Test fun aFractionIsReadOnceAndRangeChecked(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.extras = listOf(Triple("12/30", 90f, 1000f)); surface.invalidate() }
        val outputs = mutableMapOf<String, JsonObject>()
        prepare("12/30", onActionFinished = { action, result -> (result as? ActionResult.Produced)?.let { outputs[action.id] = it.outputs } },
            transform = steps({ by -> listOf(by.getValue("read"), ActionDefinition("number", ScreenActions.READ_FRACTION, 1, buildJsonObject {
                put("minimumConfidence", 0.7); put("minimum", 0); put("maximum", 999) }, mapOf("observation" to out("read", "observation"))),
                ActionDefinition("verify", ScreenActions.EXPECT, 1, buildJsonObject { put("text", "12/30"); put("minimumConfidence", 0.7) },
                    mapOf("observation" to out("read", "observation")))) }))
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        assertEquals(12, outputs.getValue("number").getValue("value").jsonPrimitive.int)
        assertEquals(30, outputs.getValue("number").getValue("total").jsonPrimitive.int)
    }
    @Test fun theSecondOfTwoEqualLabelsIsSelectedAndPressed(): Unit = runBlocking {
        val width = withContext(Dispatchers.Main) { surface.width }
        withContext(Dispatchers.Main) {
            surface.extras = listOf(Triple("进攻", 90f, 1000f), Triple("进攻", 90f, 1300f))
            surface.blankClaim = RectF(0f, 1180f, width.toFloat(), 1340f); surface.invalidate()
        }
        prepare("已领取", withInput = true, transform = steps({ by -> listOf(by.getValue("read"),
            ActionDefinition("pick", ScreenActions.SELECT_TEXT, 1, buildJsonObject {
                put("text", "进攻"); put("minimumConfidence", 0.7); put("order", "top"); put("index", 1) }, mapOf("observation" to out("read", "observation"))),
            ActionDefinition("tap", ScreenActions.TAP, 1, buildJsonObject { put("durationMs", 80) }, mapOf("target" to out("pick", "target"))),
            by.getValue("after"), by.getValue("verify")) }))
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        withContext(Dispatchers.Main) { assertEquals(1, surface.downs); assertTrue(surface.lastDownY!! in 1180f..1340f) }
    }
    @Test fun colorShareAndBeforeAfterDifferenceAreMeasuredFromObservedPixels(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.redBox = RectF(90f, 480f, 390f, 600f); surface.invalidate() }
        val outputs = mutableMapOf<String, JsonObject>()
        fun region(id: String, anchor: String) = ActionDefinition(id, ScreenActions.DERIVE, 1, buildJsonObject {
            put("dx", -150); put("dy", 40); put("width", 300); put("height", 150); put("referenceWidth", 1080); put("referenceHeight", 2400)
        }, mapOf("target" to out(anchor, "target")))
        val geometry = withContext(Dispatchers.Main) { surface.rootView.width to surface.rootView.height }
        prepare("已领取", withInput = true, onActionFinished = { action, result -> (result as? ActionResult.Produced)?.let { outputs[action.id] = it.outputs } },
            transform = { value -> steps({ by -> listOf(by.getValue("read"), by.getValue("locate"),
                region("box", "locate").let { it.copy(inputs = JsonObject(it.inputs + mapOf("referenceWidth" to JsonPrimitive(geometry.first), "referenceHeight" to JsonPrimitive(geometry.second)))) },
                ActionDefinition("red", ScreenActions.MATCH_COLOR, 1, buildJsonObject {
                    put("lower", buildJsonArray { add(200); add(0); add(0) }); put("upper", buildJsonArray { add(255); add(60); add(60) }); put("minimumRatio", 0.3)
                }, mapOf("region" to out("box", "region"))),
                by.getValue("tap"), by.getValue("after"),
                ActionDefinition("doneLabel", ScreenActions.LOCATE, 1, buildJsonObject { put("text", "已领取"); put("minimumConfidence", 0.7) },
                    mapOf("observation" to out("after", "observation"))),
                region("label", "doneLabel").let { it.copy(inputs = JsonObject(it.inputs + mapOf("dy" to JsonPrimitive(-110), "height" to JsonPrimitive(120),
                    "referenceWidth" to JsonPrimitive(geometry.first), "referenceHeight" to JsonPrimitive(geometry.second)))) },
                ActionDefinition("diff", ScreenActions.COMPARE, 1, buildJsonObject { put("minimumDifference", 0.02) },
                    mapOf("before" to out("read", "observation"), "region" to out("label", "region"))),
                by.getValue("verify")) })(value) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        assertTrue(outputs.getValue("red").toString(), outputs.getValue("red").getValue("matched").jsonPrimitive.boolean)
        assertTrue(outputs.getValue("diff").toString(), outputs.getValue("diff").getValue("changed").jsonPrimitive.boolean)
    }
    @Test fun aSwipeEndingOffscreenIsRefusedBeforeAnyIntent(): Unit = runBlocking {
        val width = withContext(Dispatchers.Main) { surface.rootView.width }; val height = withContext(Dispatchers.Main) { surface.rootView.height }
        prepare("已领取", withInput = true, input = { it.copy(capability = ScreenActions.SWIPE, inputs = buildJsonObject {
            put("durationMs", 400); put("dx", 0); put("dy", -5000); put("referenceWidth", width); put("referenceHeight", height)
            put("variation", buildJsonObject { put("startJitter", 0); put("curve", 0); put("durationJitter", 0); put("points", 2) }) }) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() || it.facts.any { fact -> fact.type == "task-completed" } } }
        assertTrue(state.deviceActions.isEmpty())
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
    }
    @Test fun duplicateTextNeverCreatesAnInputIntentOrTouchesTheSurface(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.duplicate = true; surface.invalidate() }
        prepare("已领取", withInput = true)
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertTrue(state.unresolved.single().reason, state.unresolved.single().reason.contains("唯一"))
        assertTrue(state.deviceActions.isEmpty()); assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
    }
    @Test fun aTargetThatMovedFarFromItsWindowIsNotPressed(): Unit = runBlocking {
        // Re-resolution searches only near the previous position; a far move is not chased across the screen.
        prepare("已领取", withInput = true, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.LOCATE) withContext(Dispatchers.Main) {
                surface.textLeft = 90f; surface.textBaseline = surface.height - 120f; surface.invalidate()
            }
        })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() || it.facts.any { fact -> fact.type == "task-completed" } } }
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertTrue(state.toString(), state.unresolved.any { it.reason.contains("原位置附近") })
    }
    @Test fun inputReobservesAMovedTargetRatherThanUsingTheOldCoordinates(): Unit = runBlocking {
        prepare("已领取", withInput = true, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.LOCATE) withContext(Dispatchers.Main) {
                surface.textLeft = 350f; surface.textBaseline = 700f; surface.invalidate()
            }
        })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
        withContext(Dispatchers.Main) { assertEquals(1, surface.downs); assertTrue(surface.claimed); assertTrue(surface.lastDownY!! > 600f) }
    }
    @Test fun aTargetThatDisappearsBeforeInputIsNotClickedOrRegistered(): Unit = runBlocking {
        prepare("已领取", withInput = true, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.LOCATE) withContext(Dispatchers.Main) { surface.claimed = true; surface.invalidate() }
        })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertTrue(state.deviceActions.isEmpty()); assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
    }
    @Test fun pauseAfterIntentCommitBeforeDispatchRecordsNotSentAndContinueStartsFresh(): Unit = runBlocking {
        val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        prepare("已领取", withInput = true, submit = { command ->
            runtime.submit(command).await().also {
                if (command is ExecutorCommand.RecordDeviceAction && !committed.isCompleted) { committed.complete(Unit); release.await() }
            }
        })
        try {
            runtime.enqueueTask(address).await(); withTimeout(30_000) { committed.await() }
            val old = runtime.state.value.activeTicket
            runtime.submit(ExecutorCommand.Pause).await()
            assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
            release.complete(Unit)
            val paused = withTimeout(10_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED || it.unresolved.isNotEmpty() } }
            assertTrue(paused.toString(), paused.unresolved.isEmpty()); assertTrue(paused.manualHold)
            assertEquals(DeviceActionDelivery.NOT_SENT, paused.deviceActions.single().receipt!!.delivery)
            runtime.submit(ExecutorCommand.Resume(ResumeMode.CONTINUE)).await()
            val done = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
            assertTrue(done.toString(), done.unresolved.isEmpty()); assertNull(done.current)
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
            assertEquals(2, done.deviceActions.size)
            assertNotEquals(old, done.deviceActions.last().intent.ticket)
            assertEquals(DeviceActionDelivery.COMPLETED, done.deviceActions.last().receipt!!.delivery)
        } finally { release.complete(Unit) }
    }
    @Test fun pauseDuringRealGestureRetainsUnknownUntilBusinessCanBeObserved(): Unit = runBlocking {
        prepare("已领取", withInput = true, durationMs = 800)
        runtime.enqueueTask(address).await()
        withTimeout(30_000) { while (withContext(Dispatchers.Main) { surface.downs == 0 }) delay(5) }
        val ticket = runtime.state.value.activeTicket
        runtime.submit(ExecutorCommand.Pause).await()
        val state = withTimeout(10_000) { runtime.state.first { it.unresolved.isNotEmpty() && it.phase == ExecutorPhase.BLOCKED_UNKNOWN } }
        assertTrue(state.manualHold); assertEquals(ticket, state.unresolved.single().attempt.ticket)
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
        assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        assertEquals(1, library.snapshot().versions.single().referenceCount)
    }
    @Test fun platformCompletionCannotReplaceTheMissingPostcondition(): Unit = runBlocking {
        prepare("结果没有出现", withInput = true)
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
        assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        assertEquals(1, library.snapshot().versions.single().referenceCount)
    }
    @Test fun cancellationAfterFinalProofRetainsCompletionRatherThanReplayingTheInput(): Unit = runBlocking {
        val proved = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        prepare("已领取", withInput = true, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.EXPECT) { proved.complete(Unit); hold.await() }
        })
        try {
            runtime.enqueueTask(address).await(); withTimeout(30_000) { proved.await() }
            runtime.submit(ExecutorCommand.Pause).await()
            val state = withTimeout(10_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED || it.unresolved.isNotEmpty() } }
            assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
            assertEquals(1, state.facts.count { it.type == "task-completed" })
            runtime.submit(ExecutorCommand.Resume(ResumeMode.CONTINUE)).await()
            assertNull(runtime.state.value.current)
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        } finally { hold.complete(Unit) }
    }
    @Test fun rejectedHighPriorityAdmissionReentersTheOldInputProgramWhenNothingWasSent(): Unit = runBlocking {
        val located = CompletableDeferred<Unit>(); val permitClosed = CompletableDeferred<Unit>()
        val hint = CompletableDeferred<StepOutcome>(); val highEntered = CompletableDeferred<Unit>(); val highRelease = CompletableDeferred<Unit>()
        prepare("已领取", withInput = true, transform = { value -> value.copy(tasks = value.tasks.map {
            if (it.id == "read-next") it.copy(priority = 2, fields = listOf(ConfigurationField("amount", "测试值", FieldWidget.NUMBER, true))) else it
        }) }, beforePersist = {
            if (it.admittedRunId != null && it.snapshot.waiting.any { run -> run.request.ref.id == "read-next" }) {
                highEntered.complete(Unit); highRelease.await()
            }
        }, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.LOCATE && !located.isCompleted) {
                located.complete(Unit); permitClosed.await()
                assertFalse(runtime.permitsInput(runtime.state.value.activeTicket!!))
            }
        }, onWorkFinished = { if (it == StepOutcome.Interrupted) hint.complete(it) })
        val high = address.copy(taskId = "read-next")
        val configurations = TaskConfigurationRepository(database!!, library)
        assertTrue(configurations.save(configurations.open(high), buildJsonObject { put("amount", 1) }) is ConfigurationSaveResult.Saved)
        try {
            runtime.enqueueTask(address).await(); withTimeout(30_000) { located.await() }
            val original = runtime.state.value.current!!
            val candidate = runtime.enqueueTask(high)
            withTimeout(5_000) { highEntered.await() }
            permitClosed.complete(Unit); assertEquals(StepOutcome.Interrupted, withTimeout(5_000) { hint.await() })
            assertTrue(configurations.save(configurations.open(high), buildJsonObject { put("amount", 2) }) is ConfigurationSaveResult.Saved)
            highRelease.complete(Unit)
            assertTrue(candidate.await() is RuntimeCommandResult.Rejected)
            val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
            assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
            assertTrue(state.facts.any { it.type == "step-reentered" && it.runId == original.runId })
            assertFalse(state.facts.any { it.taskId == high.taskId })
            assertTrue(state.waiting.isEmpty()); assertEquals(0, library.snapshot().versions.single().referenceCount)
        } finally { permitClosed.complete(Unit); highRelease.complete(Unit) }
    }

    private fun classified(value: TaskBundle, inside: Boolean = false, absent: Boolean = false): TaskBundle = value.copy(tasks = value.tasks.map { task ->
        val old = task.steps.single(); val (read, locate, tap, after, verify) = old.actions
        val classifier = ActionDefinition("classify", ScreenActions.CLASSIFY, 1, buildJsonObject {
            put("states", buildJsonArray {
                add(buildJsonObject { put("id", "ready"); put("text", if (absent) "不存在的待领状态" else "领取物资"); put("minimumConfidence", 0.75) })
                add(buildJsonObject { put("id", "done"); put("text", if (absent) "不存在的已领状态" else "已领取"); put("minimumConfidence", 0.75) })
            })
        }, mapOf("observation" to ActionInputBinding.Output(read.id, "observation")))
        fun nodes(vararg definitions: ActionDefinition) = definitions.map(ProgramNode::Action)
        val branch = ProgramNode.Branch(ActionInputBinding.Output("classify", "state"), listOf(
            ProgramCase(JsonPrimitive("ready"), if (inside) nodes(locate, tap, after, verify) else nodes(locate, tap)),
            ProgramCase(JsonPrimitive("done"), if (inside) nodes(verify.copy(id = "already", bindings =
                mapOf("observation" to ActionInputBinding.Output(read.id, "observation")))) else emptyList()),
        ))
        task.copy(steps = listOf(old.copy(actions = emptyList(), program = nodes(read, classifier) + branch + if (inside) emptyList() else nodes(after, verify))))
    })
    @Test fun classifiedReadyPathTraversesTheRealVersionedWorkerAndClicksOnce(): Unit = runBlocking {
        prepare("已领取", withInput = true, transform = { classified(it) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
        assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(DeviceActionDelivery.COMPLETED, state.deviceActions.single().receipt!!.delivery)
        assertEquals(0, library.snapshot().versions.single().referenceCount)
    }
    @Test fun classifiedAlreadyDonePathVerifiesWithoutAnyInput(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.claimed = true; surface.invalidate() }
        prepare("已领取", withInput = true, transform = { classified(it) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty()); assertTrue(state.deviceActions.isEmpty())
        assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
    }
    @Test fun simultaneousDeclaredStatesStayUnknownWithoutInput(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.bothStates = true; surface.invalidate() }
        prepare("已领取", withInput = true, transform = { classified(it) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertTrue(state.unresolved.single().reason.contains("多个声明状态"))
        assertTrue(state.deviceActions.isEmpty()); assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
    }
    @Test fun noDeclaredStateStaysUnknownRatherThanGuessingAlreadyDone(): Unit = runBlocking {
        prepare("已领取", withInput = true, transform = { classified(it, absent = true) })
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
        assertTrue(state.unresolved.single().reason.contains("未能确认任何声明状态"))
        assertTrue(state.deviceActions.isEmpty()); assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
    }
    @Test fun cancellationAfterTheSelectedBranchFinalProofRetainsCompletion(): Unit = runBlocking {
        val proved = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        prepare("已领取", withInput = true, transform = { classified(it, inside = true) }, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.EXPECT) { proved.complete(Unit); hold.await() }
        })
        try {
            runtime.enqueueTask(address).await(); withTimeout(30_000) { proved.await() }
            runtime.submit(ExecutorCommand.Pause).await()
            val state = withTimeout(10_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED || it.unresolved.isNotEmpty() } }
            assertTrue(state.toString(), state.unresolved.isEmpty()); assertNull(state.current)
            assertEquals(1, state.facts.count { it.type == "task-completed" })
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
        } finally { hold.complete(Unit) }
    }
    private fun repeated(value: TaskBundle) = value.copy(tasks = value.tasks.map { task ->
        task.copy(steps = task.steps.map { step -> step.copy(actions = emptyList(), program = listOf(ProgramNode.Repeat(JsonPrimitive(2),
            step.actions.map { action -> ProgramNode.Action(if (action.capability == ScreenActions.LOCATE)
                action.copy(inputs = JsonObject(action.inputs + ("text" to JsonPrimitive("已领取")))) else action) }))) })
    })
    @Test fun repeatedInputDefinitionCreatesDistinctDurableInvocationIds(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.claimed = true; surface.invalidate() }
        prepare("已领取", withInput = true, transform = ::repeated)
        runtime.enqueueTask(address).await()
        val state = withTimeout(30_000) { runtime.state.first { it.facts.any { fact -> fact.type == "task-completed" } || it.unresolved.isNotEmpty() } }
        assertTrue(state.toString(), state.unresolved.isEmpty())
        assertEquals(2, withContext(Dispatchers.Main) { surface.downs })
        assertEquals(2, state.deviceActions.size)
        assertEquals(2, state.deviceActions.map { it.intent.id }.distinct().size)
        assertEquals(2, state.deviceActions.map { it.intent.observationReference }.distinct().size)
        assertEquals(1, state.deviceActions.map { it.intent.actionId }.distinct().size)
        assertTrue(state.deviceActions.all { it.receipt!!.delivery == DeviceActionDelivery.COMPLETED })
    }
    @Test fun intermediateLoopProofCannotCompleteTheWholeStepOnPause(): Unit = runBlocking {
        withContext(Dispatchers.Main) { surface.claimed = true; surface.invalidate() }
        val proved = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        prepare("已领取", withInput = true, transform = ::repeated, onActionFinished = { action, _ ->
            if (action.capability == ScreenActions.EXPECT && !proved.isCompleted) { proved.complete(Unit); hold.await() }
        })
        try {
            runtime.enqueueTask(address).await(); withTimeout(30_000) { proved.await() }
            runtime.submit(ExecutorCommand.Pause).await()
            val state = withTimeout(10_000) { runtime.state.first { it.unresolved.isNotEmpty() } }
            assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, state.phase); assertTrue(state.manualHold)
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
            assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        } finally { hold.complete(Unit) }
    }
}
