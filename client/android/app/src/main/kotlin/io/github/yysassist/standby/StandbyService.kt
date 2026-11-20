package io.github.yysassist.standby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.yysassist.AssistApplication
import io.github.yysassist.MainActivity
import io.github.yysassist.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service of 待命. It does no work itself: being in the foreground keeps 护肝宝's process, and with it
 * the cloud connection, from being stopped while the phone sits idle. Its notification says whether the screen is
 * being kept on, and tapping it opens the App.
 */
class StandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val standby = (application as AssistApplication).standby
        // Into the foreground first, always: a service started as foreground that stops without it crashes the App. A
        // restart by the system from the background may be refused (Android 12+); then it simply does not run.
        try { startForeground(NOTIFICATION, notification(standby.keepAwake.value)) }
        catch (refused: IllegalStateException) { stopSelf(); return START_NOT_STICKY }
        if (!standby.enabled.value) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        watch?.cancel()
        watch = scope.launch {
            standby.keepAwake.collect { awake -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(awake)) }
        }
        return START_STICKY
    }

    private fun notification(awake: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "待命", NotificationManager.IMPORTANCE_LOW).apply {
            description = "待命时常驻，保持和服务器的连接" })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).setContentIntent(open)
            .setContentTitle("护肝宝待命中")
            .setContentText(if (awake) "插着电，屏幕保持常亮，到点直接运行" else "没插电：到点时如果锁着屏，要等你解锁后才运行")
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL = "standby"
        const val NOTIFICATION = 7001
    }
}
