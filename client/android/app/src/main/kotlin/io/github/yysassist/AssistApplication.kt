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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    internal lateinit var reactions: io.github.yysassist.reactions.ReactionMonitor
        private set
    lateinit var cloud: io.github.yysassist.core.cloud.CloudAgent
        private set
    /** Which game account each game here is signed in to, with its settings and plans as the server last sent them. */
    lateinit var accounts: io.github.yysassist.cloud.CloudAccounts
        private set
    internal lateinit var versionLibrary: TaskVersionLibrary
        private set
    internal lateinit var taskCodec: BundleCodec
        private set
    /** The account each game last showed on screen, by target; read by an identify task or any task that reports it. */
    val observedAccounts = MutableStateFlow<Map<String, io.github.yysassist.protocol.ObservedAccount>>(emptyMap())
    /** The server sent new accounts or settings; tasks that may start can differ now. */
    private val settingsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
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
            journal = { command -> executor.submit(command).await() }, notifier = LocalNotifier(this)::post,
            textObserved = { context, texts -> reactions.observed(context, texts) },
            accountObserved = { context, uid, nickname -> observedAccounts.update { it + (context.targetId to
                io.github.yysassist.protocol.ObservedAccount(uid, nickname, System.currentTimeMillis())) } }), actionTraces)
        taskCodec = BundleCodec(workers.contracts)
        val library = TaskVersionLibrary(database, taskCodec, TaskResourceFiles(File(noBackupFilesDir, "task-resources")))
        versionLibrary = library
        textObserver = VersionedTextObserver(library, MaaTextBackend(this))
        executor = ExecutionRuntime(ExecutionStateStore(database, library), library,
            TaskAdmissionRepository(database, library, settingsChanged) { target, task -> accounts.config(target, task)?.values }, workers, scope,
            startReadiness = DeviceExecutionReadiness(workers.contracts))
        reactions = io.github.yysassist.reactions.ReactionMonitor(io.github.yysassist.reactions.RuntimeReactionPort(executor), scope)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        github = GitHubSession(BuildConfig.GITHUB_CLIENT_ID,
            EncryptedGitHubSessionStore(File(noBackupFilesDir, "github-session.bin"), "io.github.yysassist.github.v1"),
            GitHubDeviceAuthorization(BuildConfig.GITHUB_CLIENT_ID, client), GitHubUserApi(client), scope)
        val source = GitHubTaskSource(TaskRepository("lc-cn", "yys-helper"), "https://api.github.com/".toHttpUrl(), client, github)
        taskUpdates = TaskUpdates(library, source, scope)
        val cloudExecutor = io.github.yysassist.cloud.RuntimeCloudExecutor(executor, taskUpdates, library, database, observedAccounts) { packageManager.getLaunchIntentForPackage(it) != null }
        cloud = io.github.yysassist.core.cloud.CloudAgent(client, io.github.yysassist.cloud.EncryptedCloudStore(File(noBackupFilesDir, "cloud-session.bin"), "io.github.yysassist.cloud.v1"),
            cloudExecutor, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", "android", BuildConfig.VERSION_NAME, scope,
            dataStore = io.github.yysassist.cloud.CloudDataFile(File(noBackupFilesDir, "cloud-data.json")))
        accounts = io.github.yysassist.cloud.CloudAccounts(cloud, cloudExecutor::apps)
        configurations = TaskConfigurationRepository(library, accounts)
        taskEditor = TaskConfigurationEditor(configurations, scope)
        scope.launch { cloud.data.drop(1).collect { settingsChanged.tryEmit(Unit); taskEditor.refresh() } }
        taskUpdates.pullLatest()
    }

    /**
     * Runs a task in the game on this device. Online, it is queued through the server as a job of the account the
     * game is signed in to, so that account never runs on two devices at once. Offline, it runs here with the
     * settings the server last sent.
     */
    /** Reads which account the game is signed in to, with the game's own identify task; works offline too. */
    fun identifyAccount(targetId: String) {
        val bundle = ((taskUpdates.catalog.value as? VersionCatalogState.Ready)?.catalog?.activeContent as? io.github.yysassist.storage.ActiveTaskContent.Available)?.bundle
        val task = bundle?.targets?.firstOrNull { it.id == targetId }?.accounts?.identify ?: return executor.notify("这个游戏还不能识别账号")
        executor.enqueueTask(io.github.yysassist.storage.TaskAddress(targetId, task))
    }
    fun runTask(task: io.github.yysassist.storage.TaskAddress) {
        scope.launch {
            val app = accounts.app(task.targetId)
            if (!accounts.online || app == null) { executor.enqueueTask(task); return@launch }
            val account = accounts.current(task.targetId)
                ?: return@launch executor.notify("先在 设置 → 云端控制 里选好${app.name}当前登录的游戏账号")
            try { cloud.runHere(task.targetId, task.taskId, account.id, app.key) }
            catch (rejected: io.github.yysassist.core.cloud.CloudRequestException) { executor.notify(rejected.message) }
            // The server may have queued it before the reply was lost, so it is not run here as well.
            catch (_: java.io.IOException) { executor.notify("没有连上服务器，请稍后再试") }
        }
    }
}
