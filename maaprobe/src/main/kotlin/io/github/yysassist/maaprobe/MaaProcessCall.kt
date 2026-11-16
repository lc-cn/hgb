package io.github.yysassist.maaprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * One isolated observation, no retry or timeout policy. Explicit stop is independent of the native
 * worker. Completion means no foreign use remains (Binder death, or a request never accepted).
 * This is a PoC, not the production executor/lease adapter. Callers use requestStop, not Future.cancel.
 */
internal class MaaProcessCall(
    context: Context,
    private val models: Map<String, MaaModelFile>,
    private val image: File,
    private val nativeBlock: Boolean = false,
    private val onQuiet: () -> Unit,
) {
    companion object {
        private val thread = HandlerThread("maa-process-host").apply { start() }
        private val serial = Handler(thread.looper)
        private val reader = Executors.newSingleThreadExecutor()
    }
    private val app = context.applicationContext
    private val id = UUID.randomUUID().toString()
    private val component = ComponentName(app, MaaObservationService::class.java)
    val completion = CompletableFuture<MaaProcessOutcome>()
    val acceptedPid = CompletableFuture<Int>()
    val nativeEntered = CompletableFuture<Unit>()
    var binderDeathObservedAt: Long? = null
        private set
    var quietEvidence: String? = null
        private set
    private var service: Messenger? = null
    private var binder: IBinder? = null
    private var bound = false
    private var submitted = false
    private var accepted = false
    private var stopRequested = false
    private var processDead = false
    private var reading = false
    private var finished = false
    private var pid: Int? = null
    private var proposed: MaaProcessOutcome? = null
    private val death = IBinder.DeathRecipient { serial.post { died() } }
    private val endpoint = Messenger(Handler(thread.looper) { receive(it); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, remote: IBinder) {
            if (finished || submitted) return // Never replay an observation after automatic rebind.
            if (name != component) { finishUnsubmitted(NoObservation.PROTOCOL_ERROR); return }
            binder = remote
            service = Messenger(remote)
            try {
                remote.linkToDeath(death, 0)
                sendObservation()
            } catch (_: RemoteException) {
                if (!submitted) finishUnsubmitted(NoObservation.LOCAL_ERROR)
                else if (!remote.isBinderAlive) died() else requestProcessStop()
            } catch (_: Exception) {
                if (!submitted) finishUnsubmitted(NoObservation.LOCAL_ERROR) else requestProcessStop()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (binder?.isBinderAlive == false) died()
        }
        override fun onBindingDied(name: ComponentName) {
            if (binder?.isBinderAlive == false) died() else requestProcessStop()
        }
        override fun onNullBinding(name: ComponentName) { finishUnsubmitted(NoObservation.PROTOCOL_ERROR) }
    }

    init {
        serial.post {
            try {
                require(models.keys == MaaProcessProtocol.modelNames.toSet())
                bound = app.bindService(Intent().setComponent(component), Context.BIND_AUTO_CREATE,
                    { runnable -> serial.post(runnable) }, connection)
                if (!bound) finishUnsubmitted(NoObservation.LOCAL_ERROR)
            } catch (_: Exception) { finishUnsubmitted(NoObservation.LOCAL_ERROR) }
        }
    }

    fun requestStop() {
        serial.post {
            if (!finished) {
                stopRequested = true
                proposed = MaaProcessOutcome.Missing(NoObservation.CANCELLED)
                if (!submitted) finishUnsubmitted(NoObservation.CANCELLED) else requestProcessStop()
            }
        }
    }

    private fun sendObservation() {
        if (stopRequested) { finishUnsubmitted(NoObservation.CANCELLED); return }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            val data = Bundle().apply { putString("id", id); putBoolean("nativeBlock", nativeBlock) }
            models.forEach { (name, model) ->
                require(model.bytes > 0 && model.sha256.matches(Regex("[0-9a-f]{64}")))
                val descriptor = ParcelFileDescriptor.open(model.file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor
                data.putParcelable(name, descriptor)
                data.putLong("$name.bytes", model.bytes); data.putString("$name.sha256", model.sha256)
            }
            val frame = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptors += frame; data.putParcelable("image", frame)
            // From this point a failed IPC delivery is uncertain, not proof the child never used FDs.
            submitted = true
            service!!.send(Message.obtain(null, MaaProcessProtocol.OBSERVE).apply { this.data = data; replyTo = endpoint })
        } finally { descriptors.forEach { it.close() } }
    }

    private fun receive(message: Message) {
        val data = message.data
        val result = MaaProcessProtocol.descriptor(data, "result")
        if (finished || data.getString("id") != id) { result?.close(); return }
        when (message.what) {
            MaaProcessProtocol.ACCEPTED -> {
                val child = data.getInt("pid")
                if (child <= 0 || child == Process.myPid()) {
                    proposed = MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR)
                    requestProcessStop(); return
                }
                accepted = true; pid = child; acceptedPid.complete(child)
                if (stopRequested) requestProcessStop()
            }
            MaaProcessProtocol.NATIVE_ENTERED -> {
                if (accepted && data.getInt("pid") == pid) nativeEntered.complete(Unit)
                else { proposed = MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR); requestProcessStop() }
            }
            MaaProcessProtocol.RESULT -> {
                if (!accepted || data.getInt("pid") != pid || result == null || reading || stopRequested) {
                    result?.close()
                    if (!stopRequested) proposed = MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR)
                    requestProcessStop(); return
                }
                reading = true
                reader.execute {
                    val outcome = try {
                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
                        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        val parsed = JSONObject(raw)
                        check(parsed.getInt("taskStatus") == 3000 && !parsed.getBoolean("controllerBound"))
                        parsed.getBoolean("hit"); parsed.getJSONObject("detail").getJSONArray("all")
                        MaaProcessOutcome.Observed(raw, checkNotNull(pid))
                    } catch (_: Exception) { MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR) }
                    serial.post {
                        reading = false
                        if (!stopRequested) proposed = outcome
                        requestProcessStop()
                        finishIfQuiet()
                    }
                }
            }
            MaaProcessProtocol.REJECTED -> {
                result?.close()
                if (!stopRequested) proposed = if (accepted && data.getInt("pid") == pid &&
                    !data.getString("stage").isNullOrBlank() && !data.getString("errorType").isNullOrBlank())
                    MaaProcessOutcome.Missing(NoObservation.REJECTED, data.getString("stage"), data.getString("errorType"))
                else MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR)
                requestProcessStop()
            }
            MaaProcessProtocol.BUSY -> {
                result?.close()
                // Service closed this caller's received inputs without accepting a worker.
                if (!stopRequested) proposed = MaaProcessOutcome.Missing(NoObservation.BUSY)
                quietEvidence = "not-accepted"
                finish()
            }
            else -> { result?.close(); proposed = MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR); requestProcessStop() }
        }
    }

    private fun requestProcessStop() {
        if (finished || processDead) return
        try { service?.send(Message.obtain(null, MaaProcessProtocol.STOP).apply { data = Bundle().apply { putString("id", id) } }) }
        catch (_: RemoteException) {
            if (binder?.isBinderAlive == false) died()
            // An IPC error by itself cannot release a possibly live native user.
        }
    }

    private fun died() {
        if (finished) return
        processDead = true
        binderDeathObservedAt = SystemClock.elapsedRealtimeNanos()
        quietEvidence = "binder-death"
        if (proposed == null) proposed = MaaProcessOutcome.Missing(if (stopRequested) NoObservation.CANCELLED else NoObservation.PROCESS_DIED)
        finishIfQuiet()
    }
    private fun finishIfQuiet() { if (processDead && !reading) finish() }
    private fun finishUnsubmitted(reason: NoObservation) {
        if (!submitted && !finished) { proposed = MaaProcessOutcome.Missing(reason); quietEvidence = "not-submitted"; finish() }
    }
    private fun finish() {
        if (finished) return
        finished = true
        var failure: Exception? = null
        try {
            binder?.let { try { it.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { } }
            if (bound) { app.unbindService(connection); bound = false }
        } catch (error: Exception) { failure = error }
        try { onQuiet() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        try {
            if (!MaaProcessProtocol.folder(app.cacheDir, id).deleteRecursively()) error("Child work directory cleanup failed")
        } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        if (failure == null) completion.complete(checkNotNull(proposed)) else completion.completeExceptionally(failure)
        if (!acceptedPid.isDone) acceptedPid.completeExceptionally(IllegalStateException("Observation not accepted"))
        if (!nativeEntered.isDone) nativeEntered.completeExceptionally(IllegalStateException("Native block not entered"))
    }
}
