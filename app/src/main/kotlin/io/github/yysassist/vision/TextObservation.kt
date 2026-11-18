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
        val diagnostics: List<String> = emptyList(), val pixels: EncodedPixels? = null) : TextReadResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : TextReadResult
}

/** The exact PNG sent for an observation; region requests reuse it instead of a newer capture. */
internal class EncodedPixels(val png: ByteArray, val width: Int, val height: Int)

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
    data class Image(val bestScore: Double) : InspectionValue
}
internal sealed interface InspectionResult {
    data object Interrupted : InspectionResult
    data class Inspected(val stamp: FrameStamp, val version: String, val values: List<InspectionValue>, val diagnostics: List<String> = emptyList()) : InspectionResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : InspectionResult
}
