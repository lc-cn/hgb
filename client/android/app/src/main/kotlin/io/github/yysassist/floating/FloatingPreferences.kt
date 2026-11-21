package io.github.yysassist.floating

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private val Context.floatingStore by preferencesDataStore("floating")
data class FloatingAnchor(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}
data class FloatingSettings(val visible: Boolean, val favorites: Set<TaskAddress>, val portrait: FloatingAnchor?, val landscape: FloatingAnchor?,
    val autoDock: Boolean = true,
    /** 采样、探测、试跑 on the ball: off until the user turns it on (anyone signed in may write tasks). */
    val devTools: Boolean = false)
sealed interface FloatingPreferencesState {
    data object Loading : FloatingPreferencesState
    data class Ready(val settings: FloatingSettings) : FloatingPreferencesState
    data class Failure(val message: String) : FloatingPreferencesState
}
/** Presentation preferences only. Neither visibility nor favorites mutate execution state. */
@OptIn(ExperimentalCoroutinesApi::class)
class FloatingPreferences(context: Context, private val scope: CoroutineScope,
    private val store: DataStore<Preferences> = context.applicationContext.floatingStore) {
    private val visible = booleanPreferencesKey("visible")
    private val favorites = stringSetPreferencesKey("favorite_tasks")
    private val autoDock = booleanPreferencesKey("auto_dock")
    private val devTools = booleanPreferencesKey("dev_tools")
    private val revision = MutableStateFlow(0L)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()
    val state = revision.flatMapLatest { store.data.map { FloatingPreferencesState.Ready(read(it)) as FloatingPreferencesState }
        .catch { emit(FloatingPreferencesState.Failure("悬浮设置读取失败，原数据保留")) } }
        .stateIn(scope, SharingStarted.Eagerly, FloatingPreferencesState.Loading)
    fun reload() { revision.value++ }
    fun setVisible(value: Boolean) = update { it[visible] = value }
    fun setAutoDock(value: Boolean) = update { it[autoDock] = value }
    fun setDevTools(value: Boolean) = update { it[devTools] = value }
    fun toggle(address: TaskAddress) = update { prefs ->
        val values = prefs[favorites].orEmpty().toMutableSet()
        val key = JsonArray(listOf(JsonPrimitive(address.targetId), JsonPrimitive(address.taskId))).toString()
        if (!values.remove(key)) values.add(key)
        prefs[favorites] = values
    }
    fun move(landscape: Boolean, anchor: FloatingAnchor) = update {
        val key = if (landscape) "landscape" else "portrait"
        it[floatPreferencesKey("${key}_x")] = anchor.x; it[floatPreferencesKey("${key}_y")] = anchor.y
    }
    private fun update(edit: (MutablePreferences) -> Unit) {
        scope.launch {
            try { store.edit { read(it); edit(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessages.tryEmit("悬浮设置未能保存，请重试") }
        }
    }
    private fun read(prefs: Preferences): FloatingSettings {
        fun anchor(key: String): FloatingAnchor? {
            val x = prefs[floatPreferencesKey("${key}_x")]; val y = prefs[floatPreferencesKey("${key}_y")]
            if (x == null && y == null) return null
            return FloatingAnchor(checkNotNull(x), checkNotNull(y))
        }
        val selected = prefs[favorites].orEmpty().map { encoded ->
            val items = Json.parseToJsonElement(encoded).jsonArray
            require(items.size == 2 && items.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })
            TaskAddress(items[0].jsonPrimitive.content, items[1].jsonPrimitive.content)
        }.toSet()
        // User confirmed: visible by default once accessibility is authorized.
        return FloatingSettings(prefs[visible] ?: true, selected, anchor("portrait"), anchor("landscape"), prefs[autoDock] ?: true,
            prefs[devTools] ?: false)
    }
}
