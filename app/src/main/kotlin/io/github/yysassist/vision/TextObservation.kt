package io.github.yysassist.vision

import io.github.yysassist.device.FrameStamp

internal data class TextBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class RecognizedText(val text: String, val confidence: Double, val bounds: TextBounds)
internal data class OcrModelReferences(val contract: String, val resources: Map<String, String>)
internal enum class TextReadProblem { FRAME_INVALID, RESOURCE_UNAVAILABLE, STORAGE_ERROR, UNSUPPORTED_MODEL, UNSUPPORTED_ENVIRONMENT, ENCODING_FAILED, ENGINE_UNAVAILABLE, INVALID_OUTPUT }
internal sealed interface TextReadResult {
    /** Complete observation, not proof of a business absence or task completion. */
    data class Observed(val stamp: FrameStamp, val version: String, val bundleDigest: String, val candidates: List<RecognizedText>, val diagnostics: List<String> = emptyList()) : TextReadResult
    data class Unavailable(val problem: TextReadProblem, val detail: String? = null, val diagnostics: List<String> = emptyList()) : TextReadResult
}
