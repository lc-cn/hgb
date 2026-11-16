package io.github.yysassist.maaprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Same sample pixels/labels as visionprobe; engine and converted weights differ. */
@RunWith(AndroidJUnit4::class)
class MaaComparisonTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Label(val text: String, val bounds: RectF)
    private data class Sample(val name: String, val bitmap: Bitmap, val labels: List<Label>, val privateImage: Boolean = false)
    private data class Reading(val text: String, val bounds: RectF, val score: Double)
    private fun synthetic(name: String, dark: Boolean, blank: Boolean = false): Sample {
        val image = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(if (dark) Color.rgb(28, 31, 37) else Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(236, 233, 222) else Color.BLACK; textSize = 48f }
        val labels = if (blank) emptyList() else listOf("领取物资", "永久勾玉卡", "次数 3", "100/120").mapIndexed { index, text ->
            val baseline = 95f + index * 95f; canvas.drawText(text, 45f, baseline, paint)
            val bounds = Rect(); paint.getTextBounds(text, 0, text.length, bounds)
            Label(text, RectF(bounds.left + 45f, bounds.top + baseline, bounds.right + 45f, bounds.bottom + baseline))
        }
        return Sample(name, image, labels)
    }
    private fun normalized(text: String) = text.filterNot(Char::isWhitespace)
    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF(a); if (!intersection.intersect(b)) return 0f
        val area = intersection.width() * intersection.height()
        return area / (a.width() * a.height() + b.width() * b.height() - area)
    }
    private fun metrics(labels: List<Label>, readings: List<Reading>): JSONArray {
        data class Match(val label: Int, val reading: Int, val overlap: Float)
        val pairs = labels.flatMapIndexed { i, label -> readings.mapIndexedNotNull { j, r ->
            val overlap = iou(r.bounds, label.bounds)
            if (normalized(r.text) == normalized(label.text) && overlap > 0) Match(i, j, overlap) else null
        } }.sortedWith(compareByDescending<Match> { it.overlap }.thenBy { it.label }.thenBy { it.reading })
        val located = mutableMapOf<Int, Float>(); val used = mutableSetOf<Int>()
        pairs.forEach { if (it.label !in located && used.add(it.reading)) located[it.label] = it.overlap }
        return JSONArray(labels.mapIndexed { i, label ->
            val exact = readings.filter { normalized(it.text) == normalized(label.text) }
            JSONObject().put("expected", label.text).put("exact", exact.isNotEmpty())
                .put("bestIoU", exact.maxOfOrNull { iou(it.bounds, label.bounds) }?.toDouble() ?: 0.0)
                .put("locatedExact", i in located).put("locatedIoU", located[i]?.toDouble() ?: 0.0)
        })
    }
    @Test fun convertedSmallModelUsesTheSameOfflineSampleProtocol() {
        for (app in listOf(context.packageName, instrumentation.context.packageName))
            assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission(Manifest.permission.INTERNET, app))
        val folder = context.filesDir.resolve("maa-probe").also { it.mkdirs() }
        folder.resolve("private-details.json").delete()
        val samples = mutableListOf(synthetic("light", false), synthetic("dark", true), synthetic("blank", false, true))
        val models = context.assets.open("model-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val modelFolder = folder.resolve("comparison-model-${UUID.randomUUID()}").also { it.mkdirs() }
        val report = JSONObject().put("runId", UUID.randomUUID().toString()).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("internetPermission", false).put("models", models)
            .put("engine", "MaaFramework-5.16.1-ONNX-CPU-converted-v6-small").put("phase", "preparing")
            .put("scope", "Same synthetic/private region protocol; converted weights and native preprocessing differ. Transport success/no-hit does not establish inference health or game business outcomes.")
        val output = folder.resolve("comparison.json"); output.writeText(report.toString(2))
        try {
            val files = models.getJSONArray("files")
            repeat(files.length()) { index ->
                val pin = files.getJSONObject(index); val name = pin.getString("path")
                require(name in setOf("det.onnx", "rec.onnx", "keys.txt"))
                val bytes = context.assets.open("maa-model/$name").use { it.readBytes() }
                assertEquals(pin.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                modelFolder.resolve(name).writeBytes(bytes)
            }
            if (InstrumentationRegistry.getArguments().getString("includePrivateVisionSamples") == "true") {
                val assets = instrumentation.context.assets
                val list = assets.open("private-samples/labels.json").bufferedReader().use { JSONArray(it.readText()) }
                repeat(list.length()) { index ->
                    val entry = list.getJSONObject(index); val name = entry.getString("file")
                    require(name.matches(Regex("[a-z0-9-]+\\.png")))
                    val original = assets.open("private-samples/$name").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                    val region = entry.optJSONArray("region")
                    val bitmap = if (region == null) original else try {
                        require(region.length() == 4)
                        val l = region.getInt(0); val t = region.getInt(1); val r = region.getInt(2); val b = region.getInt(3)
                        require(l >= 0 && t >= 0 && r > l && b > t && r <= original.width && b <= original.height)
                        Bitmap.createBitmap(original, l, t, r - l, b - t)
                    } catch (error: Exception) { original.recycle(); throw error }
                    if (bitmap !== original) original.recycle()
                    val labels = entry.getJSONArray("labels")
                    samples += Sample(entry.getString("name"), bitmap, (0 until labels.length()).map { i ->
                        val label = labels.getJSONObject(i); val box = label.getJSONArray("bounds")
                        Label(label.getString("text"), RectF(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(), box.getDouble(2).toFloat(), box.getDouble(3).toFloat()))
                    }, true)
                }
            }
            val created = SystemClock.elapsedRealtimeNanos()
            MaaOcr(modelFolder.absolutePath).use { engine ->
                report.put("resourceRegistrationMs", (SystemClock.elapsedRealtimeNanos() - created) / 1e6).put("phase", "recognizing")
                val runs = JSONArray(); val privateDetails = JSONArray(); report.put("runs", runs); output.writeText(report.toString(2))
                for (sample in samples) {
                    val png = ByteArrayOutputStream().also { sample.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    if (!sample.privateImage) folder.resolve("${sample.name}.png").writeBytes(png)
                    repeat(3) { repetition ->
                        val start = SystemClock.elapsedRealtimeNanos(); val raw = JSONObject(engine.recognizeRaw(png)); val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                        assertEquals(3000, raw.getInt("taskStatus")); assertFalse(raw.getBoolean("controllerBound"))
                        val all = raw.getJSONObject("detail").getJSONArray("all")
                        val readings = (0 until all.length()).map { i ->
                            val item = all.getJSONObject(i); val box = item.getJSONArray("box")
                            val x = box.getDouble(0).toFloat(); val y = box.getDouble(1).toFloat()
                            Reading(item.getString("text"), RectF(x, y, x + box.getDouble(2).toFloat(), y + box.getDouble(3).toFloat()), item.getDouble("score"))
                        }
                        assertTrue(readings.all { it.bounds.let { b -> b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite() && b.left >= 0 && b.top >= 0 && b.right <= sample.bitmap.width && b.bottom <= sample.bitmap.height } })
                        if (sample.labels.isEmpty()) assertTrue(readings.isEmpty()) else assertTrue(readings.isNotEmpty())
                        val result = JSONObject().put("sample", sample.name).put("privateImage", sample.privateImage).put("repetition", repetition)
                            .put("elapsedMs", elapsed).put("labels", metrics(sample.labels, readings)).put("readingCount", readings.size)
                        if (!sample.privateImage) result.put("native", raw)
                        else if (InstrumentationRegistry.getArguments().getString("privateVisionDetails") == "true") {
                            privateDetails.put(JSONObject().put("sample", sample.name).put("repetition", repetition).put("native", raw))
                            folder.resolve("private-details.json").writeText(privateDetails.toString(2))
                        }
                        runs.put(result); output.writeText(report.toString(2))
                    }
                }
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                report.put("processPssAfterKiB", memory.totalPss).put("success", true)
            }
            report.put("phase", "finished"); output.writeText(report.toString(2))
        } catch (error: Throwable) {
            report.put("success", false).put("errorType", error.javaClass.name).put("phase", "failed"); output.writeText(report.toString(2)); throw error
        } finally { samples.forEach { it.bitmap.recycle() }; modelFolder.deleteRecursively() }
    }
}
