package io.github.yysassist.vision

import io.github.yysassist.device.FrameStamp

internal data class TextBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class RecognizedText(val text: String, val confidence: Double, val bounds: TextBounds)
internal data class OcrModelReferences(val contract: String, val resources: Map<String, String>)
internal enum class TextReadProblem { FRAME_INVALID, RESOURCE_UNAVAILABLE, STORAGE_ERROR, UNSUPPORTED_MODEL, UNSUPPORTED_ENVIRONMENT, ENCODING_FAILED, ENGINE_UNAVAILABLE, INVALID_OUTPUT }
internal sealed interface TextReadResult {
    data object Interrupted : TextReadResult
    /** Complete observation, not proof of a business absence or task completion. */
    data class Observed(val stamp: FrameStamp, val version: String, val bundleDigest: String, val candidates: List<RecognizedText>,
        val diagnostics: List<String> = emptyList(), val pixels: EncodedPixels? = null, val timings: Map<String, Long> = emptyMap()) : TextReadResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : TextReadResult
}

/**
 * The exact pixels sent for an observation; region requests reuse them instead of a newer capture.
 * [raw] means RGBA bytes (width*height*4) rather than PNG.
 */
internal class EncodedPixels(val bytes: ByteArray, val width: Int, val height: Int, val raw: Boolean = false) {
    companion object {
        /** Copies a bitmap's pixels as RGBA without compression (ARGB_8888 memory order is RGBA). */
        fun raw(bitmap: android.graphics.Bitmap): EncodedPixels {
            require(bitmap.config == android.graphics.Bitmap.Config.ARGB_8888)
            val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(buffer)
            return EncodedPixels(buffer.array(), bitmap.width, bitmap.height, raw = true)
        }
    }
}

/** A request inside one already observed frame. Bounds are in frame pixels. */
internal sealed interface InspectionRequest {
    val region: TextBounds
    data class Text(override val region: TextBounds) : InspectionRequest
    /** template is a task-version resource id; the backend assigns transport names. */
    data class Image(override val region: TextBounds, val template: String) : InspectionRequest
}
internal sealed interface InspectionValue {
    data class Text(val candidates: List<RecognizedText>) : InspectionValue
    /** Best normalized correlation inside the region; 0.0 when no candidate was reported. */
    data class Image(val bestScore: Double, val matches: List<ImageMatch> = emptyList()) : InspectionValue
}
internal sealed interface InspectionResult {
    data object Interrupted : InspectionResult
    data class Inspected(val stamp: FrameStamp, val version: String, val values: List<InspectionValue>, val diagnostics: List<String> = emptyList(),
        val timings: Map<String, Long> = emptyMap()) : InspectionResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : InspectionResult
}
internal data class ImageMatch(val bounds: TextBounds, val score: Double)
