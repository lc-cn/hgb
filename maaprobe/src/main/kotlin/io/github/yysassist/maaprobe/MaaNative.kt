package io.github.yysassist.maaprobe

/** Isolated recognition experiment. No controller, action submission or platform bridge is exposed. */
internal object MaaNative {
    init { System.loadLibrary("yys_maa_probe") }
    external fun version(): String
    external fun create(directory: String): Long
    external fun recognizeRaw(handle: Long, image: ByteArray): ByteArray
    external fun destroy(handle: Long)
    /** Debug probe only: a foreign worker that cannot return; termination must remain independent. */
    external fun blockForProbe(entered: Runnable)
}

/** Serial synchronous calls: close cannot release native memory while recognition is using it. */
internal class MaaOcr(directory: String) : java.io.Closeable {
    private var handle: Long
    init {
        // Probe-only integrity gate before foreign worker initialization. This does not fix
        // upstream worker exception handling or replace production versioned resource leases.
        val expected = mapOf("det.onnx" to BuildConfig.DETECTOR_SHA256, "rec.onnx" to BuildConfig.RECOGNIZER_SHA256, "keys.txt" to BuildConfig.DICTIONARY_SHA256)
        expected.forEach { (name, digest) ->
            val file = java.io.File(directory, name)
            check(file.isFile) { "Missing pinned Maa model: $name" }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == digest) { "Changed pinned Maa model: $name" }
        }
        handle = MaaNative.create(directory).also { check(it != 0L) }
    }
    @Synchronized fun recognizeRaw(image: ByteArray): String {
        check(handle != 0L) { "Maa session closed" }
        return MaaNative.recognizeRaw(handle, image).toString(Charsets.UTF_8)
    }
    @Synchronized override fun close() {
        val owned = handle; handle = 0
        if (owned != 0L) MaaNative.destroy(owned)
    }
}
