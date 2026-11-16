package io.github.yysassist.vision

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import io.github.yysassist.AssistApplication

/** Debug-only observation of the real Application instance in the recognition process. */
class ApplicationStartupProbeService : Service() {
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { request ->
        val app = application as AssistApplication
        fun initialized(read: () -> Any): Boolean = try { read(); true }
            catch (_: UninitializedPropertyAccessException) { false }
        request.replyTo.send(Message.obtain(null, 1).apply { data = Bundle().apply {
            putString("process", Application.getProcessName())
            putBoolean("executor", initialized { app.executor })
            putBoolean("updates", initialized { app.taskUpdates })
            putBoolean("observer", initialized { app.textObserver })
        } })
        true
    })
    override fun onBind(intent: Intent): IBinder = endpoint.binder
}
