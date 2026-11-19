package io.github.yysassist.actions

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.RuntimeCommandResult
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import io.github.yysassist.device.*
import io.github.yysassist.storage.*
import io.github.yysassist.vision.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Offline replay of the draft daily-gift step on private captured frames: real ScreenActions, real
 * versioned resources and real native recognition, with a scripted device instead of the game.
 * A tap on the free button advances to the captured reward frame; any other input fails the test.
 */
@RunWith(AndroidJUnit4::class)
class DailyGiftReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val game = "com.netease.onmyoji"
    private var database: AssistDatabase? = null
    private lateinit var name: String
    private lateinit var folder: File
    private lateinit var library: TaskVersionLibrary

    /** Scripted frames. Only a gesture whose single point lies in [claimButton] advances to [afterTap]. */
    private class ReplayDevice(private var frame: Bitmap, private val claimButton: RectF?, private val afterTap: Bitmap?,
        private val afterReward: Bitmap? = null, private val afterDetails: Bitmap? = null) : InputDevicePort {
        /** Blank space left of the reward and details panels on the captures. */
        private val outside = RectF(360f, 480f, 480f, 600f)
        var token = Any()
        val gestures = mutableListOf<List<ScreenPoint>>()
        var backs = 0
        override suspend fun capture(expectedPackage: String): FrameResult =
            FrameResult.Ready(ScreenFrame(frame.copy(Bitmap.Config.ARGB_8888, false), FrameStamp(token, ScreenGeometry(frame.width, frame.height, 0), expectedPackage, System.nanoTime())))
        override suspend fun validateObservation(stamp: FrameStamp) = if (stamp.connection === token) null else DeviceRejection.SCREEN_CHANGED
        override suspend fun openInput(ticket: AttemptTicket, permitsInput: (AttemptTicket) -> Boolean): DeviceInputChannel = object : DeviceInputChannel {
            var last: GestureResult? = null
            override fun gestureCall(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long) = object : InputGestureCall {
                override suspend fun execute(): GestureResult {
                    gestures += points
                    val point = points.singleOrNull()
                    val next = when {
                        point == null -> null
                        frame === afterTap && outside.contains(point.x, point.y) -> afterReward
                        frame === afterReward && outside.contains(point.x, point.y) -> afterDetails
                        claimButton != null && claimButton.contains(point.x, point.y) && frame !== afterTap && frame !== afterReward -> afterTap
                        else -> null
                    }
                    if (next != null) { frame = next; token = Any() }
                    return GestureResult.Completed.also { last = it }
                }
                override suspend fun observeAfterRevoke(): GestureResult? = last
            }
            override fun backCall(stamp: FrameStamp) = object : InputGestureCall {
                override suspend fun execute(): GestureResult { backs++; return GestureResult.Completed.also { last = it } }
                override suspend fun observeAfterRevoke(): GestureResult? = last
            }
            override fun launchCall(packageName: String) = object : InputGestureCall {
                override suspend fun execute(): GestureResult = GestureResult.Completed.also { last = it }
                override suspend fun observeAfterRevoke(): GestureResult? = last
            }
            override suspend fun revoke(): InputDrain = last?.let { InputDrain.Terminal(it) } ?: InputDrain.NoInFlightGesture
        }
    }

    private fun frame(name: String): Bitmap = instrumentation.context.assets.open("region-experiment/$name").use { checkNotNull(BitmapFactory.decodeStream(it)) }
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val jade by lazy { frame("list-after.png").let { full -> Bitmap.createBitmap(full, 576, 449, 36, 43).also { full.recycle() } }.let(::png) }

    private fun bundle(models: List<TaskResource>) = dailyGiftBundle(models, jade, IdentityPolicy.OPTIONAL, "replay")

    private lateinit var factory: BuiltInStepWorkFactory
    private lateinit var content: TaskBundle
    private var device: ReplayDevice? = null
    private val journal = mutableListOf<ExecutorCommand>()

    @Before fun open(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Private replay frames and OCR models only", arguments.getString("includePrivateReplay") == "true" &&
            arguments.getString("allowOcrIntegration") == "true")
        name = "replay-${UUID.randomUUID()}.db"; folder = File(context.noBackupFilesDir, name)
        database = AssistDatabase.open(context, name)
        lateinit var observer: VersionedTextObserver
        val actions = ScreenActions({ observer }, { true }, { device }, journal = { command ->
            journal += command
            RuntimeCommandResult.Applied(ExecutorReply.DeviceActionRecorded(DeviceActionWriteResult.Recorded))
        })
        factory = BuiltInStepWorkFactory(actions)
        val codec = BundleCodec(factory.contracts)
        library = TaskVersionLibrary(database!!, codec, TaskResourceFiles(folder))
        observer = VersionedTextObserver(library, MaaTextBackend(context))
        val manifest = JSONObject(instrumentation.context.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        val models = (0 until manifest.length()).map { val pin = manifest.getJSONObject(it); val path = pin.getString("path")
            TaskResource(path, path, pin.getLong("bytes"), pin.getString("sha256")) }
        content = bundle(models)
        assertEquals(emptyList<ContentIssue>(), codec.validate(content))
        val text = codec.write(content)
        assertEquals(VersionChange.Applied, library.install(text, content.version, TaskVersionLibrary.sha256(text)) { resource, output ->
            if (resource.id == "jade-icon") output.write(jade) else instrumentation.context.assets.open("maa-model/${resource.id}").use { it.copyTo(output) }
        })
    }
    @After fun close() {
        database?.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        if (::folder.isInitialized) folder.deleteRecursively()
    }
    private fun attempt(): StepAttempt {
        val task = content.tasks.single()
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), ExecutionContext(task.targetId, Principal.Anonymous),
            content.version, listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, "{}")))
        return StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, request, 1))
    }
    private suspend fun run(start: String, afterTap: String? = null): StepOutcome {
        // The free button in list frames: card 0 button ROI measured on the captures.
        device = ReplayDevice(frame(start), RectF(488f, 445f, 728f, 500f), afterTap?.let(::frame),
            afterTap?.let { frame("details-claimed.png") }, afterTap?.let { frame("list-after.png") })
        return withTimeout(120_000) { factory.create(attempt(), content).execute() }
    }
    private fun templateUnsupported() = CpuFeatures.inconsistentSve()

    @Test fun freeButtonIsClaimedOnceAndTheRewardIsVerified(): Unit = runBlocking {
        val outcome = run("list-before.png", afterTap = "reward.png")
        if (templateUnsupported()) {
            // Without image evidence the state cannot be decided: no guess, no input.
            assertTrue(outcome.toString(), outcome is StepOutcome.Unknown && outcome.reason.contains("图像匹配"))
            assertTrue(device!!.gestures.isEmpty()); assertTrue(journal.isEmpty()); return@runBlocking
        }
        assertTrue(outcome.toString(), outcome is StepOutcome.Completed)
        assertEquals(3, device!!.gestures.size)
        assertEquals(listOf(ScreenActions.TAP, ScreenActions.TAP_REGION, ScreenActions.TAP_REGION),
            journal.filterIsInstance<ExecutorCommand.RecordDeviceAction>().map { it.intent.capability })
    }
    @Test fun theSameStateFromAnotherSessionIsAlsoClaimable(): Unit = runBlocking {
        val outcome = run("list-heldout.png", afterTap = "reward.png")
        if (templateUnsupported()) { assertTrue(device!!.gestures.isEmpty()); return@runBlocking }
        assertTrue(outcome.toString(), outcome is StepOutcome.Completed)
        assertEquals(3, device!!.gestures.size)
    }
    @Test fun aPaidOfferCompletesWithoutAnyInput(): Unit = runBlocking {
        val outcome = run("list-after.png")
        assertTrue(device!!.gestures.isEmpty()); assertTrue(journal.isEmpty())
        if (templateUnsupported()) { assertTrue(outcome is StepOutcome.Unknown); return@runBlocking }
        assertTrue(outcome.toString(), outcome is StepOutcome.Completed)
    }
    @Test fun aTapThatDoesNotProduceTheRewardStaysUnknown(): Unit = runBlocking {
        // No scripted transition: the frame after input is still the list, so the reward proof is absent.
        val outcome = run("list-before.png")
        assertTrue(outcome.toString(), outcome is StepOutcome.Unknown)
        if (!templateUnsupported()) assertEquals(1, device!!.gestures.size)
    }
    @Test fun anUnrelatedScreenIsNotGuessedAndNotTouched(): Unit = runBlocking {
        val outcome = run("details-claimed.png")
        assertTrue(outcome.toString(), outcome is StepOutcome.Unknown)
        assertTrue(device!!.gestures.isEmpty()); assertTrue(journal.isEmpty())
    }
}
