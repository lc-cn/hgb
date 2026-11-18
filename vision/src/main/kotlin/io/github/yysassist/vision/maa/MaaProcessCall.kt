package io.github.yysassist.vision.maa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.os.Process
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * One isolated observation request, no retry or timeout policy. Explicit stop is independent of the
 * native worker. Completion means no foreign use of this request's inputs remains:
 *  - "child-idle": the child wrote the result, closed every input and released ownership; the
 *    process stays loaded for the next request until it has been idle for [idleMillis];
 *  - "binder-death": the child process died (stop, rejection, crash, or idle release);
 *  - "not-submitted" / "not-accepted": the child never received or never accepted it.
 * Caller transfers ownership of imageRead. Use requestStop, not Future.cancel, to end foreign use.
 */
class MaaProcessCall(
    context: Context,
    private val models: Map<String, MaaModelFile>,
    private val imageRead: ParcelFileDescriptor,
    private val nativeBlock: Boolean = false,
    private val inspection: MaaInspection? = null,
    private val onQuiet: () -> Unit,
) {
    companion object {
        private val thread = HandlerThread("maa-process-host").apply { start() }
        internal val serial = Handler(thread.looper)
        private val reader = Executors.newSingleThreadExecutor()
        /** How long a quiet child stays loaded for reuse. Tests may shorten it. */
        @Volatile var idleMillis: Long = 20_000
        /** Serial-thread only. At most one live link; a retiring link must die before the next binds. */
        private var link: Link? = null
        private val pending = mutableListOf<MaaProcessCall>()
        private fun connect(call: MaaProcessCall) {
            val current = link
            when {
                current == null -> Link(call.app).also { link = it; pending += call; it.bind() }
                current.retiring -> pending += call
                current.service == null -> pending += call
                else -> call.connected(current)
            }
        }
        private fun linkReady(ready: Link) {
            if (link !== ready) return
            pending.toList().also { pending.clear() }.forEach { it.connected(ready) }
        }
        private fun linkGone(gone: Link) {
            if (link === gone) link = null
            val waiting = pending.toList().also { pending.clear() }
            waiting.forEach { if (!it.finished) connect(it) }
        }
        /** Releases an idle child now; a running request is unaffected. For tests and memory pressure. */
        fun releaseIdle() { serial.post { link?.let { if (it.calls.isEmpty()) it.retire() } } }
    }

    /** One binding to the child process, shared by successive requests. */
    private class Link(private val app: Context) {
        private val component = ComponentName(app, MaaObservationService::class.java)
        var binder: IBinder? = null
        var service: Messenger? = null
        var bound = false
        var dead = false
        var retiring = false
        val calls = mutableSetOf<MaaProcessCall>()
        private val idle = Runnable { if (calls.isEmpty()) retire() }
        private val death = IBinder.DeathRecipient { serial.post { died() } }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, remote: IBinder) {
                // Never adopt an automatic rebind after death: that would be a different child.
                if (dead || service != null || retiring) return
                if (name != component) { fail(); return }
                try { remote.linkToDeath(death, 0) } catch (_: RemoteException) { fail(); return }
                binder = remote; service = Messenger(remote)
                linkReady(this@Link)
            }
            override fun onServiceDisconnected(name: ComponentName) { if (binder?.isBinderAlive == false) died() }
            override fun onBindingDied(name: ComponentName) { if (binder?.isBinderAlive != true) died() else retire() }
            override fun onNullBinding(name: ComponentName) { fail() }
        }
        fun bind() {
            try {
                bound = app.bindService(Intent().setComponent(component), Context.BIND_AUTO_CREATE, { runnable -> serial.post(runnable) }, connection)
                if (!bound) fail()
            } catch (_: Exception) { fail() }
        }
        /** Binding never produced a child: waiting requests were not submitted anywhere. */
        private fun fail() {
            if (dead) return
            dead = true; unbind()
            if (link === this) link = null
            pending.toList().also { pending.clear() }.forEach { it.finishUnsubmitted(NoObservation.LOCAL_ERROR) }
        }
        fun scheduleIdle() { serial.removeCallbacks(idle); if (calls.isEmpty() && !retiring) serial.postDelayed(idle, idleMillis) }
        fun cancelIdle() = serial.removeCallbacks(idle)
        /** Unbinding makes the child terminate itself; new requests wait for the death evidence. */
        fun retire() {
            if (dead || retiring) return
            retiring = true; cancelIdle()
            if (binder == null) { died(); return }
            unbind()
            if (binder?.isBinderAlive == false) died()
        }
        fun stopChild(id: String) {
            if (dead) return
            try { service?.send(Message.obtain(null, MaaProcessProtocol.STOP).apply { data = Bundle().apply { putString("id", id) } }) }
            catch (_: RemoteException) { if (binder?.isBinderAlive == false) died() }
        }
        private fun unbind() {
            if (!bound) return
            bound = false
            try { app.unbindService(connection) } catch (_: Exception) { }
        }
        fun died() {
            if (dead) return
            dead = true; cancelIdle(); unbind()
            binder?.let { try { it.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { } }
            val affected = calls.toList(); calls.clear()
            affected.forEach { it.died() }
            linkGone(this)
        }
    }

    private val app = context.applicationContext
    private val id = UUID.randomUUID().toString()
    val completion = CompletableFuture<MaaProcessOutcome>()
    val acceptedPid = CompletableFuture<Int>()
    val nativeEntered = CompletableFuture<Unit>()
    val cleanupErrors = CompletableFuture<List<String>>()
    var binderDeathObservedAt: Long? = null
        private set
    var quietEvidence: String? = null
        private set
    private var current: Link? = null
    private var submitted = false
    private var accepted = false
    private var stopRequested = false
    private var processDead = false
    private var reading = false
    private var resultRead = false
    private var idleSeen = false
    private var finished = false
    private var pid: Int? = null
    private var proposed: MaaProcessOutcome? = null
    private val endpoint = Messenger(Handler(thread.looper) { receive(it); true })

    init {
        serial.post {
            try {
                require(models.keys == MaaProcessProtocol.modelNames.toSet())
                inspection?.let { value ->
                    require(value.templates.keys.all { it.matches(MaaProcessProtocol.templateName) })
                    org.json.JSONArray(value.requests).also { require(it.length() > 0) }
                }
                connect(this)
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

    private fun connected(link: Link) {
        if (finished) return
        if (stopRequested) { finishUnsubmitted(NoObservation.CANCELLED); return }
        current = link
        link.cancelIdle()
        link.calls += this
        try { sendObservation(link) }
        catch (_: RemoteException) {
            if (!submitted) finishUnsubmitted(NoObservation.LOCAL_ERROR)
            else if (link.binder?.isBinderAlive == false) link.died() else requestProcessStop()
        } catch (_: Exception) {
            if (!submitted) finishUnsubmitted(NoObservation.LOCAL_ERROR) else requestProcessStop()
        }
    }

    private fun sendObservation(link: Link) {
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
            inspection?.let { value ->
                data.putString("requests", value.requests)
                data.putStringArrayList("templates", ArrayList(value.templates.keys))
                value.templates.forEach { (name, template) ->
                    require(template.bytes > 0 && template.sha256.matches(Regex("[0-9a-f]{64}")))
                    val descriptor = ParcelFileDescriptor.open(template.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptors += descriptor
                    val key = MaaProcessProtocol.templateKey(name)
                    data.putParcelable(key, descriptor)
                    data.putLong("$key.bytes", template.bytes); data.putString("$key.sha256", template.sha256)
                }
            }
            data.putParcelable("image", imageRead)
            // From this point a failed IPC delivery is uncertain, not proof the child never used FDs.
            submitted = true
            link.service!!.send(Message.obtain(null, MaaProcessProtocol.OBSERVE).apply { this.data = data; replyTo = endpoint })
        } finally { descriptors.forEach { it.close() }; imageRead.close() }
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
                if (!accepted || data.getInt("pid") != pid || result == null || reading || resultRead || stopRequested) {
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
                        if (inspection == null) { parsed.getBoolean("hit"); parsed.getJSONObject("detail").getJSONArray("all") }
                        else check(parsed.getJSONArray("inspection").length() == org.json.JSONArray(inspection.requests).length())
                        MaaProcessOutcome.Observed(raw, checkNotNull(pid))
                    } catch (_: Exception) { MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR) }
                    serial.post {
                        reading = false; resultRead = true
                        if (!stopRequested) proposed = outcome
                        // An unreadable result means the child is not trusted for reuse.
                        if (outcome is MaaProcessOutcome.Missing) requestProcessStop()
                        finishIfQuiet()
                    }
                }
            }
            MaaProcessProtocol.IDLE -> {
                if (!accepted || data.getInt("pid") != pid) {
                    proposed = MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR); requestProcessStop(); return
                }
                idleSeen = true
                finishIfQuiet()
            }
            MaaProcessProtocol.REJECTED -> {
                result?.close()
                if (!stopRequested) proposed = if (accepted && data.getInt("pid") == pid &&
                    !data.getString("stage").isNullOrBlank() && !data.getString("errorType").isNullOrBlank())
                    MaaProcessOutcome.Missing(NoObservation.REJECTED, data.getString("stage"), data.getString("errorType"))
                else MaaProcessOutcome.Missing(NoObservation.PROTOCOL_ERROR)
                // Native state after a failure is not reused: the child ends and death is the quiet proof.
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
        current?.stopChild(id)
        // An IPC error by itself cannot release a possibly live native user.
    }

    private fun died() {
        if (finished) return
        processDead = true
        binderDeathObservedAt = SystemClock.elapsedRealtimeNanos()
        quietEvidence = "binder-death"
        if (proposed == null) proposed = MaaProcessOutcome.Missing(if (stopRequested) NoObservation.CANCELLED else NoObservation.PROCESS_DIED)
        finishIfQuiet()
    }
    private fun finishIfQuiet() {
        if (reading || finished) return
        if (processDead) { finish(); return }
        // Normal reuse: the full result is read and the child announced released ownership.
        if (idleSeen && resultRead && proposed is MaaProcessOutcome.Observed) { quietEvidence = "child-idle"; finish() }
        // A stop that lost the race with IDLE: native use already ended; nothing more to wait for.
        else if (idleSeen && stopRequested) { quietEvidence = "child-idle"; finish() }
    }
    private fun finishUnsubmitted(reason: NoObservation) {
        if (!submitted && !finished) { proposed = MaaProcessOutcome.Missing(reason); quietEvidence = "not-submitted"; finish() }
    }
    private fun finish() {
        if (finished) return
        finished = true
        pending.remove(this)
        current?.let { link -> link.calls.remove(this); if (!link.dead) link.scheduleIdle() }
        var failure: Exception? = null
        try { imageRead.close() } catch (error: Exception) { failure = error }
        try { onQuiet() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        try {
            if (!MaaProcessProtocol.folder(app.cacheDir, id).deleteRecursively()) error("Child work directory cleanup failed")
        } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        // Cleanup reporting cannot erase an already established observation/termination outcome.
        cleanupErrors.complete(failure?.let { listOf(it.javaClass.simpleName) + it.suppressed.map { item -> item.javaClass.simpleName } } ?: emptyList())
        completion.complete(checkNotNull(proposed))
        if (!acceptedPid.isDone) acceptedPid.completeExceptionally(IllegalStateException("Observation not accepted"))
        if (!nativeEntered.isDone) nativeEntered.completeExceptionally(IllegalStateException("Native block not entered"))
    }
}
