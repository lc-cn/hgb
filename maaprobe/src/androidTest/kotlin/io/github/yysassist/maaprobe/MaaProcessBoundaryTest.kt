package io.github.yysassist.maaprobe

import io.github.yysassist.vision.maa.*

import android.graphics.*
import android.content.ComponentName
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/** Actual remote Binder + native child failure. Own package/synthetic frames only; no input controller. */
@RunWith(AndroidJUnit4::class)
class MaaProcessBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private val calls = mutableListOf<MaaProcessCall>()
    private val hostPid = Process.myPid()
    private val outcomes = JSONArray()
    @Before fun prepare() { folder = File(context.filesDir, "maa-process-fixture/${UUID.randomUUID()}"); check(folder.mkdirs()) }
    @After fun cleanup() {
        calls.forEach { it.requestStop(); it.completion.get(30, TimeUnit.SECONDS) }
        assertEquals(hostPid, Process.myPid())
        check(folder.deleteRecursively())
        val report = File(context.filesDir, "maa-probe/process-boundary.json")
        report.parentFile!!.mkdirs()
        val previous = if (report.isFile) JSONArray(report.readText()) else JSONArray()
        for (index in 0 until outcomes.length()) previous.put(outcomes.get(index))
        report.writeText(previous.toString(2))
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read == -1) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun models(corrupt: Boolean = false): Map<String, MaaModelFile> {
        val directory = File(folder, "models-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return MaaProbeAccess.modelNames.associateWith { name ->
            val file = File(directory, name)
            if (corrupt) {
                if (name == "keys.txt") file.writeText("a\nb\n") else file.writeBytes(byteArrayOf(1, 2, 3))
            } else context.assets.open("maa-model/$name").use { input -> file.outputStream().use(input::copyTo) }
            MaaModelFile(file, file.length(), hash(file))
        }
    }
    private fun image(text: Boolean = true, large: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(1000, 480, Bitmap.Config.ARGB_8888)
        val png = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                if (text) drawText("领取物资", 45f, 95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f })
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        return File(folder, "frame-${UUID.randomUUID()}.png").apply {
            if (!large) writeBytes(png)
            else {
                // A valid ancillary PNG tEXt chunk makes the encoded file exceed Binder's data budget.
                val type = "tEXt".toByteArray(Charsets.US_ASCII)
                val payload = "probe\u0000".toByteArray() + ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
                val checksum = CRC32().apply { update(type); update(payload) }.value.toInt()
                DataOutputStream(outputStream()).use { output ->
                    output.write(png, 0, png.size - 12)
                    output.writeInt(payload.size); output.write(type); output.write(payload); output.writeInt(checksum)
                    output.write(png, png.size - 12, 12)
                }
            }
        }
    }
    private fun call(models: Map<String, MaaModelFile>, frame: File, block: Boolean = false,
        quiet: AtomicInteger = AtomicInteger(), quietAt: AtomicLong = AtomicLong()): MaaProcessCall =
        MaaProcessCall(context, models, ParcelFileDescriptor.open(frame, ParcelFileDescriptor.MODE_READ_ONLY), block) { quietAt.set(SystemClock.elapsedRealtimeNanos()); quiet.incrementAndGet() }
            .also { calls += it }
    private fun record(name: String, call: MaaProcessCall, result: MaaProcessOutcome, quiet: AtomicInteger, quietAt: AtomicLong) {
        val child = call.acceptedPid.get(30, TimeUnit.SECONDS)
        assertNotEquals(hostPid, child)
        assertEquals(1, quiet.get())
        assertEquals("binder-death", call.quietEvidence)
        assertNotNull(call.binderDeathObservedAt)
        assertTrue(quietAt.get() >= call.binderDeathObservedAt!!)
        outcomes.put(JSONObject().put("case", name).put("hostPid", hostPid).put("childPid", child)
            .put("outcome", when (result) { is MaaProcessOutcome.Observed -> "observed"; is MaaProcessOutcome.Missing -> result.reason.name })
            .put("binderDeathObservedAt", call.binderDeathObservedAt).put("quietAt", quietAt.get()).put("quietCallbacks", quiet.get()))
    }
    private fun requireFaults() = Assume.assumeTrue("Explicit child-native fault experiment only",
        InstrumentationRegistry.getArguments().getString("allowChildNativeFaults") == "true")

    @Test fun installedServiceIsPrivateAndUsesTheDeclaredSeparateProcess() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, MaaObservationService::class.java), 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:maa_ocr", info.processName)
    }

    @Test fun normalAndBlankAreObservedInAnotherProcessAndCloseAfterDeath() {
        val models = models()
        for (text in listOf(true, false)) {
            val quiet = AtomicInteger(); val at = AtomicLong()
            val call = call(models, image(text), quiet = quiet, quietAt = at)
            val result = call.completion.get(30, TimeUnit.SECONDS)
            assertTrue(result.toString(), result is MaaProcessOutcome.Observed)
            val parsed = JSONObject((result as MaaProcessOutcome.Observed).raw)
            assertEquals(text, parsed.getBoolean("hit"))
            if (!text) assertEquals(0, parsed.getJSONObject("detail").getJSONArray("all").length())
            record(if (text) "positive" else "healthy-blank", call, result, quiet, at)
        }
    }

    @Test fun mismatchedModelAndInvalidImageReturnNoObservationInsteadOfAnEmptyHit() {
        val valid = models()
        val cases = listOf(valid + ("det.onnx" to valid.getValue("det.onnx").copy(sha256 = "0".repeat(64))) to image(),
            valid to File(folder, "bad.png").apply { writeBytes(byteArrayOf(1, 2, 3)) })
        for ((index, pair) in cases.withIndex()) {
            val (models, frame) = pair
            val quiet = AtomicInteger(); val at = AtomicLong()
            val call = call(models, frame, quiet = quiet, quietAt = at)
            val result = call.completion.get(30, TimeUnit.SECONDS)
            assertTrue(result is MaaProcessOutcome.Missing)
            assertEquals(NoObservation.REJECTED, (result as MaaProcessOutcome.Missing).reason)
            assertEquals(if (index == 0) "model-copy" else "native-recognize", result.rejectedAt)
            assertEquals("IllegalStateException", result.errorType)
            record("rejected-no-observation", call, result, quiet, at)
        }
    }

    @Test fun encodedFrameLargerThanOneMiBUsesDescriptorsAndStillProducesHealthyBlank() {
        val quiet = AtomicInteger(); val at = AtomicLong()
        val frame = image(text = false, large = true)
        assertTrue(frame.length() > 1024 * 1024)
        val call = call(models(), frame, quiet = quiet, quietAt = at)
        val result = call.completion.get(30, TimeUnit.SECONDS)
        assertTrue(result.toString(), result is MaaProcessOutcome.Observed)
        assertFalse(JSONObject((result as MaaProcessOutcome.Observed).raw).getBoolean("hit"))
        record("large-descriptor-frame", call, result, quiet, at)
    }

    @Test fun immediateStopNeverReleasesMoreThanOnceOrLeavesARequestRunning() {
        val quiet = AtomicInteger()
        val call = call(models(), image(), quiet = quiet)
        call.requestStop(); call.requestStop()
        assertEquals(MaaProcessOutcome.Missing(NoObservation.CANCELLED), call.completion.get(30, TimeUnit.SECONDS))
        assertEquals(1, quiet.get())
        assertTrue(call.quietEvidence in setOf("not-submitted", "binder-death"))
    }

    @Test fun validDigestButCorruptOnnxCrashesOnlyTheChildAndNextExplicitCallCanSucceed() {
        requireFaults()
        val quiet = AtomicInteger(); val at = AtomicLong()
        val call = call(models(corrupt = true), image(), quiet = quiet, quietAt = at)
        val result = call.completion.get(30, TimeUnit.SECONDS)
        assertEquals(MaaProcessOutcome.Missing(NoObservation.PROCESS_DIED), result)
        record("actual-ort-abort", call, result, quiet, at)
        val next = call(models(), image())
        assertTrue(next.completion.get(30, TimeUnit.SECONDS) is MaaProcessOutcome.Observed)
        assertNotEquals(call.acceptedPid.get(), next.acceptedPid.get())
    }

    @Test fun blockedNativeWorkerCanBeStoppedWithoutCompletingOrKillingAnotherRejectedCaller() {
        requireFaults()
        val models = models(); val frame = image()
        val quiet = AtomicInteger(); val at = AtomicLong()
        val blocked = call(models, frame, block = true, quiet = quiet, quietAt = at)
        blocked.nativeEntered.get(30, TimeUnit.SECONDS) // Marker originates inside the C++ call.
        assertFalse(blocked.completion.isDone); assertEquals(0, quiet.get())
        val secondQuiet = AtomicInteger()
        val second = call(models, frame, quiet = secondQuiet)
        assertEquals(MaaProcessOutcome.Missing(NoObservation.BUSY), second.completion.get(30, TimeUnit.SECONDS))
        assertEquals("not-accepted", second.quietEvidence); assertEquals(1, secondQuiet.get())
        assertFalse(blocked.completion.isDone); assertEquals(0, quiet.get())
        blocked.requestStop()
        val result = blocked.completion.get(30, TimeUnit.SECONDS)
        assertEquals(MaaProcessOutcome.Missing(NoObservation.CANCELLED), result)
        record("native-worker-block", blocked, result, quiet, at)
    }
}
