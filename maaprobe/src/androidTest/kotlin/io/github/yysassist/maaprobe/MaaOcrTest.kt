package io.github.yysassist.maaprobe

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaaOcrTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun models(): java.io.File {
        val folder = context.filesDir.resolve("maa-probe/model-${UUID.randomUUID()}"); folder.mkdirs()
        for (name in listOf("det.onnx", "rec.onnx", "keys.txt")) context.assets.open("maa-model/$name").use { input -> folder.resolve(name).outputStream().use(input::copyTo) }
        return folder
    }
    private fun image(text: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            if (text) canvas.drawText("领取物资", 45f, 95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f })
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    @Test fun recognitionHasNoControllerAndBlankIsSeparateFromTransportFailure() {
        val folder = models()
        try {
            MaaOcr(folder.absolutePath).use { engine ->
                val found = JSONObject(engine.recognizeRaw(image(true)))
                val blank = JSONObject(engine.recognizeRaw(image(false)))
                val file = context.filesDir.resolve("maa-probe/first-recognition.json")
                file.writeText(JSONObject().put("positive", found).put("blank", blank).toString(2))
                assertFalse(found.getBoolean("controllerBound")); assertEquals(3000, found.getInt("taskStatus"))
                assertTrue(found.getJSONObject("detail").getJSONArray("all").length() > 0)
                assertFalse(blank.getBoolean("hit")); assertEquals(0, blank.getJSONObject("detail").getJSONArray("all").length())
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun invalidImageAndRepeatedCloseCannotInvalidateAnotherSession() {
        val folder = models()
        try {
            val engine = MaaOcr(folder.absolutePath)
            try {
                try { engine.recognizeRaw(byteArrayOf(1, 2, 3)); fail("Invalid image accepted") } catch (_: IllegalStateException) { }
                assertTrue(JSONObject(engine.recognizeRaw(image(true))).getBoolean("hit"))
            } finally { engine.close(); engine.close() }
            try { engine.recognizeRaw(image(true)); fail("Closed engine accepted a read") } catch (_: IllegalStateException) { }
            MaaOcr(folder.absolutePath).use { assertTrue(JSONObject(it.recognizeRaw(image(true))).getBoolean("hit")) }
        } finally { folder.deleteRecursively() }
    }
    @Test fun changedCopiedModelIsRejectedBeforeMaaInitializesItsWorker() {
        val folder = models()
        try {
            folder.resolve("det.onnx").writeBytes(byteArrayOf(1, 2, 3))
            try { MaaOcr(folder.absolutePath).use { fail("Changed model accepted") }; fail("No integrity rejection") }
            catch (error: IllegalStateException) { assertEquals("Changed pinned Maa model: det.onnx", error.message) }
            // A valid new session remains possible after rejection; the invalid model never entered Maa.
            val valid = models()
            try { MaaOcr(valid.absolutePath).use { assertTrue(JSONObject(it.recognizeRaw(image(true))).getBoolean("hit")) } }
            finally { valid.deleteRecursively() }
        } finally { folder.deleteRecursively() }
    }
}
