package io.github.yysassist.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification
import io.github.yysassist.R
import java.util.concurrent.atomic.AtomicInteger

/** Text-only local notification authored by a task. Never carries screenshots; no network channel. */
internal class LocalNotifier(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val nextId = AtomicInteger(1)
    /** False when the user has not allowed notifications; that is not a task failure. */
    fun post(title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务消息", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(nextId.getAndIncrement(), Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
        return true
    }
    private companion object { const val CHANNEL = "task-messages" }
}
