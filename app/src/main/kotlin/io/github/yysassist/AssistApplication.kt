package io.github.yysassist

import android.app.Application
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.content.GitHubTaskSource
import io.github.yysassist.core.content.TaskRepository
import io.github.yysassist.storage.AssistDatabase
import io.github.yysassist.storage.TaskVersionLibrary
import io.github.yysassist.storage.TaskConfigurationRepository
import io.github.yysassist.storage.TaskAdmissionRepository
import io.github.yysassist.storage.ExecutionStateStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-owned services survive Activity recreation; screens never construct an executor. */
class AssistApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var executor: ExecutionRuntime
        private set
    lateinit var appearance: AppearancePreferences
        private set
    lateinit var taskUpdates: TaskUpdates
        private set
    lateinit var configurations: TaskConfigurationRepository
        private set
    lateinit var taskEditor: TaskConfigurationEditor
        private set
    override fun onCreate() {
        super.onCreate()
        appearance = AppearancePreferences(this, scope)
        val database = AssistDatabase.open(this)
        val workers = BuiltInStepWorkFactory()
        val library = TaskVersionLibrary(database, BundleCodec(workers.contracts))
        executor = ExecutionRuntime(ExecutionStateStore(database, library), library,
            TaskAdmissionRepository(database, library), workers, scope)
        configurations = TaskConfigurationRepository(database, library)
        taskEditor = TaskConfigurationEditor(configurations, scope)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        val source = GitHubTaskSource(TaskRepository("lc-cn", "yys-helper"), "https://api.github.com/".toHttpUrl(), client)
        taskUpdates = TaskUpdates(library, source, scope)
        taskUpdates.pullLatest()
    }
}
