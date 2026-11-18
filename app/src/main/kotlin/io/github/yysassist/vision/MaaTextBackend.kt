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
internal interface BackendCall<T> {
    val result: CompletableFuture<T>
    val quiet: CompletableFuture<Unit>
    fun requestStop()
}
internal interface TextBackendCall : BackendCall<BackendTextResult>
internal sealed interface BackendInspectionResult {
    data class Inspected(val values: List<InspectionValue>, val diagnostics: List<String> = emptyList()) : BackendInspectionResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : BackendInspectionResult
}
internal interface TextBackend {
    val modelContract: String
    /** Adopts imageRead and onQuiet together; errors after adoption are delivered through the call. */
    fun start(models: Map<String, ModelInput>, imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): TextBackendCall
    /** Same adoption rule. templates are keyed by the resource id named in each image request. */
    fun inspect(models: Map<String, ModelInput>, templates: Map<String, ModelInput>, requests: List<InspectionRequest>,
        imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): BackendCall<BackendInspectionResult> {
        val quiet = CompletableFuture<Unit>()
        try { imageRead.close() } finally { try { onQuiet() } finally { quiet.complete(Unit) } }
        return object : BackendCall<BackendInspectionResult> {
            override val result = CompletableFuture.completedFuture<BackendInspectionResult>(
                BackendInspectionResult.Unavailable(TextReadProblem.UNSUPPORTED_MODEL, "识别后端不支持区域检查"))
            override val quiet = quiet
            override fun requestStop() = Unit
        }
    }
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
    override fun inspect(models: Map<String, ModelInput>, templates: Map<String, ModelInput>, requests: List<InspectionRequest>,
        imageRead: ParcelFileDescriptor, width: Int, height: Int, onQuiet: () -> Unit): BackendCall<BackendInspectionResult> {
        val quiet = CompletableFuture<Unit>()
        fun release() { try { onQuiet() } finally { quiet.complete(Unit) } }
        fun refuse(problem: TextReadProblem, detail: String? = null): BackendCall<BackendInspectionResult> {
            try { imageRead.close() } finally { release() }
            return object : BackendCall<BackendInspectionResult> {
                override val result = CompletableFuture.completedFuture<BackendInspectionResult>(BackendInspectionResult.Unavailable(problem, detail))
                override val quiet = quiet
                override fun requestStop() = Unit
            }
        }
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return refuse(TextReadProblem.UNSUPPORTED_ENVIRONMENT)
        if (models.keys != setOf("detector", "recognizer", "dictionary")) return refuse(TextReadProblem.UNSUPPORTED_MODEL)
        val names = requests.filterIsInstance<InspectionRequest.Image>().map { it.template }.distinct()
        if (requests.isEmpty() || requests.size > 32 || names.size > 16 || names.toSet() != templates.keys) return refuse(TextReadProblem.UNSUPPORTED_MODEL)
        if (requests.any { r -> r.region.let { it.left < 0 || it.top < 0 || it.width <= 0 || it.height <= 0 ||
                it.left.toLong() + it.width > width || it.top.toLong() + it.height > height } }) return refuse(TextReadProblem.FRAME_INVALID)
        // Known fatal combination: OpenCV's KleidiCV dispatch picks SVE2 when the kernel advertises
        // sve2 without sve (seen on Apple-silicon AVDs) and the child dies with SIGILL. Refuse first.
        if (names.isNotEmpty() && CpuFeatures.inconsistentSve()) return refuse(TextReadProblem.UNSUPPORTED_ENVIRONMENT, "当前CPU特性不支持图像匹配")
        val transport = names.withIndex().associate { (index, id) -> id to "t$index.png" }
        val payload = JSONArray(requests.map { request ->
            val roi = JSONArray(listOf(request.region.left, request.region.top, request.region.width, request.region.height))
            when (request) {
                is InspectionRequest.Text -> JSONObject().put("type", "OCR").put("param", JSONObject().put("roi", roi))
                // Score is judged by the caller; method 5 is normalized correlation coefficient.
                is InspectionRequest.Image -> JSONObject().put("type", "TemplateMatch").put("param", JSONObject().put("roi", roi)
                    .put("template", JSONArray(listOf(transport.getValue(request.template)))).put("threshold", 0.01).put("method", 5))
            }
        }).toString()
        val roles = mapOf("det.onnx" to "detector", "rec.onnx" to "recognizer", "keys.txt" to "dictionary")
        val files = roles.mapValues { (_, role) -> models.getValue(role).let { MaaModelFile(it.file, it.bytes, it.sha256) } }
        val templateFiles = transport.entries.associate { (id, name) -> name to templates.getValue(id).let { MaaModelFile(it.file, it.bytes, it.sha256) } }
        val call = try { MaaProcessCall(context, files, imageRead, inspection = MaaInspection(payload, templateFiles), onQuiet = ::release) }
        catch (error: Exception) {
            try { imageRead.close() } finally { release() }
            return object : BackendCall<BackendInspectionResult> {
                override val result = CompletableFuture.completedFuture<BackendInspectionResult>(
                    BackendInspectionResult.Unavailable(TextReadProblem.ENGINE_UNAVAILABLE, error.javaClass.simpleName))
                override val quiet = quiet
                override fun requestStop() = Unit
            }
        }
        val result = call.completion.thenApplyAsync({ outcome ->
            val decoded = when (outcome) {
                is MaaProcessOutcome.Missing -> BackendInspectionResult.Unavailable(TextReadProblem.ENGINE_UNAVAILABLE,
                    listOfNotNull(outcome.reason.name, outcome.rejectedAt, outcome.errorType).joinToString(":"))
                is MaaProcessOutcome.Observed -> MaaTextDecoder.decodeInspection(outcome.raw, requests, width, height)
            }
            val diagnostics = call.cleanupErrors.getNow(emptyList())
            when (decoded) {
                is BackendInspectionResult.Inspected -> decoded.copy(diagnostics = diagnostics)
                is BackendInspectionResult.Unavailable -> decoded.copy(diagnostics = diagnostics)
            }
        }, decoder)
        return object : BackendCall<BackendInspectionResult> {
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

internal object CpuFeatures {
    /** True only for the observed fatal mismatch: sve2 advertised while sve is absent. */
    fun inconsistentSve(cpuinfo: String = runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")): Boolean {
        val features = cpuinfo.lineSequence().filter { it.startsWith("Features") }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }.toSet()
        return "sve2" in features && "sve" !in features
    }
}

internal object MaaTextDecoder {
    fun decodeInspection(raw: String, requests: List<InspectionRequest>, width: Int, height: Int): BackendInspectionResult = try {
        val root = JSONObject(raw)
        require(integer(root.get("taskStatus")) == 3000 && root.get("controllerBound") == false)
        val results = root.getJSONArray("inspection"); require(results.length() == requests.size)
        BackendInspectionResult.Inspected(requests.mapIndexed { index, request ->
            val item = results.getJSONObject(index)
            require(integer(item.get("taskStatus")) == 3000 && item.get("hit") is Boolean)
            val region = request.region
            fun inside(x: Int, y: Int, w: Int, h: Int) = x >= region.left && y >= region.top &&
                x.toLong() + w <= region.left.toLong() + region.width && y.toLong() + h <= region.top.toLong() + region.height
            val all = if (item.isNull("detail")) JSONArray() else item.getJSONObject("detail").getJSONArray("all")
            when (request) {
                is InspectionRequest.Text -> {
                    require(item.getString("algorithm") == "OCR")
                    val decoded = decode(JSONObject().put("taskStatus", 3000).put("controllerBound", false).put("hit", item.get("hit"))
                        .put("detail", JSONObject().put("all", all)).toString(), width, height)
                    require(decoded is BackendTextResult.Observed)
                    require(decoded.candidates.all { inside(it.bounds.left, it.bounds.top, it.bounds.width, it.bounds.height) })
                    InspectionValue.Text(decoded.candidates)
                }
                is InspectionRequest.Image -> {
                    require(item.getString("algorithm") == "TemplateMatch")
                    val matches = (0 until all.length()).map { i ->
                        val match = all.getJSONObject(i); val box = match.getJSONArray("box"); require(box.length() == 4)
                        require(inside(box.int(0), box.int(1), box.int(2), box.int(3)) && box.int(2) > 0 && box.int(3) > 0)
                        val score = match.get("score"); require(score is Number)
                        ImageMatch(TextBounds(box.int(0), box.int(1), box.int(2), box.int(3)), score.toDouble().also { require(it.isFinite() && it in -1.0..1.0) })
                    }
                    InspectionValue.Image((matches.maxOfOrNull { it.score } ?: 0.0).coerceAtLeast(0.0), matches)
                }
            }
        })
    } catch (_: Exception) { BackendInspectionResult.Unavailable(TextReadProblem.INVALID_OUTPUT) }
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
