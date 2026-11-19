package io.github.yysassist

import io.github.yysassist.core.content.ContentIssue
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

sealed interface TaskEditorState {
    data object Loading : TaskEditorState
    data class Failure(val message: String) : TaskEditorState
    data class Ready(
        val snapshot: TaskConfigurationSnapshot, val values: JsonObject,
        val issues: List<ContentIssue> = emptyList(), val saving: Boolean = false,
        val message: String? = null, val canReload: Boolean = false,
    ) : TaskEditorState {
        val dirty: Boolean get() = values != snapshot.values
    }
}

/** Process-owned drafts survive navigation/rotation. Only an explicit save changes persisted rules. */
class TaskConfigurationEditor(private val repository: TaskConfigurationRepository, private val scope: CoroutineScope) {
    private val mutableDrafts = MutableStateFlow<Map<TaskAddress, TaskEditorState>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()

    fun open(address: TaskAddress) {
        if (mutableDrafts.value.containsKey(address)) return
        load(address, preserve = null)
    }
    fun reload(address: TaskAddress) {
        val previous = mutableDrafts.value[address] as? TaskEditorState.Ready
        if (previous?.saving == true) return
        load(address, previous)
    }
    private fun load(address: TaskAddress, preserve: TaskEditorState.Ready?) {
        mutableDrafts.update { it + (address to TaskEditorState.Loading) }
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.open(address) }
                mutableDrafts.update { it + (address to TaskEditorState.Ready(snapshot, preserve?.values ?: snapshot.values)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ConfigurationUnavailable) { loadFailed(address, preserve, error.message ?: "任务设置不可用") }
            catch (_: Exception) { loadFailed(address, preserve, "任务设置读取失败，请重试") }
        }
    }
    private fun loadFailed(address: TaskAddress, preserve: TaskEditorState.Ready?, message: String) {
        mutableDrafts.update { it + (address to (preserve?.copy(message = "$message，原输入已保留", canReload = true)
            ?: TaskEditorState.Failure(message))) }
    }
    /**
     * The server sent new data. Settings nobody is editing here are replaced when they really changed (another
     * account, or a save elsewhere), in place, without a loading screen; an unsaved draft is kept.
     */
    fun refresh() {
        mutableDrafts.value.forEach { (address, state) ->
            when {
                state is TaskEditorState.Failure -> load(address, preserve = null)
                state is TaskEditorState.Ready && !state.dirty && !state.saving -> scope.launch {
                    val fresh = runCatching { withContext(Dispatchers.IO) { repository.open(address) } }.getOrNull() ?: return@launch
                    if (fresh == state.snapshot) return@launch
                    mutableDrafts.update { all ->
                        val now = all[address] as? TaskEditorState.Ready
                        if (now == null || now.dirty || now.saving) all else all + (address to TaskEditorState.Ready(fresh, fresh.values))
                    }
                }
            }
        }
    }
    fun change(address: TaskAddress, field: String, value: JsonElement) {
        mutableDrafts.update { all ->
            val current = all[address] as? TaskEditorState.Ready ?: return@update all
            if (current.saving) all else all + (address to current.copy(values = JsonObject(current.values + (field to value)),
                issues = current.issues.filterNot { it.location == field }))
        }
    }
    fun save(address: TaskAddress) {
        val current = mutableDrafts.value[address] as? TaskEditorState.Ready ?: return
        if (current.saving) return
        mutableDrafts.update { it + (address to current.copy(saving = true, message = null)) }
        scope.launch {
            var saved = false
            val next = try {
                when (val result = withContext(Dispatchers.IO) { repository.save(current.snapshot, current.values) }) {
                    is ConfigurationSaveResult.Saved -> {
                        saved = true
                        TaskEditorState.Ready(result.snapshot, result.snapshot.values)
                    }
                    is ConfigurationSaveResult.Invalid -> current.copy(issues = result.issues)
                    ConfigurationSaveResult.RevisionChanged -> current.copy(message = "设置已在别处修改，原输入已保留，请重新读取后核对", canReload = true)
                    is ConfigurationSaveResult.Unavailable -> current.copy(message = result.message, canReload = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.copy(message = "保存结果暂无法确认，原输入已保留，请重新读取", canReload = true) }
            mutableDrafts.update { it + (address to next) }
            if (saved) mutableMessages.tryEmit("已保存规则")
        }
    }
}
