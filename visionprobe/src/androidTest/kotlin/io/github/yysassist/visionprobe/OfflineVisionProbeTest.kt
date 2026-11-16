package io.github.yysassist.visionprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Integration benchmark, not a game accuracy acceptance test. No executor, bridge or network permission. */
@RunWith(AndroidJUnit4::class)
class OfflineVisionProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Reading(val text: String, val points: List<PointF>, val confidence: Float? = null)
    private data class Label(val text: String, val bounds: RectF)
    private data class Sample(val name: String, val image: Bitmap, val labels: List<Label>, val privateImage: Boolean = false)
    private interface Engine {
        suspend fun read(bitmap: Bitmap): List<Reading>
        suspend fun close()
    }
    private fun synthetic(name: String, dark: Boolean, blank: Boolean = false): Sample {
        val image = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(if (dark) Color.rgb(28, 31, 37) else Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(236, 233, 222) else Color.BLACK; textSize = 48f }
        val labels = if (blank) emptyList() else listOf("领取物资", "永久勾玉卡", "次数 3", "100/120").mapIndexed { index, text ->
            val baseline = 95f + index * 95f
            canvas.drawText(text, 45f, baseline, paint)
            val bounds = Rect(); paint.getTextBounds(text, 0, text.length, bounds)
            Label(text, RectF(bounds.left + 45f, bounds.top + baseline, bounds.right + 45f, bounds.bottom + baseline))
        }
        return Sample(name, image, labels)
    }
    private fun normalized(text: String) = text.filterNot(Char::isWhitespace)
    private fun rect(points: List<PointF>) = RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF(a)
        if (!intersection.intersect(b)) return 0f
        val area = intersection.width() * intersection.height()
        return area / (a.width() * a.height() + b.width() * b.height() - area)
    }
    private fun readingJson(reading: Reading) = JSONObject().apply {
        put("text", reading.text)
        put("points", JSONArray(reading.points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
        reading.confidence?.let { put("confidence", it.toDouble()) }
    }
    @Test fun bothBundledEnginesLoadAndRecognizeLocallyWithoutNetworkPermission(): Unit = runBlocking {
        for (app in listOf(context.packageName, instrumentation.context.packageName)) {
            assertEquals("Probe must not have network capability: $app", PackageManager.PERMISSION_DENIED,
                context.packageManager.checkPermission(Manifest.permission.INTERNET, app))
        }
        val folder = context.filesDir.resolve("vision-probe").also { it.mkdirs() }
        val privateDetails = JSONArray()
        val debugPrivate = InstrumentationRegistry.getArguments().getString("privateVisionDetails") == "true"
        folder.resolve("private-details.json").delete()
        val samples = mutableListOf(synthetic("light", false), synthetic("dark", true), synthetic("blank", false, true))
        if (InstrumentationRegistry.getArguments().getString("includePrivateVisionSamples") == "true") {
            val assets = instrumentation.context.assets
            val list = JSONArray(assets.open("private-samples/labels.json").bufferedReader().use { it.readText() })
            repeat(list.length()) { index ->
                val entry = list.getJSONObject(index); val file = entry.getString("file")
                require(file.matches(Regex("[a-z0-9-]+\\.png")))
                val bitmap = assets.open("private-samples/$file").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                val labels = entry.getJSONArray("labels")
                samples += Sample(entry.getString("name"), bitmap, (0 until labels.length()).map { i ->
                    val label = labels.getJSONObject(i); val b = label.getJSONArray("bounds")
                    Label(label.getString("text"), RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()))
                }, privateImage = true)
            }
        }
        samples.filterNot { it.privateImage }.forEach { sample -> folder.resolve("${sample.name}.png").outputStream().use { sample.image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val report = JSONObject().apply {
            put("runId", UUID.randomUUID().toString()); put("startedAtEpochMs", System.currentTimeMillis())
            put("sdk", Build.VERSION.SDK_INT); put("abi", Build.SUPPORTED_ABIS.first()); put("hardware", Build.HARDWARE)
            put("internetPermission", false)
            put("models", JSONObject(context.assets.open("models-manifest.json").bufferedReader().use { it.readText() }))
            put("scope", "Synthetic rendered labels and optional explicitly opted-in private local crops. Crops do not prove game eligibility, business completion or held-out-theme support. Exact text/IoU are observations, not a production acceptance threshold. Create timing excludes process/provider startup; memory is a process point measurement, not an isolated engine peak.")
        }
        val engines = JSONArray(); report.put("engines", engines)
        folder.resolve("results.json").writeText(report.toString(2))
        val failures = mutableListOf<String>()
        val factories: List<Pair<String, suspend () -> Engine>> = listOf(
            "paddle-v6-tiny" to {
                check(OpenCVUtils.init(context)) { "OpenCV native load failed" }
                val paddle = PaddleOCR.create(context)
                object : Engine {
                    override suspend fun read(bitmap: Bitmap) = paddle.recognize(bitmap).results.map { Reading(it.text, it.box.points, it.confidence) }
                    override suspend fun close() { paddle.release() }
                }
            },
            "mlkit-chinese-bundled-16.0.1" to {
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                object : Engine {
                    override suspend fun read(bitmap: Bitmap): List<Reading> {
                        // Wait for the actual task. A timeout/cancelled await must not release its still-in-use Bitmap.
                        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        return text.textBlocks.flatMap { it.lines }.map { line ->
                            Reading(line.text, checkNotNull(line.cornerPoints).map { PointF(it.x.toFloat(), it.y.toFloat()) })
                        }
                    }
                    override suspend fun close() { recognizer.close() }
                }
            }
        )
        try {
            for ((name, create) in factories) {
                val result = JSONObject().put("name", name).put("phase", "creating"); engines.put(result)
                folder.resolve("results.json").writeText(report.toString(2))
                var engine: Engine? = null
                try {
                    val started = SystemClock.elapsedRealtimeNanos(); engine = create()
                    result.put("phase", "recognizing")
                    result.put("createMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                    val runs = JSONArray(); result.put("runs", runs)
                    folder.resolve("results.json").writeText(report.toString(2))
                    for (sample in samples) repeat(3) { repetition ->
                        val start = SystemClock.elapsedRealtimeNanos(); val readings = engine.read(sample.image)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                        if (sample.privateImage && debugPrivate) {
                            privateDetails.put(JSONObject().put("engine", name).put("sample", sample.name).put("repetition", repetition)
                                .put("readings", JSONArray(readings.map(::readingJson))))
                            // Explicit local diagnostics only; never included in the shareable results file.
                            folder.resolve("private-details.json").writeText(privateDetails.toString(2))
                        }
                        assertTrue("Invalid output geometry from $name", readings.all { reading -> reading.points.size == 4 && reading.points.all {
                            it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 && it.x <= sample.image.width && it.y <= sample.image.height } })
                        if (sample.labels.isNotEmpty()) assertTrue("Positive sample yielded no text: $name", readings.isNotEmpty())
                        else assertTrue("Blank image produced text: $name", readings.isEmpty())
                        val metrics = sample.labels.map { label ->
                            val exact = readings.filter { normalized(it.text) == normalized(label.text) }
                            JSONObject().put("expected", label.text).put("exact", exact.isNotEmpty())
                                .put("bestIoU", exact.maxOfOrNull { iou(rect(it.points), label.bounds) }?.toDouble() ?: 0.0)
                        }
                        val run = JSONObject().put("sample", sample.name).put("privateImage", sample.privateImage)
                            .put("repetition", repetition).put("elapsedMs", elapsed).put("labels", JSONArray(metrics))
                        if (!sample.privateImage) run.put("readings", JSONArray(readings.map(::readingJson)))
                        else run.put("readingCount", readings.size)
                        runs.put(run)
                    }
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    result.put("processPssAfterKiB", memory.totalPss); result.put("nativeAllocatedAfterBytes", Debug.getNativeHeapAllocatedSize())
                    result.put("success", true)
                } catch (error: Exception) {
                    result.put("success", false).put("errorType", error.javaClass.name).put("error", error.message)
                    failures += "$name: ${error.javaClass.simpleName}"
                } catch (error: LinkageError) {
                    result.put("success", false).put("errorType", error.javaClass.name).put("error", error.message)
                    failures += "$name: ${error.javaClass.simpleName}"
                } catch (error: AssertionError) {
                    result.put("success", false).put("errorType", error.javaClass.name).put("error", error.message)
                    failures += "$name: ${error.javaClass.simpleName}"
                } finally {
                    try { engine?.close() }
                    catch (error: Exception) {
                        result.put("success", false).put("closeErrorType", error.javaClass.name)
                        failures += "$name: close failed"
                    }
                    result.put("phase", "finished")
                    folder.resolve("results.json").writeText(report.toString(2))
                }
            }
        } finally { samples.forEach { it.image.recycle() } }
        assertTrue("Candidate integration errors: $failures", failures.isEmpty())
    }
}
