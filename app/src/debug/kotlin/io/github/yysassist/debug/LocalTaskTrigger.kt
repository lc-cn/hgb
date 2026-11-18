package io.github.yysassist.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.actions.ScreenActions
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.device.DeviceExecutionReadiness
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.storage.*
import io.github.yysassist.vision.MaaTextBackend
import io.github.yysassist.vision.VersionedTextObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only. Runs one locally staged task bundle once inside the already running assistant
 * process, so the accessibility service stays bound. Protected by android.permission.DUMP: only the
 * shell (adb) or system can send it. Uses an isolated database and resource folder that are deleted
 * afterwards; the user's version library, configurations and queue are not touched.
 *
 * adb push <bundle dir>/. /sdcard/Android/data/<pkg>/files/debug-task/
 * adb shell am broadcast -n <pkg>/.debug.LocalTaskTrigger -a io.github.yysassist.debug.RUN_LOCAL_TASK \
 *   --es target <targetId> --es task <taskId>
 * Result: /sdcard/Android/data/<pkg>/files/debug-task/result.json
 * With --ez replay true and a staged replay.json, the screen is a ReplayDevice: no real capture or input.
 */
class LocalTaskTrigger : BroadcastReceiver() {
    companion object {
        const val ACTION = "io.github.yysassist.debug.RUN_LOCAL_TASK"
        private val running = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Application.getProcessName() != context.packageName) return
        val target = intent.getStringExtra("target") ?: return
        val task = intent.getStringExtra("task") ?: return
        val replay = intent.getBooleanExtra("replay", false)
        val staged = context.getExternalFilesDir("debug-task") ?: return
        val result = File(staged, "result.json")
        if (!running.compareAndSet(false, true)) { result.writeText(JSONObject().put("error", "already-running").toString(2)); return }
        val app = context.applicationContext
        scope.launch {
            val replayDevice = if (replay) ReplayDevice(staged) else null
            val report = JSONObject().put("target", target).put("task", task).put("replay", replay).put("startedAtEpochMillis", System.currentTimeMillis())
            val name = "debug-trigger.db"; val folder = File(app.noBackupFilesDir, "debug-trigger")
            app.deleteDatabase(name); folder.deleteRecursively()
            val database = AssistDatabase.open(app, name)
            val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                result.writeText(report.put("phase", "installing").toString(2))
                lateinit var runtime: ExecutionRuntime
                lateinit var observer: VersionedTextObserver
                val actions = if (replayDevice != null) ScreenActions({ observer }, { runtime.permitsInput(it) }, { replayDevice },
                    journal = { command -> runtime.submit(command).await() })
                    else ScreenActions({ observer }, { runtime.permitsInput(it) }, journal = { command -> runtime.submit(command).await() })
                val factory = BuiltInStepWorkFactory(actions, ActionTraceLog(database, runScope))
                val library = TaskVersionLibrary(database, BundleCodec(factory.contracts), TaskResourceFiles(folder))
                observer = VersionedTextObserver(library, MaaTextBackend(app))
                val text = File(staged, "task-bundle.json").readText()
                val bundle = (BundleCodec(factory.contracts).read(text) as? io.github.yysassist.core.content.BundleReadResult.Valid)?.bundle
                    ?: error("任务包不可用")
                check(library.install(text, bundle.version, TaskVersionLibrary.sha256(text)) { resource, output ->
                    File(staged, resource.assetName).inputStream().use { it.copyTo(output) }
                } == VersionChange.Applied) { "任务包安装失败" }
                check(library.activate(bundle.version) == VersionChange.Applied) { "任务包启用失败" }
                // A replay has no real screen to wait for; a live run waits for the real device as usual.
                runtime = if (replayDevice != null) ExecutionRuntime(ExecutionStateStore(database, library), library,
                    TaskAdmissionRepository(database, library), factory, runScope)
                else ExecutionRuntime(ExecutionStateStore(database, library), library, TaskAdmissionRepository(database, library),
                    factory, runScope, startReadiness = DeviceExecutionReadiness(factory.contracts))
                withTimeout(10_000) { runtime.status.first { it == RuntimeStatus.Ready } }
                result.writeText(report.put("phase", "running").toString(2))
                runtime.enqueueTask(TaskAddress(target, task)).await()
                val state = withTimeout(180_000) { runtime.state.first { value ->
                    value.facts.any { it.type == "task-completed" || it.type == "task-failed" } || value.unresolved.isNotEmpty() } }
                report.put("phase", "finished")
                    .put("facts", JSONArray(state.facts.map { JSONObject().put("type", it.type).put("detail", it.detail) }))
                    .put("unresolved", JSONArray(state.unresolved.map { it.reason }))
                    .put("deviceActions", JSONArray(state.deviceActions.map { record -> JSONObject()
                        .put("capability", record.intent.capability).put("actionId", record.intent.actionId)
                        .put("delivery", record.receipt?.delivery?.name).put("evidence", record.receipt?.evidence) }))
                replayDevice?.let { report.put("replayInputs", JSONArray(it.inputs)) }
                delay(500) // let the trace writer drain before reading back
                report.put("traces", JSONArray(database.actionTraces().recent(500).reversed().map { t -> JSONObject()
                    .put("actionId", t.actionId).put("capability", t.capability).put("result", t.result).put("reason", t.reason)
                    .put("elapsedMillis", t.elapsedMillis).put("trace", t.traceJson?.let(::JSONObject)) }))
            } catch (error: Throwable) {
                report.put("phase", "failed").put("errorType", error.javaClass.simpleName).put("error", error.message)
            } finally {
                runScope.cancel(); runScope.coroutineContext[Job]!!.join()
                database.close(); app.deleteDatabase(name); folder.deleteRecursively()
                result.writeText(report.put("finishedAtEpochMillis", System.currentTimeMillis()).toString(2))
                running.set(false)
            }
        }
    }
}
