package io.github.yysassist.server

import io.github.yysassist.protocol.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a send did not happen, in words for the console. */
class ChannelFailure(message: String) : Exception(message)

/** Posts a JSON body and returns the status and the start of the reply. Tests stand in for the network. */
fun interface ChannelHttp { fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> }

/**
 * The network as it is. The host is resolved once, and the connection goes only to the addresses checked then, so a
 * name that answers differently on a second lookup cannot reach inside; an address in a private, loopback, link-local
 * or other reserved range is refused unless [allowPrivate] (local development only). Redirects are not followed, the
 * whole call has 15 seconds, and only the first 4 KB of a reply is read.
 */
class OkChannelHttp(private val allowPrivate: Boolean = false) : ChannelHttp {
    private val client = OkHttpClient.Builder()
        .dns(object : Dns { override fun lookup(hostname: String) = Channels.allowed(hostname, allowPrivate) })
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val uri = Channels.publicAddress(url, allowPrivate)
        val request = Request.Builder().url(uri.toString()).headers(headers.toHeaders())
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { reply -> reply.code to (reply.body.source().let { source -> source.request(4096); source.buffer.readUtf8(minOf(source.buffer.size, 4096)) }) }
        } catch (error: ChannelFailure) { throw error }
        catch (error: java.io.IOException) { throw (error.cause as? ChannelFailure) ?: ChannelFailure("连接失败：${error.javaClass.simpleName}") }
    }
}

/**
 * The user's notification channels and the sending of notices to them. A notice recorded by a rule becomes one
 * delivery per enabled channel subscribed to that rule; deliveries are sent apart from the request or tick that made
 * them, retried 1, 5 and 15 minutes after a failure, then left failed for the user to send again. Secret fields are
 * sealed with a key derived from the server's secret and never returned.
 */
class Channels(private val db: Database, private val clock: () -> Long, private val events: ConsoleEvents, private val mailer: Mailer,
    serverSecret: ByteArray, private val http: ChannelHttp = OkChannelHttp(),
    /** Pushes a notice to a connected device to show; false when the device is not connected. */
    private val push: (deviceId: String, notice: DeviceNotice) -> Boolean = { _, _ -> false }) {
    /** Told when channels change, so devices learn which rules to show themselves. */
    var changedChannels: (String) -> Unit = {}
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stringMap = MapSerializer(String.serializer(), String.serializer())
    private val ruleIds = ListSerializer(String.serializer())
    private val key = SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(serverSecret, "HmacSHA256")) }
        .doFinal("channel-secrets".toByteArray()), "AES")
    private val testLimit = RateLimiter(10, 10 * MINUTE, clock)

    private data class Field(val id: String, val secret: Boolean, val required: Boolean)
    private val fields = mapOf(
        ChannelKind.DINGTALK to listOf(Field("url", true, true), Field("sign", true, false)),
        ChannelKind.WECOM to listOf(Field("url", true, true)),
        ChannelKind.BARK to listOf(Field("server", false, false), Field("key", true, true)),
        ChannelKind.EMAIL to emptyList(),
        ChannelKind.WEBHOOK to listOf(Field("url", true, true), Field("header", true, false)),
        ChannelKind.LOCAL to listOf(Field("device", false, true)),
    )
    private val kindNames = mapOf(ChannelKind.DINGTALK to "钉钉", ChannelKind.WECOM to "企业微信", ChannelKind.BARK to "Bark", ChannelKind.EMAIL to "邮件", ChannelKind.WEBHOOK to "Webhook", ChannelKind.LOCAL to "本机通知")

    // ---- channels ----

    private class Stored(val id: String, val userId: String, val kind: ChannelKind, val name: String, val enabled: Boolean, val rules: List<String>,
        val config: Map<String, String>, val secrets: Map<String, String>)
    private fun Database.stored(userId: String, id: String): Stored? =
        single("SELECT id, user_id, kind, name, enabled, rules_json, config_json, secrets FROM channels WHERE id = ? AND user_id = ?", id, userId) { row(it) }
    private fun row(it: java.sql.ResultSet) = Stored(it.getString(1), it.getString(2), ChannelKind.valueOf(it.getString(3)), it.getString(4), it.getInt(5) == 1,
        json.decodeFromString(ruleIds, it.getString(6)), json.decodeFromString(stringMap, it.getString(7)), it.getBytes(8)?.let(::open).orEmpty())

    fun list(userId: String): List<ChannelView> = db.tx {
        query("SELECT id, user_id, kind, name, enabled, rules_json, config_json, secrets FROM channels WHERE user_id = ? ORDER BY created_at, id", userId) { row(it) }
            .map { view(it) }
    }
    private fun Database.view(channel: Stored): ChannelView {
        val last = single("""SELECT id, channel_id, channel_name, state, attempts, created_at, next_at, error, test FROM deliveries WHERE channel_id = ?
            ORDER BY updated_at DESC, id LIMIT 1""", channel.id) { delivery(it) }
        return ChannelView(channel.id, channel.kind, channel.name, channel.enabled, channel.rules, target(channel), channel.secrets.keys.sorted(), channel.config, last)
    }
    private fun Database.target(channel: Stored): String = when (channel.kind) {
        ChannelKind.DINGTALK -> "机器人地址已填写" + if ("sign" in channel.secrets) " · 已加签" else ""
        ChannelKind.WECOM -> "机器人地址已填写"
        ChannelKind.BARK -> "${host(channel.config["server"] ?: BARK)} · 设备密钥已填写"
        ChannelKind.EMAIL -> verifiedEmail(channel.userId) ?: "账号没有已验证的邮箱"
        ChannelKind.WEBHOOK -> host(channel.secrets["url"].orEmpty()) + if ("header" in channel.secrets) " · 带认证头" else ""
        ChannelKind.LOCAL -> single("SELECT name FROM devices WHERE id = ? AND user_id = ? AND revoked = 0", channel.config["device"], channel.userId) { it.getString(1) }
            ?: "设备已解绑"
    }
    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: "地址无效"
    private fun Database.verifiedEmail(userId: String) = single("SELECT email FROM users WHERE id = ? AND email_verified = 1", userId) { it.getString(1) }

    /**
     * Checks [save] and returns what to store: the plain settings and the secrets. A secret left blank keeps [old]'s,
     * unless the user cleared it, the kind changed, or the address changed: a saved secret is never sent somewhere new.
     */
    private fun Database.checked(userId: String, save: ChannelSave, old: Stored?): Pair<Map<String, String>, Map<String, String>> {
        val name = save.name.trim()
        if (name.isEmpty() || name.length > 40) throw ApiException(400, "invalid_name", "名称需要 1 到 40 个字")
        if (save.rules.any { it !in RuleCatalog.byId }) throw ApiException(400, "invalid_rules", "订阅的规则不存在")
        val known = fields.getValue(save.kind)
        if (save.fields.keys.any { id -> known.none { it.id == id } }) throw ApiException(400, "invalid_fields", "有不认识的设置项")
        val config = mutableMapOf<String, String>(); val secrets = mutableMapOf<String, String>()
        val sameAddress = old?.kind == save.kind &&
            save.fields["url"]?.trim().let { it.isNullOrEmpty() || it == old.secrets["url"] } &&
            (save.kind != ChannelKind.BARK || save.fields["server"]?.trim().orEmpty() == old.config["server"].orEmpty())
        for (field in known) {
            val given = save.fields[field.id]?.trim().orEmpty()
            if (given.length > 500) throw ApiException(400, "invalid_fields", "设置项太长")
            // A secret left blank keeps the saved one, but only for the same kind: secrets never move between kinds.
            val keep = field.secret && field.id !in save.clear && (field.id == "url" || sameAddress)
            val value = given.ifEmpty { if (keep && old?.kind == save.kind) old.secrets[field.id].orEmpty() else "" }
            if (given.isEmpty() && field.secret && field.required && !keep && old?.kind == save.kind && old.secrets[field.id] != null)
                throw ApiException(400, "missing_field", "地址改了，请重新填写${label(save.kind, field.id)}")
            if (value.isEmpty()) { if (field.required) throw ApiException(400, "missing_field", "请填写${label(save.kind, field.id)}"); continue }
            if (field.secret) secrets[field.id] = value else config[field.id] = value
        }
        val url = secrets["url"].orEmpty()
        when (save.kind) {
            ChannelKind.DINGTALK -> if (!url.startsWith("https://oapi.dingtalk.com/robot/send?access_token=")) throw ApiException(400, "invalid_url", "钉钉机器人地址应以 https://oapi.dingtalk.com/robot/send?access_token= 开头")
            ChannelKind.WECOM -> if (!url.startsWith("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=")) throw ApiException(400, "invalid_url", "企业微信机器人地址应以 https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key= 开头")
            ChannelKind.BARK -> config["server"]?.let { webAddress(it, "Bark 服务器") }
            ChannelKind.WEBHOOK -> {
                webAddress(url, "Webhook 地址")
                secrets["header"]?.let { header ->
                    val (headerName, headerValue) = header.split(':', limit = 2).map(String::trim).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
                    if (!HEADER_NAME.matches(headerName) || headerValue.isEmpty() || headerName.lowercase() in FORBIDDEN_HEADERS || headerValue.any { it == '\r' || it == '\n' })
                        throw ApiException(400, "invalid_header", "认证头的格式是“名称: 值”，例如 Authorization: Bearer xxx")
                }
            }
            ChannelKind.EMAIL -> if (verifiedEmail(userId) == null) throw ApiException(400, "no_email", "邮件发到账号已验证的邮箱；这个账号还没有")
            ChannelKind.LOCAL -> if (single("SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND revoked = 0", config["device"], userId) { true } != true)
                throw ApiException(400, "invalid_device", "选一台已绑定的设备")
        }
        return config to secrets
    }
    private fun webAddress(value: String, what: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) throw ApiException(400, "invalid_url", "$what 需要是 http 或 https 地址")
    }
    private fun label(kind: ChannelKind, id: String) = when (id) {
        "url" -> if (kind == ChannelKind.WEBHOOK) "地址" else "机器人 Webhook 地址"; "key" -> "设备密钥"; "sign" -> "加签密钥"; "server" -> "服务器"; "device" -> "设备"; else -> "认证头"
    }

    /** All mail goes to the same address, so one mail channel is enough; more would only multiply the same message. */
    private fun Database.oneMailChannel(save: ChannelSave, old: Stored?) {
        if (save.kind == ChannelKind.EMAIL && old?.kind != ChannelKind.EMAIL &&
            (single("SELECT COUNT(*) FROM channels WHERE user_id = ? AND kind = 'EMAIL'", old?.userId ?: "") { it.getInt(1) } ?: 0) > 0)
            throw ApiException(409, "email_exists", "已经有一个邮件渠道了")
    }
    fun create(userId: String, save: ChannelSave): ChannelView {
        val view = db.tx {
            if ((single("SELECT COUNT(*) FROM channels WHERE user_id = ?", userId) { it.getInt(1) } ?: 0) >= MAX_CHANNELS)
                throw ApiException(409, "too_many_channels", "最多 $MAX_CHANNELS 个渠道")
            val (config, secrets) = checked(userId, save, null)
            if (save.kind == ChannelKind.EMAIL && (single("SELECT COUNT(*) FROM channels WHERE user_id = ? AND kind = 'EMAIL'", userId) { it.getInt(1) } ?: 0) > 0)
                throw ApiException(409, "email_exists", "已经有一个邮件渠道了")
            val id = UUID.randomUUID().toString(); val now = clock()
            update("""INSERT INTO channels (id, user_id, kind, name, enabled, rules_json, config_json, secrets, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, save.kind.name, save.name.trim(), if (save.enabled) 1 else 0, json.encodeToString(ruleIds, save.rules.distinct()),
                json.encodeToString(stringMap, config), secrets.takeIf { it.isNotEmpty() }?.let(::seal), now, now)
            view(stored(userId, id)!!)
        }
        changed(userId); changedChannels(userId); return view
    }
    fun change(userId: String, id: String, save: ChannelSave): ChannelView {
        val view = db.tx {
            val old = stored(userId, id) ?: throw ApiException(404, "not_found", "渠道不存在")
            val (config, secrets) = checked(userId, save, old)
            oneMailChannel(save, old)
            update("""UPDATE channels SET kind = ?, name = ?, enabled = ?, rules_json = ?, config_json = ?, secrets = ?, updated_at = ? WHERE id = ? AND user_id = ?""",
                save.kind.name, save.name.trim(), if (save.enabled) 1 else 0, json.encodeToString(ruleIds, save.rules.distinct()), json.encodeToString(stringMap, config),
                secrets.takeIf { it.isNotEmpty() }?.let(::seal), clock(), id, userId)
            view(stored(userId, id)!!)
        }
        changed(userId); changedChannels(userId); return view
    }
    fun delete(userId: String, id: String) {
        db.tx { if (update("DELETE FROM channels WHERE id = ? AND user_id = ?", id, userId) == 0) throw ApiException(404, "not_found", "渠道不存在") }
        changed(userId); changedChannels(userId)
    }
    private fun changed(userId: String) = events.publish(userId, "delivery", "{}")

    // ---- deliveries ----

    /** Called where a rule records a notice, in the same transaction: one pending delivery per subscribed channel. */
    fun Database.enqueue(userId: String, noticeId: String, ruleId: String, title: String, now: Long) {
        val subscribed = query("""SELECT id, name FROM channels c WHERE user_id = ? AND enabled = 1 AND EXISTS (SELECT 1 FROM json_each(c.rules_json) WHERE value = ?)""",
            userId, ruleId) { it.getString(1) to it.getString(2) }
        for ((channelId, name) in subscribed) update("""INSERT INTO deliveries (id, user_id, notice_id, channel_id, channel_name, state, attempts, next_at, title, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)""", UUID.randomUUID().toString(), userId, noticeId, channelId, name, now, title.take(200), now, now)
    }
    fun Database.deliveriesOf(noticeId: String): List<DeliveryView> =
        query("""SELECT id, channel_id, channel_name, state, attempts, created_at, next_at, error, test FROM deliveries WHERE notice_id = ? ORDER BY created_at, id""", noticeId) { delivery(it) }
    private fun delivery(it: java.sql.ResultSet) = DeliveryView(it.getString(1), it.getString(2), it.getString(3), DeliveryState.valueOf(it.getString(4)), it.getInt(5),
        it.getLong(6), it.getLong(7).takeUnless { _ -> it.wasNull() }, it.getString(8), it.getInt(9) == 1)

    /** Sends the deliveries that are due, one at a time, outside any transaction. Run on its own loop. */
    fun deliverDue() {
        // At most a few per user each round, so one user's slow or dead channels cannot hold up everyone else's.
        val due = db.tx { query("""SELECT id FROM (SELECT id, next_at, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY next_at, id) AS turn FROM deliveries
            WHERE state = 'PENDING' AND next_at <= ?) WHERE turn <= $PER_USER_ROUND ORDER BY next_at, id LIMIT 20""", clock()) { it.getString(1) } }
        for (id in due) {
            try { deliverOne(id) }
            catch (error: Exception) {
                // A row that cannot even be read (a channel sealed under another server secret, say) is settled, not retried forever.
                System.err.println("channels: delivery $id not sent: $error")
                runCatching { db.tx { update("UPDATE deliveries SET state = 'FAILED', next_at = NULL, error = '渠道配置无法读取', updated_at = ? WHERE id = ?", clock(), id) } }
            }
        }
    }
    private class Outgoing(val userId: String, val channel: Stored?, val title: String, val detail: String, val ruleId: String, val at: Long,
        val jobId: String?, val attempts: Int, val zone: String, val noticeId: String)
    private fun deliverOne(id: String) {
        val out = db.tx {
            single("""SELECT d.user_id, d.channel_id, n.title, n.detail, n.rule_id, n.at, n.job_id, d.attempts, u.time_zone, n.id FROM deliveries d
                JOIN notices n ON n.id = d.notice_id JOIN users u ON u.id = d.user_id WHERE d.id = ? AND d.state = 'PENDING'""", id) {
                val userId = it.getString(1); val channelId = it.getString(2)
                Outgoing(userId, channelId?.let { channel -> stored(userId, channel) }, it.getString(3), it.getString(4), it.getString(5), it.getLong(6),
                    it.getString(7), it.getInt(8), it.getString(9), it.getString(10))
            }
        } ?: return
        val failure = when {
            out.channel == null -> "渠道已删除"
            !out.channel.enabled -> "渠道已停用"
            else -> try { send(out.channel, out.title, out.detail, out.ruleId, out.at, out.jobId, out.zone, id, out.noticeId); null }
                catch (error: ChannelFailure) { error.message } catch (error: MailUnavailable) { error.message } catch (error: Exception) { "发送出错：${error.javaClass.simpleName}" }
        }
        val now = clock(); val attempts = out.attempts + 1
        // A channel that is gone or off is not retried; any other failure is, after 1, 5 and 15 minutes.
        val retry = failure != null && out.channel?.enabled == true && attempts <= RETRY_MINUTES.size
        db.tx {
            if (failure == null) update("UPDATE deliveries SET state = 'SENT', attempts = ?, next_at = NULL, error = '', updated_at = ? WHERE id = ?", attempts, now, id)
            else if (retry) update("UPDATE deliveries SET attempts = ?, next_at = ?, error = ?, updated_at = ? WHERE id = ?",
                attempts, now + RETRY_MINUTES[attempts - 1] * MINUTE, failure.take(300), now, id)
            else update("UPDATE deliveries SET state = 'FAILED', attempts = ?, next_at = NULL, error = ?, updated_at = ? WHERE id = ?", attempts, failure.orEmpty().take(300), now, id)
        }
        changed(out.userId)
    }

    /** A failed delivery is tried again from the start, now. */
    fun retry(userId: String, id: String) {
        db.tx {
            if (update("""UPDATE deliveries SET state = 'PENDING', attempts = 0, next_at = ?, error = '', updated_at = ? WHERE id = ? AND user_id = ? AND state = 'FAILED' AND test = 0""",
                    clock(), clock(), id, userId) == 0) throw ApiException(409, "not_failed", "只能重发发送失败的投递")
        }
        changed(userId)
    }

    /** Sends a test message with the draft, now, and keeps a test delivery; no notice is made and nothing is saved. */
    fun test(userId: String, test: ChannelTest): ChannelTestResult {
        if (!testLimit.allow(userId)) throw ApiException(429, "rate_limited", "测试发送太频繁，请稍后再试")
        val (draft, zone) = db.tx {
            val old = test.id?.let { stored(userId, it) ?: throw ApiException(404, "not_found", "渠道不存在") }
            val (config, secrets) = checked(userId, test.save, old)
            Stored(test.id ?: "", userId, test.save.kind, test.save.name.trim(), true, emptyList(), config, secrets) to
                (single("SELECT time_zone FROM users WHERE id = ?", userId) { it.getString(1) } ?: "Asia/Shanghai")
        }
        val failure = try { send(draft, "测试消息", "收到这条，说明“${draft.name}”可以用了。", "test", clock(), null, zone, null, "test:${UUID.randomUUID()}"); null }
            catch (error: ChannelFailure) { error.message } catch (error: MailUnavailable) { error.message } catch (error: Exception) { "发送出错：${error.javaClass.simpleName}" }
        val now = clock()
        db.tx {
            update("""INSERT INTO deliveries (id, user_id, notice_id, channel_id, channel_name, state, attempts, error, test, title, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, 1, ?, 1, '测试消息', ?, ?)""", UUID.randomUUID().toString(), userId, test.id?.takeIf { it.isNotEmpty() },
                draft.name, if (failure == null) "SENT" else "FAILED", failure.orEmpty().take(300), now, now)
            update("""DELETE FROM deliveries WHERE user_id = ? AND test = 1 AND id NOT IN (SELECT id FROM deliveries WHERE user_id = ? AND test = 1 ORDER BY created_at DESC LIMIT 20)""",
                userId, userId)
        }
        changed(userId)
        return if (failure == null) ChannelTestResult(true, "测试消息已发出") else ChannelTestResult(false, failure)
    }

    // ---- sending ----

    /**
     * Sends one message. Sending is at least once: a send that went out just before the server stopped is sent again,
     * so a webhook gets [deliveryId] to tell a repeat. Errors never quote what the other side answered, except the
     * official DingTalk and WeCom services' own explanation.
     */
    private fun send(channel: Stored, title: String, detail: String, ruleId: String, at: Long, jobId: String?, zone: String, deliveryId: String?, noticeId: String) {
        val time = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))).format(Instant.ofEpochMilli(at))
        val subject = "【护肝宝】$title"
        val text = listOf(subject, detail, time).filter { it.isNotBlank() }.joinToString("\n")
        fun text(content: String) = buildJsonObject { put("msgtype", "text"); putJsonObject("text") { put("content", content) } }.toString()
        when (channel.kind) {
            ChannelKind.DINGTALK -> {
                var url = channel.secrets.getValue("url")
                channel.secrets["sign"]?.let { sign ->
                    val stamp = clock()
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sign.toByteArray(), "HmacSHA256")) }.doFinal("$stamp\n$sign".toByteArray())
                    url += "&timestamp=$stamp&sign=" + URLEncoder.encode(Base64.getEncoder().encodeToString(mac), Charsets.UTF_8)
                }
                expectCode(http.post(url, emptyMap(), text(text)), "errcode", 0, "钉钉")
            }
            ChannelKind.WECOM -> expectCode(http.post(channel.secrets.getValue("url"), emptyMap(), text(text)), "errcode", 0, "企业微信")
            ChannelKind.BARK -> {
                val body = buildJsonObject { put("device_key", channel.secrets.getValue("key")); put("title", subject); put("body", listOf(detail, time).filter { it.isNotBlank() }.joinToString("\n")); put("group", "护肝宝") }
                expectCode(http.post((channel.config["server"] ?: BARK).trimEnd('/') + "/push", emptyMap(), body.toString()), "code", 200, "Bark")
            }
            ChannelKind.WEBHOOK -> {
                val headers = channel.secrets["header"]?.split(':', limit = 2)?.let { mapOf(it[0].trim() to it[1].trim()) }.orEmpty()
                val body = buildJsonObject {
                    put("source", "护肝宝"); deliveryId?.let { put("deliveryId", it) }; put("rule", ruleId); put("title", title); put("detail", detail); put("at", at)
                    jobId?.let { put("jobId", it) }
                }
                val (status, _) = http.post(channel.secrets.getValue("url"), headers, body.toString())
                if (status !in 200..299) throw ChannelFailure("对方返回 $status")
            }
            ChannelKind.EMAIL -> {
                val to = db.tx { verifiedEmail(channel.userId) } ?: throw ChannelFailure("账号没有已验证的邮箱")
                mailer.send(to, subject, text)
            }
            // Shown by the app; a device that is not connected is tried again like any other channel.
            ChannelKind.LOCAL -> if (!push(channel.config.getValue("device"), DeviceNotice(noticeId, title, detail, at))) throw ChannelFailure("设备不在线")
        }
    }
    /** These services answer 200 even when they refuse; the reply's own code says whether it was accepted. */
    private fun expectCode(reply: Pair<Int, String>, field: String, ok: Int, service: String) {
        val (status, body) = reply
        if (status !in 200..299) throw ChannelFailure("$service 返回 $status")
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = parsed?.get(field)?.jsonPrimitive?.intOrNull
        if (code != ok) {
            // Only the official services' explanation is shown; a self-hosted Bark server's reply is not echoed back.
            val why = if (service == "Bark") null else parsed?.get("errmsg")?.jsonPrimitive?.contentOrNull?.take(120)
            throw ChannelFailure("$service 拒绝了" + (why?.let { "：$it" } ?: if (code != null) "（代码 $code）" else ""))
        }
    }

    private fun seal(secrets: Map<String, String>): ByteArray {
        val iv = Secrets.bytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(json.encodeToString(stringMap, secrets).toByteArray())
    }
    private fun open(sealed: ByteArray): Map<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
        return json.decodeFromString(stringMap, String(cipher.doFinal(sealed, 12, sealed.size - 12)))
    }

    companion object {
        const val MAX_CHANNELS = 20
        private const val PER_USER_ROUND = 3
        private const val MINUTE = 60_000L
        private val RETRY_MINUTES = listOf(1L, 5L, 15L)
        private const val BARK = "https://api.day.app"
        private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "expect", "upgrade", "te",
            "trailer", "keep-alive", "proxy-authorization", "proxy-connection", "cookie")

        /** [url] as an address the server may post to: http(s), to a host that resolves outside reserved ranges unless [allowPrivate]. */
        fun publicAddress(url: String, allowPrivate: Boolean): URI {
            val uri = runCatching { URI(url) }.getOrNull() ?: throw ChannelFailure("地址无效")
            if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) throw ChannelFailure("只能发到 http 或 https 地址")
            allowed(uri.host, allowPrivate)
            return uri
        }
        /** The addresses of [host] the server may connect to; refuses the host if any of them is reserved. */
        fun allowed(host: String, allowPrivate: Boolean): List<InetAddress> {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { throw ChannelFailure("找不到 $host") }
            if (!allowPrivate && addresses.any(::reserved)) throw ChannelFailure("不能发到内网或本机地址")
            return addresses
        }
        private fun reserved(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) return true
            val b = address.address.map { it.toInt() and 0xff }
            return when (address) {
                is Inet4Address -> b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 198 && b[1] in 18..19) || b[0] >= 240 || (b[0] == 192 && b[1] == 0 && b[2] == 0)
                is Inet6Address -> (b[0] and 0xfe) == 0xfc ||                                        // unique local fc00::/7
                    (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) ||                    // NAT64 64:ff9b::/96
                    (b.subList(0, 12).all { it == 0 } && b.subList(12, 16).any { it != 0 }) ||         // IPv4-compatible ::a.b.c.d
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)                       // documentation
                else -> true
            }
        }
    }
}
