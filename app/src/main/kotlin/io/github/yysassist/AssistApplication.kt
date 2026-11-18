package io.github.yysassist

import android.app.Application
import io.github.yysassist.floating.FloatingPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.content.GitHubTaskSource
import io.github.yysassist.core.content.TaskRepository
import io.github.yysassist.storage.AssistDatabase
import io.github.yysassist.storage.TaskVersionLibrary
import io.github.yysassist.storage.ActionTraceLog
import io.github.yysassist.actions.LocalNotifier
import io.github.yysassist.storage.TaskResourceFiles
import java.io.File
import io.github.yysassist.storage.TaskConfigurationRepository
import io.github.yysassist.storage.TaskAdmissionRepository
import io.github.yysassist.storage.ExecutionStateStore
import io.github.yysassist.vision.VersionedTextObserver
import io.github.yysassist.vision.MaaTextBackend
import io.github.yysassist.actions.ScreenActions
import io.github.yysassist.device.DeviceExecutionReadiness
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.yysassist.auth.EncryptedGitHubSessionStore
import io.github.yysassist.auth.GitHubSession
import io.github.yysassist.core.auth.GitHubDeviceAuthorization
import io.github.yysassist.core.auth.GitHubUserApi

/** Application-owned services survive Activity recreation; screens never construct an executor. */
class AssistApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var executor: ExecutionRuntime
        private set
    lateinit var floating: FloatingPreferences
        private set
    private val foregroundActivities = mutableSetOf<MainActivity>()
    private val mutableMainVisible = MutableStateFlow(false)
    val mainVisible = mutableMainVisible.asStateFlow()
    internal fun mainStarted(activity: MainActivity) { foregroundActivities += activity; mutableMainVisible.value = true }
    internal fun mainStopped(activity: MainActivity) { foregroundActivities -= activity; mutableMainVisible.value = foregroundActivities.isNotEmpty() }
    lateinit var appearance: AppearancePreferences
        private set
    lateinit var taskUpdates: TaskUpdates
        private set
    internal lateinit var github: GitHubSession
        private set
    lateinit var configurations: TaskConfigurationRepository
        private set
    lateinit var taskEditor: TaskConfigurationEditor
        private set
    internal lateinit var textObserver: VersionedTextObserver
        private set
    lateinit var actionTraces: ActionTraceLog
        private set
    internal lateinit var versionLibrary: TaskVersionLibrary
        private set
    internal lateinit var taskCodec: BundleCodec
        private set
    override fun onCreate() {
        super.onCreate()
        // Android creates this Application in :maa_ocr too. Only the main process owns
        // persistence recovery, task admission and update networking.
        if (getProcessName() != packageName) return
        appearance = AppearancePreferences(this, scope)
        floating = FloatingPreferences(this, scope)
        val database = AssistDatabase.open(this)
        actionTraces = ActionTraceLog(database, scope)
        val workers = BuiltInStepWorkFactory(ScreenActions({ textObserver }, { executor.permitsInput(it) },
            journal = { command -> executor.submit(command).await() }, notifier = LocalNotifier(this)::post), actionTraces)
        taskCodec = BundleCodec(workers.contracts)
        val library = TaskVersionLibrary(database, taskCodec, TaskResourceFiles(File(noBackupFilesDir, "task-resources")))
        versionLibrary = library
        textObserver = VersionedTextObserver(library, MaaTextBackend(this))
        executor = ExecutionRuntime(ExecutionStateStore(database, library), library,
            TaskAdmissionRepository(database, library), workers, scope,
            startReadiness = DeviceExecutionReadiness(workers.contracts))
        configurations = TaskConfigurationRepository(database, library)
        taskEditor = TaskConfigurationEditor(configurations, scope)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        github = GitHubSession(BuildConfig.GITHUB_CLIENT_ID,
            EncryptedGitHubSessionStore(File(noBackupFilesDir, "github-session.bin"), "io.github.yysassist.github.v1"),
            GitHubDeviceAuthorization(BuildConfig.GITHUB_CLIENT_ID, client), GitHubUserApi(client), scope)
        val source = GitHubTaskSource(TaskRepository("lc-cn", "yys-helper"), "https://api.github.com/".toHttpUrl(), client, github)
        taskUpdates = TaskUpdates(library, source, scope)
        taskUpdates.pullLatest()
    }
}
