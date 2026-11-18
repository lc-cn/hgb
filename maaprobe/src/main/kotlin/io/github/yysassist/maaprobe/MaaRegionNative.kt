package io.github.yysassist.maaprobe

/**
 * Probe-only generic recognition (OCR / TemplateMatch / ColorMatch with ROI). Not linked by the
 * production App; no controller, action or pipeline graph. Calls are synchronous and serial.
 */
object MaaRegionNative {
    init { System.loadLibrary("yys_maa_region_probe") }
    external fun create(directory: String): Long
    external fun overrideImage(handle: Long, name: String, png: ByteArray)
    external fun recognize(handle: Long, type: String, param: String, png: ByteArray): ByteArray
    external fun destroy(handle: Long)
}
