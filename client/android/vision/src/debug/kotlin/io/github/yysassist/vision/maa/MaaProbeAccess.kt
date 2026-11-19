package io.github.yysassist.vision.maa

/** Raw ABI access is available only to debug probes, never to a release task container. */
object MaaProbeAccess {
    val modelNames: List<String> get() = MaaProcessProtocol.modelNames
    fun version(): String = MaaNative.version()
    fun create(directory: String): Long = MaaNative.create(directory)
    fun recognizeRaw(handle: Long, image: ByteArray): ByteArray = MaaNative.recognizeRaw(handle, image)
    fun destroy(handle: Long) = MaaNative.destroy(handle)
}
