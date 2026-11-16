package io.github.yysassist.maaprobe

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Explicit fault experiment, never run with a game or used as an absence decision. */
@RunWith(AndroidJUnit4::class)
class MaaFailureContractTest {
    @Test fun corruptModelOutcomeIsObservedWithoutTreatingNoHitAsHealthy() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowCorruptMaaModels") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.filesDir.resolve("maa-probe/corrupt-${UUID.randomUUID()}"); folder.mkdirs()
        folder.resolve("det.onnx").writeBytes(byteArrayOf(1, 2, 3))
        folder.resolve("rec.onnx").writeBytes(byteArrayOf(1, 2, 3))
        folder.resolve("keys.txt").writeText("a\nb\n")
        val report = JSONObject().put("phase", "initializing").put("input", "Positive synthetic image, intentionally invalid ONNX files")
        val file = context.filesDir.resolve("maa-probe/failure-contract.json"); file.writeText(report.toString(2))
        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("领取物资", 20f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f })
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            try {
                // Deliberately bypass the probe's checksum gate to reproduce the upstream contract.
                val handle = MaaNative.create(folder.absolutePath)
                try {
                    report.put("phase", "recognizing"); file.writeText(report.toString(2))
                    val result = JSONObject(MaaNative.recognizeRaw(handle, bytes).toString(Charsets.UTF_8))
                    report.put("outcome", "returned").put("raw", result)
                    assertFalse("Broken models cannot establish a valid hit", result.getBoolean("hit"))
                    assertEquals(0, result.getJSONObject("detail").getJSONArray("all").length())
                    report.put("succeededWithEmptyNoHit", result.getInt("taskStatus") == 3000)
                } finally { MaaNative.destroy(handle) }
            } catch (error: IllegalStateException) {
                report.put("outcome", "explicit_failure").put("errorType", error.javaClass.name).put("error", error.message)
            }
            report.put("phase", "finished"); file.writeText(report.toString(2))
        } finally { bitmap.recycle(); folder.deleteRecursively() }
    }
}
