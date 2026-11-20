package io.github.yysassist

import android.content.Context
import io.github.yysassist.core.content.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which GitHub repository task versions come from: the one this build was made for (config/instance.json or the
 * fork's HGB_TASK_REPO), unless the user picked another in 开发 → 设置.
 */
class TaskSourceSetting(context: Context, defaultRepository: String) {
    private val preferences = context.getSharedPreferences("task-source", Context.MODE_PRIVATE)
    val default: TaskRepository = checkNotNull(TaskRepository.parse(defaultRepository)) { "Invalid TASK_REPO $defaultRepository" }
    private val mutable = MutableStateFlow(preferences.getString(KEY, null)?.let(TaskRepository::parse) ?: default)
    val current: StateFlow<TaskRepository> = mutable.asStateFlow()

    /** Uses [text] ("owner/name") from the next pull on; false when it is not a repository name. */
    fun set(text: String): Boolean {
        val chosen = TaskRepository.parse(text) ?: return false
        if (chosen == default) preferences.edit().remove(KEY).apply() else preferences.edit().putString(KEY, chosen.fullName).apply()
        mutable.value = chosen
        return true
    }
    fun reset() { preferences.edit().remove(KEY).apply(); mutable.value = default }

    private companion object { const val KEY = "repository" }
}
