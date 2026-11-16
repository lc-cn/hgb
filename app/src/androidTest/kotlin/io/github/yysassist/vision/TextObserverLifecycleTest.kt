package io.github.yysassist.vision

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.device.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Real version library/SQLite/pipes. Backend completion is controlled, not claimed as real OCR. */
@RunWith(AndroidJUnit4::class)
class TextObserverLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var folder: File
    private lateinit var name: String
    private val codec = BundleCodec(BuiltInStepWorkFactory().contracts)
    private val references = OcrModelReferences("fixture.v1", mapOf("detector" to "det", "recognizer" to "rec", "dictionary" to "keys"))
    @Before fun open(): Unit = runBlocking {
        name = "text-life-${UUID.randomUUID()}.db"; folder = File(context.noBackupFilesDir, name)
        database = AssistDatabase.open(context, name); library = TaskVersionLibrary(database, codec, TaskResourceFiles(folder))
        install("old"); install("current"); library.activate("current")
    }
    @After fun close() { database.close(); context.deleteDatabase(name); assertTrue(folder.deleteRecursively()) }
    private suspend fun install(version: String) {
        val resource = listOf("det", "rec", "keys").map { TaskResource(it, "$it.bin", 1, TaskVersionLibrary.sha256("a")) }
        val action = ActionDefinition("wait", "control.wait", 1, buildJsonObject { put("durationMs", 0) })
        val task = TaskDefinition("wait", "utility", "夹具", "tools", 1, steps = listOf(StepDefinition("wait", "等待", listOf(action))))
        val target = TargetProfile("utility", "夹具", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val bundle = TaskBundle(1, version, "2026-11-16T00:00:00Z", "观察夹具", listOf(task), listOf(target), resource)
        val text = codec.write(bundle)
        assertEquals(VersionChange.Applied, library.install(text, version, TaskVersionLibrary.sha256(text)) { _, output -> output.write('a'.code) })
    }
    private fun frame(noise: Boolean = false): ScreenFrame {
        val bitmap = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        if (noise) bitmap.setPixels(IntArray(1000 * 480) { index -> 0xff000000.toInt() or ((index * 1103515245 + 12345) and 0xffffff) }, 0, 1000, 0, 0, 1000, 480)
        return ScreenFrame(bitmap, FrameStamp(Any(), ScreenGeometry(1000, 480, 0), context.packageName, 1))
    }
    private class ControlledBackend(private val consume: Boolean = true) : TextBackend {
        override val modelContract = "fixture.v1"
        val entered = CompletableFuture<Unit>(); val consumed = CompletableFuture<Unit>(); val stopped = CompletableFuture<Unit>()
        val value = CompletableFuture<BackendTextResult>(); val quietSignal = CompletableFuture<Unit>()
        var inputs: Map<String, ModelInput>? = null
        private var image: ParcelFileDescriptor? = null
        private var release: (() -> Unit)? = null
        private val worker = Executors.newSingleThreadExecutor()
        override fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): TextBackendCall {
            inputs = models; image = imageRead; release = onQuiet; entered.complete(Unit)
            if (consume) worker.execute {
                ParcelFileDescriptor.AutoCloseInputStream(imageRead).use { assertTrue(it.readBytes().isNotEmpty()) }
                consumed.complete(Unit)
            }
            return object : TextBackendCall {
                override val result = value; override val quiet = quietSignal
                override fun requestStop() { stopped.complete(Unit) }
            }
        }
        fun end() {
            image?.close(); release?.invoke(); quietSignal.complete(Unit); worker.shutdown()
        }
    }

    @Test fun observedResultCannotOutrunItsVersionLeaseAndCarriesOriginalFrameIdentity() = runBlocking {
        val backend = ControlledBackend(); val observer = VersionedTextObserver(library, backend); val frame = frame()
        val work = async(Dispatchers.IO) { observer.observe("old", references, frame) }
        try {
            withTimeout(5_000) { backend.consumed.awaitValue() }
            assertEquals("a", backend.inputs!!.getValue("detector").file.readText())
            assertEquals(TaskVersionLibrary.sha256("a"), backend.inputs!!.getValue("detector").sha256)
            backend.value.complete(BackendTextResult.Observed(listOf(RecognizedText("领取", 0.9, TextBounds(1, 1, 10, 10)))))
            withTimeout(5_000) { backend.stopped.awaitValue() }
            assertFalse(work.isCompleted)
            assertEquals(VersionRejection.IN_USE, (library.delete("old") as VersionChange.Rejected).code)
            backend.end()
            val value = withTimeout(5_000) { work.await() } as TextReadResult.Observed
            assertSame(frame.stamp, value.stamp); assertEquals("old", value.version)
            assertEquals(TaskVersionLibrary.sha256(codec.write(library.readVersion("old"))), value.bundleDigest)
            assertEquals(VersionChange.Applied, library.delete("old"))
        } finally { backend.end(); work.cancelAndJoin(); frame.close() }
    }

    @Test fun callerCancellationRetainsPixelsAndModelUntilIndependentQuietEvidence() = runBlocking {
        val backend = ControlledBackend(); val frame = frame()
        val work = launch(Dispatchers.IO) { frame.use { VersionedTextObserver(library, backend).observe("old", references, it) } }
        try {
            withTimeout(5_000) { backend.consumed.awaitValue() }
            work.cancel(); withTimeout(5_000) { backend.stopped.awaitValue() }
            assertFalse(work.isCompleted); assertFalse(frame.bitmap.isRecycled)
            assertEquals(VersionRejection.IN_USE, (library.delete("old") as VersionChange.Rejected).code)
            backend.end(); withTimeout(5_000) { work.join() }
            assertTrue(work.isCancelled); assertTrue(frame.bitmap.isRecycled)
            assertEquals(VersionChange.Applied, library.delete("old"))
        } finally { backend.end(); work.cancelAndJoin(); if (!frame.bitmap.isRecycled) frame.close() }
    }

    @Test fun stoppingAnUnconsumedImagePipeDrainsWriterBeforeCallerRecyclesPixels() = runBlocking {
        val backend = ControlledBackend(consume = false); val frame = frame(noise = true)
        val work = launch(Dispatchers.IO) { frame.use { VersionedTextObserver(library, backend).observe("old", references, it) } }
        try {
            withTimeout(5_000) { backend.entered.awaitValue() }
            work.cancel(); withTimeout(5_000) { backend.stopped.awaitValue() }
            assertFalse(work.isCompleted); assertFalse(frame.bitmap.isRecycled)
            backend.end(); withTimeout(5_000) { work.join() }
            assertTrue(frame.bitmap.isRecycled)
            assertEquals(0, library.snapshot().versions.single { it.version == "old" }.referenceCount)
        } finally { backend.end(); work.cancelAndJoin(); if (!frame.bitmap.isRecycled) frame.close() }
    }

    @Test fun unsupportedContractAndMissingReferenceNeverStartAConsumerOrLeakALease() = runBlocking {
        val backend = ControlledBackend(); val frame = frame(); val observer = VersionedTextObserver(library, backend)
        try {
            assertEquals(TextReadProblem.UNSUPPORTED_MODEL, (observer.observe("old", references.copy(contract = "other"), frame) as TextReadResult.Unavailable).problem)
            assertEquals(TextReadProblem.RESOURCE_UNAVAILABLE, (observer.observe("old", references.copy(resources = mapOf("detector" to "missing")), frame) as TextReadResult.Unavailable).problem)
            assertFalse(backend.entered.isDone)
            assertEquals(0, library.snapshot().versions.single { it.version == "old" }.referenceCount)
        } finally { backend.end(); frame.close() }
    }
}
