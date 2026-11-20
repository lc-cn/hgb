package io.github.yysassist.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yysassist.AssistApplication
import io.github.yysassist.protocol.DevCommand
import io.github.yysassist.protocol.DevReport
import io.github.yysassist.protocol.DevState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Debug builds only: hands debug-task/dev-command.json (a workbench command) to the app's own dev runner, as if the
 * server had sent it, and writes the final report to debug-task/dev-result.json. Reports to the server are still
 * attempted and may fail. Protected by android.permission.DUMP like [LocalTaskTrigger].
 *
 * adb shell am broadcast -n <pkg>/.debug.DevCommandTrigger -a io.github.yysassist.debug.DEV_COMMAND
 */
class DevCommandTrigger : BroadcastReceiver() {
    companion object { const val ACTION = "io.github.yysassist.debug.DEV_COMMAND" }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Application.getProcessName() != context.packageName) return
        val staged = context.getExternalFilesDir("debug-task") ?: return
        val result = File(staged, "dev-result.json")
        val runner = (context.applicationContext as AssistApplication).devRunner ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val command = withContext(Dispatchers.IO) { json.decodeFromString(DevCommand.serializer(), File(staged, "dev-command.json").readText()) }
                runner.start(command)
                val (_, report) = withTimeout(command.timeoutMs + 60_000) { runner.last.first { it?.first == command.id && it.second.state != DevState.RUNNING }!! }
                withContext(Dispatchers.IO) { result.writeText(json.encodeToString(DevReport.serializer(), report)) }
            } catch (error: Throwable) { withContext(Dispatchers.IO) { result.writeText("""{"error":"${error.javaClass.simpleName}: ${error.message}"}""") } }
        }
    }
}
