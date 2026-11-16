package io.github.yysassist.vision.maa

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** Private PoC IPC. Only data descriptors cross the boundary; no script, controller or input API. */
internal object MaaProcessProtocol {
    const val OBSERVE = 1
    const val STOP = 2
    const val ACCEPTED = 10
    const val RESULT = 11
    const val REJECTED = 12
    const val BUSY = 13
    const val NATIVE_ENTERED = 14
    val modelNames = listOf("det.onnx", "rec.onnx", "keys.txt")
    fun folder(cache: File, id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(cache, "maa-process/$id")
    }
    @Suppress("DEPRECATION") // Typed overload is API33; probe contract still supports API30.
    fun descriptor(data: Bundle, name: String): ParcelFileDescriptor? = data.getParcelable<android.os.Parcelable>(name) as? ParcelFileDescriptor
    fun closeInputs(data: Bundle) {
        (modelNames + "image").forEach { descriptor(data, it)?.close() }
    }
}

data class MaaModelFile(val file: File, val bytes: Long, val sha256: String)
enum class NoObservation { CANCELLED, PROCESS_DIED, REJECTED, BUSY, LOCAL_ERROR, PROTOCOL_ERROR }
sealed interface MaaProcessOutcome {
    /** Native observation transport completed; hit=false is still not a business absence decision. */
    data class Observed(val raw: String, val childPid: Int) : MaaProcessOutcome
    data class Missing(val reason: NoObservation, val rejectedAt: String? = null, val errorType: String? = null) : MaaProcessOutcome
}
