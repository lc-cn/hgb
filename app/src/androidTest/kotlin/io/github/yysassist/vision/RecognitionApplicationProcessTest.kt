package io.github.yysassist.vision

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.AssistApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecognitionApplicationProcessTest {
    @Test fun recognitionProcessCannotCreateAnotherExecutorOrTaskUpdateOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull((context.applicationContext as AssistApplication).executor)
        val reply = CompletableFuture<Bundle>()
        val handler = HandlerThread("startup-test").apply { start() }
        val endpoint = Messenger(Handler(handler.looper) { reply.complete(it.data); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Messenger(service).send(Message.obtain(null, 1).apply { replyTo = endpoint })
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        try {
            bound = context.bindService(Intent(context, ApplicationStartupProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            val result = reply.get(10, TimeUnit.SECONDS)
            assertEquals("${context.packageName}:maa_ocr", result.getString("process"))
            assertFalse("Recognition process started another executor", result.getBoolean("executor"))
            assertFalse("Recognition process started task update networking", result.getBoolean("updates"))
            assertFalse("Recognition process constructed a nested observer", result.getBoolean("observer"))
        } finally {
            if (bound) context.unbindService(connection)
            handler.quitSafely(); handler.join()
        }
    }
}
