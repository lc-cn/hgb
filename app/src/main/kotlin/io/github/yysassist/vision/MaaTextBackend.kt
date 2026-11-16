package io.github.yysassist.vision

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.yysassist.vision.maa.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal data class ModelInput(val file: File, val bytes: Long, val sha256: String)
internal sealed interface BackendTextResult {
    data class Observed(val candidates: List<RecognizedText>, val diagnostics: List<String> = emptyList()) : BackendTextResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : BackendTextResult
}
internal interface TextBackendCall {
    val result: CompletableFuture<BackendTextResult>
    val quiet: CompletableFuture<Unit>
    fun requestStop()
}
internal interface TextBackend {
    val modelContract: String
    /** Adopts imageRead and onQuiet together; errors after adoption are delivered through the call. */
    fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): TextBackendCall
}

/** First integration candidate behind a replaceable observation boundary, not a cross-theme claim. */
internal class MaaTextBackend(private val context: Context) : TextBackend {
    companion object { const val CONTRACT = "maa.ppocr.v1"; private val decoder = Executors.newCachedThreadPool() }
    override val modelContract = CONTRACT
    override fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): TextBackendCall {
        val quiet = CompletableFuture<Unit>()
        fun release() { try { onQuiet() } finally { quiet.complete(Unit) } }
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            try { imageRead.close() } finally { release() }
            return completed(BackendTextResult.Unavailable(TextReadProblem.UNSUPPORTED_ENVIRONMENT), quiet)
        }
        if (models.keys != setOf("detector", "recognizer", "dictionary")) {
            try { imageRead.close() } finally { release() }
            return completed(BackendTextResult.Unavailable(TextReadProblem.UNSUPPORTED_MODEL), quiet)
        }
        val roles = mapOf("det.onnx" to "detector", "rec.onnx" to "recognizer", "keys.txt" to "dictionary")
        val files = roles.mapValues { (_, role) -> models.getValue(role).let { MaaModelFile(it.file, it.bytes, it.sha256) } }
        val call = try { MaaProcessCall(context, files, imageRead, onQuiet = ::release) }
        catch (error: Exception) {
            try { imageRead.close() } finally { release() }
            return completed(BackendTextResult.Unavailable(TextReadProblem.ENGINE_UNAVAILABLE, error.javaClass.simpleName), quiet)
        }
        val result = call.completion.thenApplyAsync({ outcome ->
            val decoded = when (outcome) {
                is MaaProcessOutcome.Missing -> BackendTextResult.Unavailable(TextReadProblem.ENGINE_UNAVAILABLE,
                    listOfNotNull(outcome.reason.name, outcome.rejectedAt, outcome.errorType).joinToString(":"))
                is MaaProcessOutcome.Observed -> MaaTextDecoder.decode(outcome.raw, width, height)
            }
            val diagnostics = call.cleanupErrors.getNow(emptyList())
            when (decoded) {
                is BackendTextResult.Observed -> decoded.copy(diagnostics = diagnostics)
                is BackendTextResult.Unavailable -> decoded.copy(diagnostics = diagnostics)
            }
        }, decoder)
        return object : TextBackendCall {
            override val result = result
            override val quiet = quiet
            override fun requestStop() = call.requestStop()
        }
    }
    private fun completed(value: BackendTextResult, signal: CompletableFuture<Unit>) = object : TextBackendCall {
        override val result = CompletableFuture.completedFuture(value)
        override val quiet = signal
        override fun requestStop() = Unit
    }
}

internal object MaaTextDecoder {
    fun decode(raw: String, width: Int, height: Int): BackendTextResult = try {
        require(width > 0 && height > 0)
        val root = JSONObject(raw)
        require(integer(root.get("taskStatus")) == 3000 && root.get("controllerBound") == false && root.get("hit") is Boolean)
        val all = root.getJSONObject("detail").getJSONArray("all")
        val values = (0 until all.length()).map { index ->
            val item = all.getJSONObject(index)
            val text = item.get("text"); require(text is String)
            val score = item.get("score"); require(score is Number)
            val confidence = score.toDouble(); require(confidence.isFinite() && confidence in 0.0..1.0)
            val box = item.getJSONArray("box"); require(box.length() == 4)
            val x = box.int(0); val y = box.int(1); val w = box.int(2); val h = box.int(3)
            require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= width && y.toLong() + h <= height)
            RecognizedText(text, confidence, TextBounds(x, y, w, h))
        }
        BackendTextResult.Observed(values)
    } catch (_: Exception) { BackendTextResult.Unavailable(TextReadProblem.INVALID_OUTPUT) }
    private fun JSONArray.int(index: Int) = integer(get(index))
    private fun integer(value: Any): Int {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number % 1.0 == 0.0)
        return number.toInt()
    }
}
