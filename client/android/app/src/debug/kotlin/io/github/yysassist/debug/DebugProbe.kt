package io.github.yysassist.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.yysassist.actions.ScreenActions
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.content.BundleReadResult
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.device.FrameResult
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.storage.*
import io.github.yysassist.vision.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only: what the recognizer sees on the screen right now. Takes one capture of the game, reads all text
 * on the whole frame, optionally reads text inside given regions (a region read can differ from the whole-frame read)
 * and scores the staged bundle's templates, then writes the frame, an annotated copy and probe.json. Sends no input.
 * Protected by android.permission.DUMP like [LocalTaskTrigger]; uses the same staged bundle for models and templates,
 * installed into a throwaway library that is deleted afterwards.
 *
 * adb shell am broadcast -n <pkg>/.debug.DebugProbe -a io.github.yysassist.debug.PROBE \
 *   [--es regions "x,y,w,h;x,y,w,h"] [--es templates "id,id"] [--es name label]
 * Regions are on the 2400x1080 reference frame. Output: debug-task/probe/<time>-<name>/{frame.png,annotated.png,probe.json};
 * the newest 20 probes are kept.
 */
class DebugProbe : BroadcastReceiver() {
    companion object {
        const val ACTION = "io.github.yysassist.debug.PROBE"
        private const val KEEP = 20
        private const val REFERENCE_WIDTH = 2400; private const val REFERENCE_HEIGHT = 1080
        private val running = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private fun fmt(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Application.getProcessName() != context.packageName) return
        val staged = context.getExternalFilesDir("debug-task") ?: return
        val root = File(staged, "probe").apply { mkdirs() }
        val latest = File(root, "latest.json")
        // Written whole then renamed, so a reader never sees half a file.
        fun publish(json: String) { val temporary = File(root, "latest.json.tmp"); temporary.writeText(json); temporary.renameTo(latest) }
        // A bad argument is reported, never thrown: a throw here would take the whole process down with it.
        val parsed = runCatching {
            intent.getStringExtra("regions")?.split(";")?.filter { it.isNotBlank() }?.map { spec ->
                spec.split(",").map { it.trim().toInt() }.also { require(it.size == 4 && it[2] > 0 && it[3] > 0) { "区域要写成 x,y,w,h：$spec" } } }.orEmpty()
        }
        val regions = parsed.getOrElse { publish(JSONObject().put("phase", "failed").put("error", "参数有误：${it.message}").toString(2)); return }
        val templates = intent.getStringExtra("templates")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (!running.compareAndSet(false, true)) { publish(JSONObject().put("phase", "failed").put("error", "上一次探测还没结束").toString(2)); return }
        val app = context.applicationContext
        val name = intent.getStringExtra("name")?.filter { it.isLetterOrDigit() || it == '-' }?.take(40).orEmpty()
        val folder = File(root, java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.ROOT).format(java.util.Date()) + (if (name.isEmpty()) "" else "-$name"))
        scope.launch {
            val report = JSONObject().put("startedAtEpochMillis", System.currentTimeMillis()).put("folder", folder.name)
            val dbName = "debug-probe.db"; val resources = File(app.noBackupFilesDir, "debug-probe")
            var database: AssistDatabase? = null
            try {
                app.deleteDatabase(dbName); resources.deleteRecursively()
                val db = AssistDatabase.open(app, dbName).also { database = it }
                folder.mkdirs()
                val codec = BundleCodec(BuiltInStepWorkFactory(ScreenActions({ error("no tasks run here") }, { false })).contracts)
                val library = TaskVersionLibrary(db, codec, TaskResourceFiles(resources))
                val observer = VersionedTextObserver(library, MaaTextBackend(app))
                // Files and encoding stay off the main thread: the accessibility service shares it with any running task.
                val text = withContext(Dispatchers.IO) { File(staged, "task-bundle.json").readText() }
                val bundle = when (val read = withContext(Dispatchers.IO) { codec.read(text) }) {
                    is BundleReadResult.Valid -> read.bundle
                    is BundleReadResult.Invalid -> error("任务包不可用：" + read.issues.joinToString("；") { "${it.location} ${it.reason}" })
                }
                check(library.install(text, bundle.version, TaskVersionLibrary.sha256(text)) { resource, output ->
                    File(staged, resource.assetName).inputStream().use { it.copyTo(output) } } == VersionChange.Applied) { "任务包安装失败" }
                check(library.activate(bundle.version) == VersionChange.Applied) { "任务包启用失败" }
                val unknown = templates.filter { id -> bundle.resources.none { it.id == id } }
                require(unknown.isEmpty()) { "任务包里没有这些模板：${unknown.joinToString()}" }
                val profile = bundle.visionProfiles.first()
                val models = OcrModelReferences(profile.recognizer, profile.models)
                val bridge = GameAccessibilityService.connection.value?.bridge ?: error("无障碍服务未连接")
                val frame = when (val capture = bridge.capture(profile.packageName)) {
                    is FrameResult.Ready -> capture.frame
                    is FrameResult.Unavailable -> error("截图失败：${capture.reason}" + (capture.platformCode?.let { "（$it）" } ?: ""))
                }
                frame.use {
                    val width = frame.bitmap.width; val height = frame.bitmap.height
                    val sx = width.toDouble() / REFERENCE_WIDTH; val sy = height.toDouble() / REFERENCE_HEIGHT
                    fun toFrame(r: List<Int>) = TextBounds((r[0] * sx).toInt(), (r[1] * sy).toInt(), (r[2] * sx).toInt(), (r[3] * sy).toInt())
                    fun toReference(b: TextBounds) = JSONArray(listOf((b.left / sx).toInt(), (b.top / sy).toInt(), (b.width / sx).toInt(), (b.height / sy).toInt()))
                    fun texts(list: List<RecognizedText>) = JSONArray(list.map { t ->
                        JSONObject().put("text", t.text).put("confidence", fmt(t.confidence).toDouble()).put("box", toReference(t.bounds)) })
                    report.put("frame", JSONArray(listOf(width, height))).put("version", bundle.version)
                    val observed = observer.observe(bundle.version, models, frame)
                    val whole = (observed as? TextReadResult.Observed) ?: error("整屏识别失败：$observed")
                    report.put("texts", texts(whole.candidates))
                    val pixels = checkNotNull(whole.pixels)
                    val requests = regions.map { InspectionRequest.Text(toFrame(it)) } +
                        templates.map { InspectionRequest.Image(TextBounds(0, 0, width, height), it) }
                    val values = if (requests.isEmpty()) emptyList() else when (val inspected = observer.inspect(bundle.version, models, whole.stamp, pixels, requests)) {
                        is InspectionResult.Inspected -> inspected.values
                        else -> error("区域检查失败：$inspected")
                    }
                    report.put("regions", JSONArray(regions.indices.map { i ->
                        JSONObject().put("region", JSONArray(regions[i])).put("texts", texts((values[i] as InspectionValue.Text).candidates)) }))
                    report.put("templates", JSONArray(templates.indices.map { i ->
                        val value = values[regions.size + i] as InspectionValue.Image
                        JSONObject().put("template", templates[i]).put("best", fmt(value.bestScore).toDouble())
                            .put("matches", JSONArray(value.matches.sortedByDescending { it.score }.take(5).map { m ->
                                JSONObject().put("score", fmt(m.score).toDouble()).put("box", toReference(m.bounds)) })) }))
                    withContext(Dispatchers.IO) {
                    File(folder, "frame.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    // Annotated copy: text boxes green with their index in probe.json, asked regions blue, template hits red.
                    val annotated = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(annotated)
                    fun paint(color: Int) = Paint().apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 3f }
                    val label = Paint().apply { color = Color.GREEN; textSize = 22f * sx.toFloat(); isAntiAlias = true }
                    fun rect(b: TextBounds, p: Paint) = canvas.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + b.width).toFloat(), (b.top + b.height).toFloat(), p)
                    whole.candidates.forEachIndexed { i, t -> rect(t.bounds, paint(Color.GREEN)); canvas.drawText("$i", t.bounds.left.toFloat(), t.bounds.top.toFloat() - 2, label) }
                    regions.forEach { rect(toFrame(it), paint(Color.BLUE)) }
                    templates.indices.forEach { i -> (values[regions.size + i] as InspectionValue.Image).matches.maxByOrNull { it.score }?.let { rect(it.bounds, paint(Color.RED)) } }
                    File(folder, "annotated.png").outputStream().use { annotated.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    annotated.recycle()
                    }
                }
                report.put("phase", "finished")
            } catch (error: Throwable) {
                report.put("phase", "failed").put("error", "${error.javaClass.simpleName}: ${error.message}")
            } finally {
                database?.close(); app.deleteDatabase(dbName); resources.deleteRecursively()
                val json = report.put("finishedAtEpochMillis", System.currentTimeMillis()).toString(2)
                if (folder.isDirectory) File(folder, "probe.json").writeText(json)
                publish(json)
                root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.deleteRecursively() }
                running.set(false)
            }
        }
    }
}
