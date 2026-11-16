package io.github.yysassist.vision

import android.graphics.*
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.device.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual App observer + real task-version blobs + shared native subprocess. Synthetic frames only. */
@RunWith(AndroidJUnit4::class)
class VersionedMaaIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var folder: File
    private var database: AssistDatabase? = null
    private lateinit var library: TaskVersionLibrary
    private val codec = BundleCodec(BuiltInStepWorkFactory().contracts)
    private val models = OcrModelReferences(MaaTextBackend.CONTRACT,
        mapOf("detector" to "det.onnx", "recognizer" to "rec.onnx", "dictionary" to "keys.txt"))
    @Before fun open(): Unit = runBlocking {
        Assume.assumeTrue("Explicit isolated emulator OCR integration only", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("allowOcrIntegration") == "true")
        name = "versioned-ocr-${UUID.randomUUID()}.db"; folder = File(context.noBackupFilesDir, name)
        database = AssistDatabase.open(context, name); library = TaskVersionLibrary(database!!, codec, TaskResourceFiles(folder))
        install("valid")
    }
    @After fun close() {
        database?.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        if (::folder.isInitialized) assertTrue(folder.deleteRecursively())
    }
    private suspend fun install(version: String, corrupt: Boolean = false) {
        val manifest = JSONObject(instrumentation.context.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        val resources = (0 until manifest.length()).map { index ->
            val value = manifest.getJSONObject(index); val path = value.getString("path")
            if (!corrupt) TaskResource(path, path, value.getLong("bytes"), value.getString("sha256"))
            else {
                val bytes = if (path == "keys.txt") "a\nb\n".toByteArray() else byteArrayOf(1, 2, 3)
                TaskResource(path, path, bytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }
        val task = TaskDefinition("fixture", "utility", "只读夹具", "tools", 1, steps = listOf(
            StepDefinition("wait", "等待", listOf(ActionDefinition("wait", "control.wait", 1, buildJsonObject { put("durationMs", 0) })))))
        val target = TargetProfile("utility", "自有像素", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val text = codec.write(TaskBundle(1, version, "2026-11-16T00:00:00Z", "集成夹具", listOf(task), listOf(target), resources))
        assertEquals(VersionChange.Applied, library.install(text, version, TaskVersionLibrary.sha256(text)) { resource, output ->
            if (!corrupt) instrumentation.context.assets.open("maa-model/${resource.id}").use { it.copyTo(output) }
            else output.write(if (resource.id == "keys.txt") "a\nb\n".toByteArray() else byteArrayOf(1, 2, 3))
        })
    }
    private fun frame(text: Boolean): ScreenFrame {
        val bitmap = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            if (text) drawText("领取物资", 45f, 95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f })
        }
        return ScreenFrame(bitmap, FrameStamp(Any(), ScreenGeometry(1000, 480, 0), context.packageName, 42))
    }
    @Test fun versionResourcesReachRealOcrWithFrameIdentityAndHealthyBlankSeparate(): Unit = runBlocking {
        val observer = VersionedTextObserver(library, MaaTextBackend(context))
        for (positive in listOf(true, false)) frame(positive).use { frame ->
            val result = withTimeout(30_000) { observer.observe("valid", models, frame) }
            assertTrue(result.toString(), result is TextReadResult.Observed)
            val value = result as TextReadResult.Observed
            assertSame(frame.stamp, value.stamp); assertEquals("valid", value.version)
            if (positive) {
                val target = value.candidates.single { it.text == "领取物资" }
                assertTrue(target.bounds.left < 220 && target.bounds.left + target.bounds.width > 45)
                assertTrue(target.bounds.top < 95 && target.bounds.top + target.bounds.height > 45)
            } else assertTrue(value.candidates.isEmpty())
            assertEquals(0, library.snapshot().versions.single().referenceCount)
        }
        assertEquals(VersionChange.Applied, library.delete("valid"))
    }
    @Test fun checksumValidBadOnnxCannotBecomeAnEmptyObservationOrLeakVersionUse(): Unit = runBlocking {
        Assume.assumeTrue("Explicit native child fault only", InstrumentationRegistry.getArguments().getString("allowChildNativeFaults") == "true")
        install("bad", corrupt = true)
        val observer = VersionedTextObserver(library, MaaTextBackend(context))
        frame(true).use { frame ->
            val value = withTimeout(30_000) { observer.observe("bad", models, frame) }
            assertTrue(value is TextReadResult.Unavailable)
            assertEquals(TextReadProblem.ENGINE_UNAVAILABLE, (value as TextReadResult.Unavailable).problem)
            assertTrue(value.detail!!.contains("PROCESS_DIED"))
        }
        assertEquals(0, library.snapshot().versions.single { it.version == "bad" }.referenceCount)
        assertEquals(VersionChange.Applied, library.delete("bad"))
        frame(true).use { assertTrue(withTimeout(30_000) { observer.observe("valid", models, it) } is TextReadResult.Observed) }
    }
}
