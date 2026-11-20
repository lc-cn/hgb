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
import kotlinx.serialization.json.*
import java.io.File

/**
 * Debug builds only: hands debug-task/dev-command.json (a workbench command) to the app's own dev runner, as if the
 * server had sent it, and writes the final report to debug-task/dev-result.json. Reports to the server are still
 * attempted and may fail. Protected by android.permission.DUMP like [LocalTaskTrigger].
 *
 * adb shell am broadcast -n <pkg>/.debug.DevCommandTrigger -a io.github.yysassist.debug.DEV_COMMAND
 * With --es import <game>, instead imports debug-task/task-bundle.json as that game's workbench draft (replacing it).
 */
class DevCommandTrigger : BroadcastReceiver() {
    companion object { const val ACTION = "io.github.yysassist.debug.DEV_COMMAND" }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Application.getProcessName() != context.packageName) return
        val staged = context.getExternalFilesDir("debug-task") ?: return
        val result = File(staged, "dev-result.json")
        val runner = (context.applicationContext as AssistApplication).devRunner ?: return
        intent.getStringExtra("import")?.let { game ->
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val reply = try {
                    val text = withContext(Dispatchers.IO) { File(staged, "task-bundle.json").readText() }
                    val body = Json.parseToJsonElement(text)
                    val version = body.jsonObject["version"]?.jsonPrimitive?.content.orEmpty()
                    (context.applicationContext as AssistApplication).cloud.request("PUT", "/api/dev/drafts/$game",
                        buildJsonObject { put("baseVersion", version); put("body", body) }.toString()) ?: "{}"
                } catch (error: Throwable) { """{"error":"${error.javaClass.simpleName}: ${error.message}"}""" }
                withContext(Dispatchers.IO) { result.writeText(reply) }
            }
            return
        }
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
