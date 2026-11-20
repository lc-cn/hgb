package io.github.yysassist.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 待命: the phone waits for planned jobs on its own. While it is on, a foreground service keeps 护肝宝 running and
 * connected; while the phone is also plugged in, the screen is kept on, so it never locks and a job can open the game,
 * and a black screen saver covers it while idle (see StandbyScreen).
 * No app can unlock a secure lock screen, so unplugged or already locked, a job waits for the user to unlock.
 */
class StandbyMode(private val context: Context, scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("standby", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutablePlugged = MutableStateFlow(false)
    val plugged: StateFlow<Boolean> = mutablePlugged.asStateFlow()
    /** Keep the screen on now: standing by and plugged in. */
    val keepAwake: StateFlow<Boolean> = combine(enabled, plugged) { on, power -> on && power }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        val power = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { mutablePlugged.value = intent.action == Intent.ACTION_POWER_CONNECTED }
        }
        context.registerReceiver(power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) },
            Context.RECEIVER_NOT_EXPORTED)
        // The sticky battery broadcast says whether it is plugged in right now.
        mutablePlugged.value = (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    fun setEnabled(on: Boolean) {
        preferences.edit().putBoolean(ENABLED, on).apply()
        mutableEnabled.value = on
        if (on) ensureRunning() else context.stopService(Intent(context, StandbyService::class.java))
    }

    /**
     * Starts the service when standby is on. Android only lets an app start one from the foreground (or from a few
     * exempt states), so this is called when the App is opened and when the accessibility service connects.
     */
    fun ensureRunning() {
        if (!enabled.value) return
        // Refused from the background (ForegroundServiceStartNotAllowedException on Android 12+, an IllegalStateException).
        try { context.startForegroundService(Intent(context, StandbyService::class.java)) }
        catch (refused: IllegalStateException) { Log.i("hgb", "待命服务暂时不能从后台启动，下次打开 App 时再启动") }
    }

    /** Whether Android's battery saving leaves 护肝宝 alone overnight; without it the connection can be cut while asleep. */
    fun runsInBackground(): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Asks Android, in its own dialog, to stop optimizing 护肝宝's battery use. */
    fun requestBackgroundRunning() {
        val pages = listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        for (page in pages) try { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: android.content.ActivityNotFoundException) { }
    }

    private companion object { const val ENABLED = "enabled" }
}
