package io.github.yysassist.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

/** A text the phone recognized on a sample, with its box [x, y, width, height] in the sample's pixels. */
@Serializable
data class SampleText(val text: String, val box: List<Int>)
@Serializable
data class SampleView(val id: String, val target: String, val name: String, val device: String, val width: Int, val height: Int,
    val bytes: Long, val sha256: String, val createdAt: Long, val texts: List<SampleText> = emptyList(),
    /** Boxes marked as player information (names, ids, chat): never cut into a template. */
    val privateRegions: List<List<Int>> = emptyList(),
    /** Whether the phone has sent what it recognized on it; templates are cut only after that. */
    val recognized: Boolean = false)
/** What a later edit of a sample may change; a field left null stays as it is. */
@Serializable
data class SampleChange(val name: String? = null, val texts: List<SampleText>? = null, val privateRegions: List<List<Int>>? = null)

/**
 * Cuts a template out of [sample] at [rect] (sample pixels, which are the reference frame: samples come from the
 * phone at its own size). [confirmedTexts]: texts inside the box the author confirmed are game interface, not player
 * data. [reason]: why it may be larger than [Assets.MAX_TEMPLATE] on a side.
 */
@Serializable
data class TemplateCut(val id: String, val sample: String, val rect: List<Int>, val confirmedTexts: List<String> = emptyList(), val reason: String? = null)
@Serializable
data class TemplateView(val id: String, val target: String, val assetName: String, val bytes: Long, val sha256: String, val width: Int,
    val height: Int, val sample: String?, val rect: List<Int>, val reason: String? = null,
    /** Other templates of this game with the very same image. */
    val sameAs: List<String> = emptyList())

/**
 * Samples (screenshots a developer took on the phone) and the templates cut from them. Images live on disk under
 * [root], named by digest for templates so the same image is stored once; rows hold what pages list.
 */
class Assets(private val db: Database, private val root: File, private val clock: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private val samples = File(root, "samples")
    private val templates = File(root, "templates")

    private fun checkTarget(target: String) {
        if (!target.matches(Regex("[A-Za-z0-9._-]{1,64}"))) throw ApiException(400, "bad_target", "游戏名只能用字母、数字和 . _ -，最长 64")
    }
    private fun rectangle(rect: List<Int>, width: Int, height: Int, what: String) {
        if (rect.size != 4 || rect[0] < 0 || rect[1] < 0 || rect[2] <= 0 || rect[3] <= 0 || rect[0] + rect[2] > width || rect[1] + rect[3] > height)
            throw ApiException(400, "bad_rect", "$what 需为图内的 x,y,宽,高")
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    /**
     * Decodes [region] of a PNG (all of it when null), after checking its declared size: a small file can claim a huge
     * image, and decoding that whole would exhaust memory.
     */
    private fun read(bytes: ByteArray, region: java.awt.Rectangle? = null): BufferedImage {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw ApiException(400, "bad_image", "不是可读的图片")
        input.use {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: throw ApiException(400, "bad_image", "不是可读的图片")
            try {
                reader.input = it
                val (w, h) = runCatching { reader.getWidth(0) to reader.getHeight(0) }.getOrElse { throw ApiException(400, "bad_image", "不是可读的图片") }
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) throw ApiException(400, "bad_image", "样本尺寸太大")
                val param = reader.defaultReadParam.apply { region?.let(::setSourceRegion) }
                return runCatching { reader.read(0, param) }.getOrElse { throw ApiException(400, "bad_image", "不是可读的图片") }
            } finally { reader.dispose() }
        }
    }
    /** Writes atomically, so a half-written file is never served. */
    private fun write(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) { temporary.delete(); throw ApiException(500, "store_failed", "保存图片失败") }
    }

    // ---- Samples ----

    /** Stores a PNG the phone captured. Only PNG: a lossy screenshot would cut templates the phone never sees. */
    fun addSample(userId: String, target: String, name: String, device: String, bytes: ByteArray): SampleView {
        checkTarget(target)
        if (bytes.size > MAX_SAMPLE_BYTES) throw ApiException(413, "too_large", "样本太大")
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG)) throw ApiException(400, "bad_image", "样本须为 PNG")
        val image = read(bytes)
        val label = name.trim().take(64).ifEmpty { "样本" }
        val view = SampleView(UUID.randomUUID().toString(), target, label, device.trim().take(64), image.width, image.height,
            bytes.size.toLong(), sha256(bytes), clock())
        db.tx {
            val count = single("SELECT COUNT(*) FROM dev_samples WHERE user_id = ?", userId) { it.getInt(1) } ?: 0
            if (count >= MAX_SAMPLES) throw ApiException(409, "too_many", "样本已满 $MAX_SAMPLES 张，请先删掉不用的")
            val file = File(samples, "${view.id}.png")
            write(file, bytes)
            try {
                update("""INSERT INTO dev_samples (id, user_id, target_id, name, device, width, height, bytes, sha256, texts, private_regions, recognized, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', '[]', 0, ?)""",
                    view.id, userId, target, view.name, view.device, view.width, view.height, view.bytes, view.sha256, view.createdAt)
            } catch (failure: Exception) { file.delete(); throw failure }
        }
        return view
    }

    private fun sampleRow(rs: java.sql.ResultSet) = SampleView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
        rs.getInt(6), rs.getLong(7), rs.getString(8), rs.getLong(11), json.decodeFromString(rs.getString(9)), json.decodeFromString(rs.getString(10)), rs.getInt(12) == 1)
    private val sampleColumns = "id, target_id, name, device, width, height, bytes, sha256, texts, private_regions, created_at, recognized"

    fun samples(userId: String, target: String): List<SampleView> = db.tx {
        query("SELECT $sampleColumns FROM dev_samples WHERE user_id = ? AND target_id = ? ORDER BY created_at DESC", userId, target, row = ::sampleRow)
    }
    fun sample(userId: String, id: String): SampleView = db.tx {
        single("SELECT $sampleColumns FROM dev_samples WHERE user_id = ? AND id = ?", userId, id, row = ::sampleRow)
    } ?: throw ApiException(404, "no_sample", "没有这个样本")
    fun sampleImage(userId: String, id: String): ByteArray = File(samples, "${sample(userId, id).id}.png").readBytes()

    fun changeSample(userId: String, id: String, change: SampleChange): SampleView = db.tx {
        val current = sample(userId, id)
        change.texts?.let { texts ->
            if (texts.size > MAX_TEXTS) throw ApiException(400, "too_many", "识别文字太多")
            texts.forEach { rectangle(it.box, current.width, current.height, "文字 ${it.text.take(16)} 的位置") }
        }
        change.privateRegions?.forEach { rectangle(it, current.width, current.height, "玩家信息区域") }
        val next = current.copy(name = change.name?.trim()?.take(64)?.ifEmpty { null } ?: current.name,
            texts = change.texts?.map { it.copy(text = it.text.take(64)) } ?: current.texts, privateRegions = change.privateRegions ?: current.privateRegions)
        update("UPDATE dev_samples SET name = ?, texts = ?, private_regions = ?, recognized = ? WHERE user_id = ? AND id = ?", next.name,
            json.encodeToString(next.texts),
            json.encodeToString(next.privateRegions),
            if (next.recognized || change.texts != null) 1 else 0, userId, id)
        next.copy(recognized = next.recognized || change.texts != null)
    }

    /** Templates already cut from it stay: they are their own files. */
    fun deleteSample(userId: String, id: String) {
        db.tx {
            if (update("DELETE FROM dev_samples WHERE user_id = ? AND id = ?", userId, id) == 0) throw ApiException(404, "no_sample", "没有这个样本")
            update("UPDATE dev_templates SET sample_id = NULL WHERE user_id = ? AND sample_id = ?", userId, id)
        }
        File(samples, "$id.png").delete()
    }

    // ---- Templates ----

    /**
     * Cuts, checks and stores a template; cutting the same id again replaces it. Refused when the box touches player
     * information, or holds recognized text the author has not confirmed is part of the game's interface.
     */
    fun cut(userId: String, target: String, cut: TemplateCut): TemplateView {
        checkTarget(target)
        if (!cut.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}"))) throw ApiException(400, "bad_id", "模板名只能用字母、数字和 . _ -，最长 48")
        val sample = sample(userId, cut.sample)
        if (sample.target != target) throw ApiException(400, "wrong_target", "样本不属于这个游戏")
        if (!sample.recognized) throw ApiException(409, "not_recognized", "样本还没有识别结果，先在手机上识别再裁")
        rectangle(cut.rect, sample.width, sample.height, "裁剪框")
        val (x, y, w, h) = cut.rect
        val reason = cut.reason?.trim()?.take(120)?.ifEmpty { null }
        if ((w > MAX_TEMPLATE || h > MAX_TEMPLATE) && reason == null)
            throw ApiException(400, "too_big", "模板超过 ${MAX_TEMPLATE}×$MAX_TEMPLATE，请裁小或写明理由")
        fun overlaps(a: List<Int>) = a[0] < x + w && x < a[0] + a[2] && a[1] < y + h && y < a[1] + a[3]
        if (sample.privateRegions.any(::overlaps)) throw ApiException(400, "private", "裁剪框碰到了玩家信息区域")
        val unconfirmed = sample.texts.filter { overlaps(it.box) }.map { it.text }.distinct().filter { it !in cut.confirmedTexts }
        if (unconfirmed.isNotEmpty()) throw ApiException(409, "confirm_texts", "框内有文字需确认是游戏界面：${unconfirmed.joinToString("、")}")
        // Opaque RGB with nothing but pixels: no alpha, no text chunks, no timestamps.
        val source = read(File(samples, "${sample.id}.png").readBytes(), java.awt.Rectangle(x, y, w, h))
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { drawImage(source, 0, 0, null); dispose() }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val digest = sha256(bytes)
        val assetName = "${cut.id}.${digest.take(8)}.png"
        write(File(templates, "$digest.png"), bytes)
        return db.tx {
            update("""INSERT INTO dev_templates (user_id, target_id, id, asset_name, bytes, sha256, width, height, sample_id, rect, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, target_id, id) DO UPDATE SET asset_name = excluded.asset_name,
                bytes = excluded.bytes, sha256 = excluded.sha256, width = excluded.width, height = excluded.height, sample_id = excluded.sample_id,
                rect = excluded.rect, reason = excluded.reason, created_at = excluded.created_at""",
                userId, target, cut.id, assetName, bytes.size.toLong(), digest, w, h, sample.id, cut.rect.joinToString(","), reason, clock())
            templatesOf(userId, target).single { it.id == cut.id }
        }
    }

    fun templatesOf(userId: String, target: String): List<TemplateView> = db.tx {
        val rows = query("SELECT id, asset_name, bytes, sha256, width, height, sample_id, rect, reason FROM dev_templates WHERE user_id = ? AND target_id = ? ORDER BY id",
            userId, target) { TemplateView(it.getString(1), target, it.getString(2), it.getLong(3), it.getString(4), it.getInt(5), it.getInt(6),
                it.getString(7), it.getString(8).split(',').map(String::toInt), it.getString(9)) }
        rows.map { t -> t.copy(sameAs = rows.filter { it.sha256 == t.sha256 && it.id != t.id }.map { it.id }) }
    }
    fun deleteTemplate(userId: String, target: String, id: String) = db.tx {
        if (update("DELETE FROM dev_templates WHERE user_id = ? AND target_id = ? AND id = ?", userId, target, id) == 0)
            throw ApiException(404, "no_template", "没有这个模板")
    }
    private fun templateFile(sha256: String): File? = sha256.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { File(templates, "$it.png") }?.takeIf { it.isFile }
    /**
     * The image of this developer's template of this game with [sha256], for publishing; null when there is none.
     * Refused when the region it was cut from has since been marked as player information.
     */
    fun publishable(userId: String, target: String, sha256: String): File? {
        val cuts = db.tx { query("""SELECT t.id, t.rect, s.private_regions FROM dev_templates t LEFT JOIN dev_samples s ON s.id = t.sample_id AND s.user_id = t.user_id
            WHERE t.user_id = ? AND t.target_id = ? AND t.sha256 = ?""", userId, target, sha256) {
            Triple(it.getString(1), it.getString(2).split(',').map(String::toInt), it.getString(3)?.let { r -> json.decodeFromString<List<List<Int>>>(r) }.orEmpty()) } }
        if (cuts.isEmpty()) return null
        cuts.forEach { (id, r, private) ->
            if (private.any { a -> a[0] < r[0] + r[2] && r[0] < a[0] + a[2] && a[1] < r[1] + r[3] && r[1] < a[1] + a[3] })
                throw ApiException(400, "private", "模板 $id 的位置后来被标为玩家信息，请重新裁")
        }
        return templateFile(sha256)
    }
    fun templateImage(userId: String, target: String, id: String): ByteArray {
        val template = templatesOf(userId, target).singleOrNull { it.id == id } ?: throw ApiException(404, "no_template", "没有这个模板")
        return templateFile(template.sha256)?.readBytes() ?: throw ApiException(404, "no_template", "模板图片不见了")
    }

    companion object {
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 4096L * 4096
        const val MAX_SAMPLES = 500
        const val MAX_TEXTS = 2000
        /** Largest side of a template without a stated reason: small templates keep packages small and matching fast. */
        const val MAX_TEMPLATE = 64
    }
}
