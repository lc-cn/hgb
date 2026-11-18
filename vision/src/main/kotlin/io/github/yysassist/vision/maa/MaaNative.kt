package io.github.yysassist.vision.maa

/** Isolated recognition experiment. No controller, action submission or platform bridge is exposed. */
internal object MaaNative {
    init { System.loadLibrary("yys_maa") }
    external fun version(): String
    external fun create(directory: String): Long
    external fun recognizeRaw(handle: Long, image: ByteArray): ByteArray
    external fun overrideTemplate(handle: Long, name: String, png: ByteArray)
    /** Region request on the same pixels: type is OCR or TemplateMatch only; param carries ROI. */
    external fun inspectRaw(handle: Long, type: String, param: String, image: ByteArray): ByteArray
    external fun destroy(handle: Long)
    /** Debug probe only: a foreign worker that cannot return; termination must remain independent. */
    external fun blockForProbe(entered: Runnable)
}
