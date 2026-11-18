package io.github.yysassist.plans

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.yysassist.AssistApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Plan alarms and the system events that can move the next run time. Receives nothing from other apps. */
class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AssistApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { withTimeoutOrNull(8_000) { app.plans.wake() } } finally { pending.finish() }
        }
    }
}

/** Exact when the user allows it; otherwise the system may deliver the alarm some minutes late. */
class AndroidPlanAlarm(private val context: Context) : PlanAlarm {
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val intent get() = PendingIntent.getBroadcast(context, 0, Intent(context, PlanAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun set(atEpochMillis: Long) {
        if (canUseExactAlarms(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, intent)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, intent)
    }
    override fun cancel() = manager.cancel(intent)
}

fun canUseExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
