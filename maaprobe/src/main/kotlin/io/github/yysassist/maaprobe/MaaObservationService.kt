package io.github.yysassist.maaprobe

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Single-use bound process: main Looper owns stop, while native code runs on a separate worker. */
class MaaObservationService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { receive(it); true })
    private var ownerId: String? = null
    private var ownerBinder: IBinder? = null
    private val ownerDeath = IBinder.DeathRecipient { terminateSelf() }
    private fun childProcess() = Application.getProcessName() == "$packageName:maa_ocr"
    private fun terminateSelf() { if (childProcess()) Process.killProcess(Process.myPid()) }
    override fun onBind(intent: Intent): IBinder? = if (childProcess()) endpoint.binder else null
    override fun onUnbind(intent: Intent): Boolean { terminateSelf(); return false }
    override fun onDestroy() { worker.shutdownNow(); terminateSelf(); super.onDestroy() }

    private fun receive(message: Message) {
        val data = message.data
        val id = data.getString("id") ?: return
        if (message.what == MaaProcessProtocol.STOP) {
            if (id == ownerId) terminateSelf()
            return
        }
        if (message.what != MaaProcessProtocol.OBSERVE) return
        val reply = message.replyTo ?: run { MaaProcessProtocol.closeInputs(data); return }
        if (ownerId != null) {
            MaaProcessProtocol.closeInputs(data)
            reply(reply, MaaProcessProtocol.BUSY, id)
            return
        }
        try {
            MaaProcessProtocol.folder(cacheDir, id) // Validate before accepting ownership.
            ownerId = id
            ownerBinder = reply.binder
            reply.binder.linkToDeath(ownerDeath, 0)
            reply(reply, MaaProcessProtocol.ACCEPTED, id)
            worker.execute { observe(id, data, reply) }
        } catch (_: Exception) {
            MaaProcessProtocol.closeInputs(data)
            terminateSelf()
        }
    }

    private fun reply(destination: Messenger, what: Int, id: String, extra: Bundle = Bundle()) {
        extra.putString("id", id); extra.putInt("pid", Process.myPid())
        destination.send(Message.obtain(null, what).apply { data = extra })
    }

    private fun observe(id: String, data: Bundle, destination: Messenger) {
        val directory = MaaProcessProtocol.folder(cacheDir, id)
        var stage = "model-copy"
        val result: ParcelFileDescriptor
        try {
            check(directory.mkdirs()) { "Work directory already exists" }
            MaaProcessProtocol.modelNames.forEach { name ->
                val length = data.getLong("$name.bytes", -1)
                val expected = data.getString("$name.sha256")
                require(length > 0 && expected?.matches(Regex("[0-9a-f]{64}")) == true)
                val descriptor = checkNotNull(MaaProcessProtocol.descriptor(data, name))
                val file = File(directory, name)
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read.toLong() > length - count) throw IOException("Model too long")
                            output.write(buffer, 0, read); hash.update(buffer, 0, read); count += read
                        }
                    }
                }
                check(count == length && hash.digest().joinToString("") { "%02x".format(it) } == expected) { "Model integrity rejected" }
                check(file.setReadOnly())
            }
            stage = "image-read"
            val image = ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(MaaProcessProtocol.descriptor(data, "image"))).use { it.readBytes() }
            if (data.getBoolean("nativeBlock")) {
                check(BuildConfig.DEBUG)
                MaaNative.blockForProbe { reply(destination, MaaProcessProtocol.NATIVE_ENTERED, id) }
                error("Blocked native call unexpectedly returned")
            }
            // Manifest-verified data can still be an invalid ONNX program. Let the real native failure
            // happen here, outside the host; never replace it with a healthy empty OCR result.
            stage = "native-register"
            val handle = MaaNative.create(directory.absolutePath)
            stage = "native-recognize"
            val raw = try { MaaNative.recognizeRaw(handle, image) } finally { MaaNative.destroy(handle) }
            stage = "result-write"
            val resultFile = File(directory, "result.json").apply { writeBytes(raw); check(setReadOnly()) }
            result = ParcelFileDescriptor.open(resultFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            // Keep error meaning without transmitting file paths, model contents or stack dumps.
            try { reply(destination, MaaProcessProtocol.REJECTED, id, Bundle().apply {
                putString("stage", stage); putString("errorType", error.javaClass.simpleName)
            }) } catch (_: RemoteException) { terminateSelf() }
            return
        } finally {
            MaaProcessProtocol.closeInputs(data)
            // Keep ownership and process alive until STOP/death. A second caller cannot reuse it
            // between response delivery and the first caller proving process termination.
        }
        try {
            result.use { descriptor ->
                reply(destination, MaaProcessProtocol.RESULT, id, Bundle().apply { putParcelable("result", descriptor) })
            }
        } catch (_: Exception) {
            // Delivery/close failure is not evidence the result was never delivered. Do not issue a
            // contradictory REJECTED terminal response; host retains any already received result FD.
            terminateSelf()
        }
    }
}
