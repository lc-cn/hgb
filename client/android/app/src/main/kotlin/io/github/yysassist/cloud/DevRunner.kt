package io.github.yysassist.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.actions.ScreenActions
import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.core.execution.ExecutorPhase
import io.github.yysassist.device.DeviceExecutionReadiness
import io.github.yysassist.device.FrameResult
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.device.StillScreen
import io.github.yysassist.protocol.*
import io.github.yysassist.storage.*
import io.github.yysassist.vision.MaaTextBackend
import io.github.yysassist.vision.OcrModelReferences
import io.github.yysassist.vision.TextReadResult
import io.github.yysassist.vision.VersionedTextObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs developer workbench commands on this phone, one at a time and only while no task runs here; the cloud agent
 * reports the phone busy meanwhile, so no job is sent. A run uses its own database, resources and executor, which are
 * deleted afterwards: the user's versions, settings and queue are never touched. Recognized text stays on the phone,
 * except in a sample the author took on purpose.
 */
internal class DevRunner(
    private val app: Context,
    private val cloud: () -> CloudAgent,
    private val main: ExecutionRuntime,
    private val mainResources: TaskResourceFiles,
    private val mainObserver: () -> VersionedTextObserver,
    private val activeBundle: () -> TaskBundle?,
    private val github: GitHubTaskSource,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    val busy: Boolean get() = running.get()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start(command: DevCommand) {
        if (!running.compareAndSet(false, true)) { scope.launch { report(command, DevReport(DevState.FAILED, "上一个调试命令还没结束")) }; return }
        scope.launch {
            try {
                val snapshot = main.state.value
                val report = if (snapshot.phase != ExecutorPhase.IDLE || snapshot.current != null || snapshot.waiting.isNotEmpty())
                    DevReport(DevState.FAILED, "手机正在执行任务")
                else try {
                    withTimeout(command.timeoutMs + 30_000) {
                        report(command, DevReport(DevState.RUNNING))
                        when (command.kind) { DevKind.SAMPLE -> sample(command); DevKind.RUN -> run(command) }
                    }
                } catch (timeout: TimeoutCancellationException) { DevReport(DevState.FAILED, "超时") }
                report(command, report)
            } catch (cancelled: CancellationException) {
                // Only the app shutting down ends it unreported; anything else cancelled inside still gets a report.
                if (!currentCoroutineContext().isActive) throw cancelled
                report(command, DevReport(DevState.FAILED, "已中断：${cancelled.message}".take(500)))
            }
            catch (error: Throwable) { report(command, DevReport(DevState.FAILED, "${error.javaClass.simpleName}: ${error.message}".take(500))) }
            finally { running.set(false) }
        }
    }

    /** The latest report, by command id; debug tools read it. */
    val last = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, DevReport>?>(null)

    private suspend fun report(command: DevCommand, report: DevReport) {
        last.value = command.id to report
        // A lost report leaves the run open on the server until it times out; nothing here depends on it.
        runCatching { cloud().request("POST", "/api/dev/runs/${command.id}/report", json.encodeToString(DevReport.serializer(), report)) }
    }

    private fun path(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /** Captures the game, reads the whole frame with the active version's recognizer and stores both as a sample. */
    private suspend fun sample(command: DevCommand): DevReport {
        val bundle = activeBundle() ?: return DevReport(DevState.FAILED, "手机还没有可用的任务包")
        val profile = bundle.visionProfiles.firstOrNull { it.targetId == command.target } ?: return DevReport(DevState.FAILED, "任务包里没有这个游戏的视觉配置")
        val bridge = GameAccessibilityService.connection.value?.bridge ?: return DevReport(DevState.FAILED, "无障碍服务未连接")
        val frame = when (val capture = bridge.capture(profile.packageName)) {
            is FrameResult.Ready -> capture.frame
            is FrameResult.Unavailable -> return DevReport(DevState.FAILED, "截图失败：${capture.reason}")
        }
        val (png, texts) = frame.use {
            val read = mainObserver().observe(bundle.version, OcrModelReferences(profile.recognizer, profile.models), frame)
            val candidates = (read as? TextReadResult.Observed)?.candidates ?: return DevReport(DevState.FAILED, "整屏识别失败：$read")
            val bytes = withContext(Dispatchers.IO) { ByteArrayOutputStream().also { frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            bytes to JsonArray(candidates.map { buildJsonObject { put("text", it.text); putJsonArray("box") { add(it.bounds.left); add(it.bounds.top); add(it.bounds.width); add(it.bounds.height) } } })
        }
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val created = cloud().requestBytes("POST", "/api/dev/games/${path(command.target)}/samples?name=${path(command.name)}&device=${path(device)}", png, "image/png")
            ?: return DevReport(DevState.FAILED, "服务器没有返回样本")
        val id = Json.parseToJsonElement(created.decodeToString()).jsonObject.getValue("id").jsonPrimitive.content
        cloud().request("PATCH", "/api/dev/samples/$id", buildJsonObject { put("texts", texts) }.toString())
        return DevReport(DevState.FINISHED, "已采样，读到 ${texts.size} 处文字", sample = id)
    }

    /** Runs the command's task once in a throwaway executor, on the real screen or on a still sample. */
    private suspend fun run(command: DevCommand): DevReport {
        val text = command.bundle ?: return DevReport(DevState.FAILED, "命令里没有任务包")
        val taskId = command.task ?: return DevReport(DevState.FAILED, "命令里没有任务")
        val name = "dev-run.db"; val folder = File(app.noBackupFilesDir, "dev-run")
        withContext(Dispatchers.IO) { app.deleteDatabase(name); folder.deleteRecursively() }
        val database = AssistDatabase.open(app, name)
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var still: StillScreen? = null
        try {
            lateinit var runtime: ExecutionRuntime
            lateinit var observer: VersionedTextObserver
            val probe = ScreenActions({ observer }, { false })
            val codec = BundleCodec(BuiltInStepWorkFactory(probe).contracts)
            val bundle = when (val read = withContext(Dispatchers.Default) { codec.read(text) }) {
                is BundleReadResult.Valid -> read.bundle
                is BundleReadResult.Invalid -> return DevReport(DevState.FAILED, "任务包不可用：" + read.issues.take(3).joinToString("；") { "${it.location} ${it.reason}" })
            }
            val definition = bundle.tasks.singleOrNull { it.targetId == command.target && it.id == taskId } ?: return DevReport(DevState.FAILED, "任务包里没有任务 $taskId")
            still = command.sample?.let { sample ->
                val bytes = cloud().requestBytes("GET", "/api/dev/samples/${path(sample)}/image") ?: return DevReport(DevState.FAILED, "取不到样本")
                val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return DevReport(DevState.FAILED, "样本不能解码")
                val packageName = bundle.visionProfiles.firstOrNull { it.targetId == command.target }?.packageName
                    ?: bundle.targets.single { it.id == command.target }.androidPackages.first()
                StillScreen(bitmap, packageName)
            }
            val actions = if (still != null) ScreenActions({ observer }, { runtime.permitsInput(it) }, { still }, journal = { c -> runtime.submit(c).await() })
                else ScreenActions({ observer }, { runtime.permitsInput(it) }, journal = { c -> runtime.submit(c).await() })
            val factory = BuiltInStepWorkFactory(actions, ActionTraceLog(database, runScope))
            val library = TaskVersionLibrary(database, BundleCodec(factory.contracts), TaskResourceFiles(folder))
            observer = VersionedTextObserver(library, MaaTextBackend(app))
            val installed = library.install(text, bundle.version, TaskVersionLibrary.sha256(text)) { resource, output -> fetch(command, bundle, resource, output) }
            if (installed != VersionChange.Applied || library.activate(bundle.version) != VersionChange.Applied) return DevReport(DevState.FAILED, "任务包安装失败")
            val values = normalizeConfigurationDraft(definition, command.configuration)
            val admission = TaskAdmissionRepository(database, library) { t, k -> values.takeIf { t == command.target && k == taskId } }
            runtime = if (still != null) ExecutionRuntime(ExecutionStateStore(database, library), library, admission, factory, runScope)
                else ExecutionRuntime(ExecutionStateStore(database, library), library, admission, factory, runScope, startReadiness = DeviceExecutionReadiness(factory.contracts))
            withTimeout(10_000) { runtime.status.first { it == RuntimeStatus.Ready } }
            val enqueued = runtime.enqueueTask(TaskAddress(command.target, taskId)).await()
            val accepted = (enqueued as? io.github.yysassist.RuntimeCommandResult.Applied)?.reply as? io.github.yysassist.core.execution.ExecutorReply.Enqueued
            if (accepted?.result !is io.github.yysassist.core.execution.EnqueueResult.Accepted)
                return DevReport(DevState.FAILED, "没能开始：" + ((accepted?.result as? io.github.yysassist.core.execution.EnqueueResult.Rejected)?.reason
                    ?: (enqueued as? io.github.yysassist.RuntimeCommandResult.Rejected)?.message ?: enqueued.toString()).take(300))
            val state = withTimeoutOrNull(command.timeoutMs) { runtime.state.first { value ->
                value.facts.any { it.type == "task-completed" || it.type == "task-failed" } || value.unresolved.isNotEmpty() } }
            delay(500) // let the trace writer drain before reading back
            val traces = database.actionTraces().recent(500).reversed().map { t ->
                DevTrace(t.stepId, t.actionId, t.capability, t.result, t.reason?.take(600), t.elapsedMillis, t.traceJson?.let(::sanitize)) }
            val facts = state?.facts.orEmpty().map { DevFact(it.type, it.detail) } + state?.unresolved.orEmpty().map { DevFact("unresolved", it.reason) } +
                still?.inputs.orEmpty().map { DevFact("still-input", it) }
            val done = state?.facts?.any { it.type == "task-completed" } == true
            return DevReport(if (state == null) DevState.FAILED else DevState.FINISHED,
                when { state == null -> "超时"; done -> "完成"; else -> "未完成" }, facts = facts, traces = traces)
        } finally {
            // Also when this run was cancelled or timed out: nothing is left open for the next one.
            withContext(NonCancellable) {
                runScope.cancel(); runScope.coroutineContext[Job]!!.join()
                database.close()
                still?.recycle()
                withContext(Dispatchers.IO) { app.deleteDatabase(name); folder.deleteRecursively() }
            }
        }
    }

    /** A resource from this phone's own copy when it has one, else from the release it names, else from the workbench. */
    private suspend fun fetch(command: DevCommand, bundle: TaskBundle, resource: TaskResource, output: java.io.OutputStream) {
        // Read whole first, so a copy that fails halfway writes nothing and the download below starts clean.
        mainResources.cached(resource.sha256)?.takeIf { it.length() == resource.bytes }?.let { file ->
            runCatching { withContext(Dispatchers.IO) { file.readBytes() } }.getOrNull()?.takeIf { it.size.toLong() == resource.bytes }
                ?.let { withContext(Dispatchers.IO) { output.write(it) }; return }
        }
        if (resource.release != null) {
            github.downloadResource(RemoteTaskVersion(bundle.version, 0, 0, "", emptyList()), resource, output)
            return
        }
        val bytes = cloud().requestBytes("GET", "/api/dev/games/${path(command.target)}/assets/${resource.sha256}")
            ?: throw java.io.IOException("工作台没有素材 ${resource.id}")
        withContext(Dispatchers.IO) { output.write(bytes) }
    }

    /** Keeps numbers, booleans and identifiers; drops any text a recognizer read. */
    private fun sanitize(text: String): JsonObject? = runCatching { keep(Json.parseToJsonElement(text)) as? JsonObject }.getOrNull()
    private fun keep(element: JsonElement, key: String? = null): JsonElement? = when (element) {
        is JsonObject -> JsonObject(element.mapNotNull { (k, v) -> keep(v, k)?.let { k to it } }.toMap())
        is JsonArray -> JsonArray(element.mapNotNull { keep(it, key) })
        is JsonPrimitive -> if (!element.isString || key in IDENTIFIERS) element else null
        else -> null
    }
    private companion object {
        val IDENTIFIERS = setOf("kind", "state", "template", "sha256", "templateSha256", "id", "capability", "found")
    }
}
