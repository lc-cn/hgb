package io.github.yysassist.maaprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Offline experiment: does ROI association plus non-text evidence (template / colour) separate the
 * observed daily-gift states that text-only OCR confused? Reports raw scores; asserts only transport
 * and geometry, never a business threshold. Private frames are gated and never leave the device report.
 */
@RunWith(AndroidJUnit4::class)
class MaaRegionStateExperimentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun ints(array: JSONArray) = IntArray(array.length()) { array.getInt(it) }
    private fun iou(a: IntArray, b: IntArray): Double {
        val l = maxOf(a[0], b[0]); val t = maxOf(a[1], b[1]); val r = minOf(a[0] + a[2], b[0] + b[2]); val bt = minOf(a[1] + a[3], b[1] + b[3])
        if (r <= l || bt <= t) return 0.0
        val i = (r - l).toDouble() * (bt - t); return i / (a[2].toDouble() * a[3] + b[2].toDouble() * b[3] - i)
    }
    private fun normalized(text: String) = text.filterNot(Char::isWhitespace)

    private class Reading(val text: String, val box: IntArray, val score: Double)
    private fun readings(detail: Any?, key: String = "all"): List<JSONObject> {
        val all = (detail as? JSONObject)?.optJSONArray(key) ?: return emptyList()
        return (0 until all.length()).map { all.getJSONObject(it) }
    }

    @Test fun regionAndGraphicStateEvidence() {
        assumeTrue("Private region experiment not requested", arguments.getString("includePrivateRegionExperiment") == "true")
        for (app in listOf(context.packageName, instrumentation.context.packageName))
            assertEquals(PackageManager.PERMISSION_DENIED, context.packageManager.checkPermission(Manifest.permission.INTERNET, app))
        val folder = context.filesDir.resolve("maa-probe").also { it.mkdirs() }
        val privateOut = folder.resolve("region-private-details.json").also { it.delete() }
        val output = folder.resolve("${arguments.getString("regionSpec") ?: "region-experiment"}.json")
        val assets = instrumentation.context.assets
        // Spec folder is selectable (e.g. -e regionSpec navigation); frames and spec live together.
        val folderName = arguments.getString("regionSpec") ?: "region-experiment"
        require(folderName.matches(Regex("[a-z-]+")))
        val spec = assets.open("$folderName/spec.json").bufferedReader().use { JSONObject(it.readText()) }
        val models = context.assets.open("model-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val modelFolder = folder.resolve("region-model-${UUID.randomUUID()}").also { it.mkdirs() }
        val report = JSONObject().put("runId", UUID.randomUUID().toString()).put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL).put("abi", Build.SUPPORTED_ABIS.first()).put("internetPermission", false)
            .put("engine", "MaaFramework-5.16.1-ONNX-CPU-converted-v6-small").put("phase", "preparing")
            .put("scope", "Five private frames from one device/theme; templates cropped from two of them. Scores are measurements, not thresholds or business results.")
        output.writeText(report.toString(2))
        val details = JSONArray()
        var handle = 0L
        try {
            val files = models.getJSONArray("files")
            repeat(files.length()) { index ->
                val pin = files.getJSONObject(index); val name = pin.getString("path")
                require(name in setOf("det.onnx", "rec.onnx", "keys.txt"))
                val bytes = context.assets.open("maa-model/$name").use { it.readBytes() }
                assertEquals(pin.getString("sha256"), sha(bytes))
                modelFolder.resolve(name).writeBytes(bytes)
            }
            val frames = mutableMapOf<String, ByteArray>(); val sizes = mutableMapOf<String, Pair<Int, Int>>()
            val frameSpec = spec.getJSONObject("frames")
            frameSpec.keys().forEach { name ->
                require(name.matches(Regex("[a-z0-9-]+\\.png")))
                val bytes = assets.open("$folderName/$name").use { it.readBytes() }
                assertEquals(frameSpec.getJSONObject(name).getString("sha256"), sha(bytes))
                frames[name] = bytes
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { sizes[name] = it.width to it.height; it.recycle() }
            }
            handle = MaaRegionNative.create(modelFolder.absolutePath).also { check(it != 0L) }
            val templates = spec.getJSONObject("templates"); val templateReport = JSONObject()
            templates.keys().forEach { name ->
                val t = templates.getJSONObject(name); val rect = ints(t.getJSONArray("rect"))
                val source = frames.getValue(t.getString("frame"))
                val full = BitmapFactory.decodeByteArray(source, 0, source.size)
                val crop = Bitmap.createBitmap(full, rect[0], rect[1], rect[2], rect[3]); full.recycle()
                val bytes = png(crop); crop.recycle()
                MaaRegionNative.overrideImage(handle, name, bytes)
                templateReport.put(name, JSONObject().put("frame", t.getString("frame")).put("rect", t.getJSONArray("rect")).put("sha256", sha(bytes)))
            }
            report.put("templates", templateReport).put("phase", "recognizing")

            fun run(type: String, param: JSONObject, frame: String): JSONObject {
                val raw = JSONObject(MaaRegionNative.recognize(handle, type, param.toString(), frames.getValue(frame)).toString(Charsets.UTF_8))
                assertEquals(3000, raw.getInt("taskStatus"))
                assertEquals(type, raw.getString("algorithm"))
                return raw
            }
            fun param(case: JSONObject, roi: IntArray): Pair<String, JSONObject> {
                val p = JSONObject().put("roi", JSONArray(roi.toList()))
                return when (case.getString("type")) {
                    "OCR" -> "OCR" to p
                    "TemplateMatch" -> "TemplateMatch" to p.put("template", JSONArray(listOf(case.getString("template"))))
                        .put("threshold", 0.01).put("method", 5)
                    "ColorMatch" -> "ColorMatch" to case.getJSONObject("color").let { c ->
                        p.put("method", c.getInt("method")).put("lower", c.getJSONArray("lower")).put("upper", c.getJSONArray("upper"))
                            .put("count", c.getInt("count")).put("connected", c.getBoolean("connected")) }
                    else -> error("Unknown case type")
                }
            }
            /** Measurement for one call: best template score, max colour count, or OCR texts inside ROI. */
            fun measure(type: String, raw: JSONObject, roi: IntArray, expectedText: String?): JSONObject {
                val all = readings(raw.opt("detail"))
                all.forEach { item -> val b = ints(item.getJSONArray("box"))
                    assertTrue("Result outside ROI", b[0] >= roi[0] - 1 && b[1] >= roi[1] - 1 && b[0] + b[2] <= roi[0] + roi[2] + 1 && b[1] + b[3] <= roi[1] + roi[3] + 1) }
                val m = JSONObject().put("hit", raw.getBoolean("hit")).put("candidates", all.size)
                when (type) {
                    "TemplateMatch" -> m.put("bestScore", all.maxOfOrNull { it.getDouble("score") } ?: 0.0)
                    "ColorMatch" -> m.put("maxCount", all.maxOfOrNull { it.getInt("count") } ?: 0)
                    "OCR" -> {
                        val texts = all.map { normalized(it.getString("text")) }
                        m.put("textCount", texts.size)
                        if (expectedText != null) m.put("expectedExact", texts.contains(expectedText)).put("expectedJoined", texts.joinToString("") == expectedText)
                        m.put("anyText", texts.any { it.isNotEmpty() })
                    }
                }
                return m
            }

            val caseResults = JSONArray(); report.put("cases", caseResults)
            val cases = spec.getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index); val frame = case.getString("frame"); val roi = ints(case.getJSONArray("roi"))
                val (w, h) = sizes.getValue(frame); assertTrue(roi[0] >= 0 && roi[1] >= 0 && roi[0] + roi[2] <= w && roi[1] + roi[3] <= h)
                val (type, p) = param(case, roi)
                val reps = JSONArray()
                repeat(3) { repetition ->
                    val raw = run(type, p, frame)
                    reps.put(measure(type, raw, roi, case.optString("expectedText").takeIf { case.has("expectedText") && !case.isNull("expectedText") }))
                    details.put(JSONObject().put("case", case.getString("id")).put("repetition", repetition).put("raw", raw))
                }
                val identical = (1 until reps.length()).all { reps.getJSONObject(it).toString() == reps.getJSONObject(0).toString() }
                caseResults.put(JSONObject().put("id", case.getString("id")).put("type", type).put("expected", case.opt("expected") ?: JSONObject.NULL)
                    .put("circular", case.optBoolean("circular", false)).put("repetitionsIdentical", identical).put("result", reps.getJSONObject(0)))
                output.writeText(report.toString(2))
            }

            val anchorResults = JSONArray(); report.put("anchors", anchorResults)
            val anchors = spec.getJSONArray("anchors")
            for (index in 0 until anchors.length()) {
                val anchor = anchors.getJSONObject(index); val frame = anchor.getString("frame"); val (w, h) = sizes.getValue(frame)
                val full = intArrayOf(0, 0, w, h)
                val raw = run("OCR", JSONObject(), frame)
                details.put(JSONObject().put("anchor", frame).put("raw", raw))
                val expected = ints(anchor.getJSONArray("expectedBox")).let { intArrayOf(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
                val matches = readings(raw.opt("detail")).filter { normalized(it.getString("text")) == anchor.getString("text") }
                val entry = JSONObject().put("frame", frame).put("text", anchor.getString("text")).put("exactCandidates", matches.size)
                // Single, unambiguous anchor only; multiple or zero candidates are reported, never guessed.
                val chosen = matches.singleOrNull()
                if (chosen != null) {
                    val box = ints(chosen.getJSONArray("box"))
                    entry.put("anchorIoU", iou(box, expected))
                    val derivedResults = JSONArray(); entry.put("derived", derivedResults)
                    val derived = anchor.getJSONArray("derived")
                    for (d in 0 until derived.length()) {
                        val spec2 = derived.getJSONObject(d)
                        val cx = box[0] + box[2] / 2; val bottom = box[1] + box[3]
                        val roi = intArrayOf(cx + spec2.getInt("dx"), bottom + spec2.getInt("dy"), spec2.getInt("w"), spec2.getInt("h"))
                        val inside = roi[0] >= 0 && roi[1] >= 0 && roi[0] + roi[2] <= full[2] && roi[1] + roi[3] <= full[3]
                        val dr = JSONObject().put("name", spec2.getString("name")).put("insideFrame", inside)
                            .put("handRoiIoU", iou(roi, ints(spec2.getJSONArray("handRoi"))))
                        if (inside) {
                            val checks = spec2.getJSONArray("checks"); val out = JSONObject()
                            for (c in 0 until checks.length()) {
                                val name = checks.getString(c)
                                val case = when (name) {
                                    "jade" -> JSONObject().put("type", "TemplateMatch").put("template", "jade.png")
                                    "stamp" -> JSONObject().put("type", "TemplateMatch").put("template", "stamp.png")
                                    "red" -> JSONObject().put("type", "ColorMatch").put("color", spec.getJSONObject("red"))
                                    else -> JSONObject().put("type", "OCR")
                                }
                                val (type, p) = param(case, roi)
                                val r = run(type, p, frame)
                                details.put(JSONObject().put("anchor", frame).put("derived", spec2.getString("name")).put("check", name).put("raw", r))
                                out.put(name, measure(type, r, roi, null))
                            }
                            dr.put("checks", out)
                        }
                        derivedResults.put(dr)
                    }
                }
                anchorResults.put(entry); output.writeText(report.toString(2))
            }

            // Separability per evidence kind over non-circular boolean cases. No threshold is chosen here.
            val summary = JSONObject()
            fun gap(filter: (JSONObject) -> Boolean, key: String) = JSONObject().also { s ->
                val rows = (0 until caseResults.length()).map { caseResults.getJSONObject(it) }
                    .filter { filter(it) && !it.getBoolean("circular") && it.opt("expected") is Boolean }
                val pos = rows.filter { it.getBoolean("expected") }.map { it.getJSONObject("result").getDouble(key) }
                val neg = rows.filterNot { it.getBoolean("expected") }.map { it.getJSONObject("result").getDouble(key) }
                s.put("positives", pos.size).put("negatives", neg.size)
                    .put("minPositive", pos.minOrNull() ?: JSONObject.NULL).put("maxNegative", neg.maxOrNull() ?: JSONObject.NULL)
                    .put("separable", pos.isNotEmpty() && neg.isNotEmpty() && pos.min() > neg.max())
            }
            summary.put("jadeTemplate", gap({ it.getString("id").endsWith(":jade") }, "bestScore"))
            summary.put("stampTemplate", gap({ it.getString("id").endsWith("stamp") }, "bestScore"))
            summary.put("redColor", gap({ it.getString("id").endsWith("red") }, "maxCount"))
            summary.put("allRepetitionsIdentical", (0 until caseResults.length()).all { caseResults.getJSONObject(it).getBoolean("repetitionsIdentical") })
            report.put("summary", summary).put("success", true).put("phase", "finished")
            output.writeText(report.toString(2))
            if (arguments.getString("privateVisionDetails") == "true") privateOut.writeText(details.toString(2))
        } catch (error: Throwable) {
            report.put("success", false).put("errorType", error.javaClass.name).put("errorMessage", error.message).put("phase", "failed")
            output.writeText(report.toString(2)); throw error
        } finally {
            if (handle != 0L) MaaRegionNative.destroy(handle)
            modelFolder.deleteRecursively()
        }
    }
}
