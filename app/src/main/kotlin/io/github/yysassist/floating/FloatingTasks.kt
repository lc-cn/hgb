package io.github.yysassist.floating

import io.github.yysassist.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FloatingTask(val address: TaskAddress, val name: String)
sealed interface FloatingTasksState {
    data object Loading : FloatingTasksState
    data class Ready(val tasks: List<FloatingTask>, val hasFavorites: Boolean, val hasUnknown: Boolean) : FloatingTasksState
    data class Failure(val message: String) : FloatingTasksState
}
/** A view of current content/config/readiness. No queue, cached task definition or copied configuration. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun floatingTasks(preferences: FloatingPreferences, versions: StateFlow<VersionCatalogState>, runtime: ExecutionRuntime,
    scope: CoroutineScope): StateFlow<FloatingTasksState> = combine(preferences.state, versions, runtime.startConditionChanges) { prefs, catalog, _ -> prefs to catalog }
    .mapLatest { (prefs, catalog) ->
        when {
            prefs is FloatingPreferencesState.Failure -> FloatingTasksState.Failure(prefs.message)
            catalog is VersionCatalogState.Failure -> FloatingTasksState.Failure(catalog.message)
            prefs !is FloatingPreferencesState.Ready || catalog !is VersionCatalogState.Ready -> FloatingTasksState.Loading
            catalog.catalog.activeContent is ActiveTaskContent.Invalid -> FloatingTasksState.Failure(catalog.catalog.activeContent.message)
            else -> {
                val selected = prefs.settings.favorites
                val bundle = (catalog.catalog.activeContent as? ActiveTaskContent.Available)?.bundle
                var unknown = false
                val tasks = bundle?.tasks.orEmpty().mapNotNull { task ->
                    val address = TaskAddress(task.targetId, task.id)
                    if (address !in selected || task.trigger != null) null else when (runtime.assessStart(address)) {
                        TaskStartAvailability.READY -> FloatingTask(address, task.name)
                        TaskStartAvailability.UNKNOWN -> { unknown = true; null }
                        TaskStartAvailability.BLOCKED -> null
                    }
                }
                currentCoroutineContext().ensureActive()
                FloatingTasksState.Ready(tasks, selected.isNotEmpty(), unknown)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, FloatingTasksState.Loading)
