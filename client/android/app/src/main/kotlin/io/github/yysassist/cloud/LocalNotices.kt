package io.github.yysassist.cloud

import android.content.Context
import io.github.yysassist.actions.LocalNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices shown on this phone: those the server pushes for a local-notification channel of this device, and those
 * the phone noticed itself for one. The user can turn them off here without touching the channel; the system's own
 * notification permission still decides whether anything appears.
 */
internal class LocalNotices(context: Context, private val notifier: LocalNotifier) {
    private val preferences = context.applicationContext.getSharedPreferences("local-notices", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY, true))
    val enabled = mutableEnabled.asStateFlow()

    fun setEnabled(on: Boolean) { preferences.edit().putBoolean(KEY, on).apply(); mutableEnabled.value = on }
    /**
     * False when off here or not allowed by the system; that is never an error of the task. [key] names the notice, so
     * a notice shown again replaces itself instead of piling up or replacing another.
     */
    fun show(key: String, title: String, text: String): Boolean = enabled.value && notifier.post(title, text, key)

    private companion object { const val KEY = "enabled" }
}
