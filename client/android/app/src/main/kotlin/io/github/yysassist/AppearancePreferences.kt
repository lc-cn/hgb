package io.github.yysassist

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode { SYSTEM, LIGHT, DARK }
sealed interface AppearanceState {
    data object Loading : AppearanceState
    data class Ready(val mode: ThemeMode) : AppearanceState
    data class Failure(val message: String) : AppearanceState
}
private val Context.appearanceStore by preferencesDataStore("appearance")
class AppearancePreferences(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.appearanceStore
    private val themeKey = stringPreferencesKey("theme_mode")
    val state = store.data.map { preferences ->
        val value = preferences[themeKey]
        val mode = if (value == null) ThemeMode.SYSTEM else ThemeMode.entries.firstOrNull { it.name == value }
            ?: error("无法识别已保存的主题设置")
        AppearanceState.Ready(mode) as AppearanceState
    }.catch { emit(AppearanceState.Failure("主题设置读取失败")) }
        .stateIn(scope, SharingStarted.Eagerly, AppearanceState.Loading)

    suspend fun setTheme(mode: ThemeMode) { store.edit { it[themeKey] = mode.name } }
}
