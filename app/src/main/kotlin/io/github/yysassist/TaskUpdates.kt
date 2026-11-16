package io.github.yysassist

import io.github.yysassist.core.content.GitHubTaskSource
import io.github.yysassist.core.content.TaskSourceException
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

sealed interface VersionCatalogState {
    data object Loading : VersionCatalogState
    data class Ready(val catalog: TaskVersionCatalog) : VersionCatalogState
    data class Failure(val message: String) : VersionCatalogState
}
sealed interface TaskPullState {
    data object Idle : TaskPullState
    data object Fetching : TaskPullState
    data class Finished(val message: String) : TaskPullState
    data class Failure(val message: String) : TaskPullState
}

/** Application-owned operation lifetimes: navigation/rotation does not fetch again or cancel a commit. */
class TaskUpdates(private val library: TaskVersionLibrary, private val source: GitHubTaskSource, private val scope: CoroutineScope) {
    private val mutableCatalog = MutableStateFlow<VersionCatalogState>(VersionCatalogState.Loading)
    val catalog = mutableCatalog.asStateFlow()
    private val mutablePull = MutableStateFlow<TaskPullState>(TaskPullState.Idle)
    val pull = mutablePull.asStateFlow()
    private val mutableOperation = MutableStateFlow<String?>(null)
    val operation = mutableOperation.asStateFlow()
    private val mutableMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = mutableMessage.asSharedFlow()
    private val pullGuard = Mutex()
    private var observation: Job? = null

    init { reloadCatalog() }
    fun reloadCatalog() {
        observation?.cancel()
        mutableCatalog.value = VersionCatalogState.Loading
        observation = scope.launch(Dispatchers.IO) {
            try { library.catalog.collect { mutableCatalog.value = VersionCatalogState.Ready(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableCatalog.value = VersionCatalogState.Failure("本地任务版本读取失败，请重试") }
        }
    }

    fun pullLatest() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!pullGuard.tryLock()) return@launch
            mutablePull.value = TaskPullState.Fetching
            try {
                mutablePull.value = withContext(Dispatchers.IO) {
                    val remote = source.latest()
                    // null here means verified remote absence, not a network or authorization error.
                    library.observeLatest(remote?.version)
                    if (remote == null) TaskPullState.Finished("暂无已发布的任务版本")
                    else when (val result = library.install(source.download(remote), remote.version, remote.sha256) { resource, sink ->
                        source.downloadResource(remote, resource, sink)
                    }) {
                        VersionChange.Applied -> TaskPullState.Finished("已取得 ${remote.version}")
                        VersionChange.Unchanged -> TaskPullState.Finished("${remote.version} 已在本地")
                        is VersionChange.Rejected -> TaskPullState.Failure(result.message)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: TaskSourceException) { mutablePull.value = TaskPullState.Failure(error.message ?: "任务拉取失败") }
            catch (_: Exception) { mutablePull.value = TaskPullState.Failure("本地保存结果暂无法确认，请重新读取任务版本") }
            finally { pullGuard.unlock() }
        }
    }

    fun activate(version: String) = change(version) { library.activate(version) }
    fun delete(version: String) = change(version) { library.delete(version) }
    private fun change(version: String, action: suspend () -> VersionChange) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!mutableOperation.compareAndSet(null, version)) return@launch
            try {
                val result = withContext(Dispatchers.IO) { action() }
                if (result is VersionChange.Rejected) mutableMessage.tryEmit(result.message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableMessage.tryEmit("操作结果暂无法确认，请重新读取任务版本")
                reloadCatalog()
            } finally { mutableOperation.value = null }
        }
    }
}
