package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.github.yysassist.core.content.ConfigurationField
import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.content.TaskDefinition
import io.github.yysassist.core.content.validateConfiguration
import io.github.yysassist.protocol.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID

class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
enum class SessionKind { WEB, DEVICE }
data class Caller(val userId: String, val sessionId: String, val kind: SessionKind, val deviceId: String?)
data class IssuedSession(val userId: String, val email: String?, val accessToken: String, val refreshToken: String?, val accessExpiresAt: Long)

/** All business rules. Every query that reads user data is scoped by the caller's user id. */
class CloudService(private val db: Database, private val mailer: Mailer, val github: GitHubIdentity, private val secret: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis, val events: ConsoleEvents = ConsoleEvents(), val sockets: DeviceSockets = DeviceSockets()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tasks = ListSerializer(CloudTask.serializer())
    private val apps = ListSerializer(AppStatus.serializer())
    private val loginLimit = RateLimiter(10, 10 * MINUTE, clock)
    private val mailLimit = RateLimiter(5, 10 * MINUTE, clock)
    private val badCredentials get() = ApiException(401, "invalid_credentials", "邮箱或密码不正确")
    private val badCode get() = ApiException(401, "invalid_code", "验证码不正确或已过期")

    private fun email(value: String): String = value.trim().lowercase().takeIf { it.length in 3..254 && EMAIL.matches(it) }
        ?: throw ApiException(400, "invalid_email", "邮箱格式不正确")
    private fun password(value: String) = value.takeIf { it.length in 10..200 } ?: throw ApiException(400, "weak_password", "密码长度需要 10 到 200 位")
    private fun limited(limiter: RateLimiter, key: String) { if (!limiter.allow(key)) throw ApiException(429, "rate_limited", "操作太频繁，请稍后再试") }
    private fun codeHash(address: String, purpose: String, code: String) = Secrets.hmac(secret, "$purpose:$address:$code")

    private fun sendCode(address: String, purpose: String, subject: String) {
        val code = Secrets.code()
        db.tx { update("INSERT OR REPLACE INTO email_codes (email, purpose, code_hash, expires_at, attempts) VALUES (?, ?, ?, ?, 0)",
            address, purpose, codeHash(address, purpose, code), clock() + CODE_LIFETIME) }
        mailer.send(address, subject, "你的验证码是 $code，10 分钟内有效。如果不是你本人操作，请忽略这封邮件。")
    }
    /** A code works once, and a few wrong guesses burn it. */
    private fun consumeCode(address: String, purpose: String, code: String) = db.tx {
        val stored = single("SELECT code_hash, expires_at, attempts FROM email_codes WHERE email = ? AND purpose = ?", address, purpose) {
            Triple(it.getString(1), it.getLong(2), it.getInt(3)) } ?: throw badCode
        if (stored.second < clock() || stored.third >= MAX_CODE_ATTEMPTS) { update("DELETE FROM email_codes WHERE email = ? AND purpose = ?", address, purpose); return@tx false }
        if (!Secrets.same(stored.first, codeHash(address, purpose, code.trim()))) {
            update("UPDATE email_codes SET attempts = attempts + 1 WHERE email = ? AND purpose = ?", address, purpose); return@tx false }
        update("DELETE FROM email_codes WHERE email = ? AND purpose = ?", address, purpose); true
    }.also { if (!it) throw badCode }

    private fun issue(userId: String, emailAddress: String?, kind: SessionKind): IssuedSession = db.tx {
        val access = Secrets.token(); val refresh = if (kind == SessionKind.DEVICE) Secrets.token() else null
        val expires = clock() + if (kind == SessionKind.DEVICE) DEVICE_ACCESS_LIFETIME else WEB_LIFETIME
        update("INSERT INTO sessions (id, user_id, kind, access_hash, refresh_hash, access_expires_at, refresh_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), userId, kind.name, Secrets.sha256(access), refresh?.let(Secrets::sha256), expires, refresh?.let { clock() + REFRESH_LIFETIME })
        IssuedSession(userId, emailAddress, access, refresh, expires)
    }

    /** The reply is identical for new and existing addresses, so it cannot be used to find accounts. */
    fun register(request: RegisterRequest) {
        val address = email(request.email); password(request.password); limited(mailLimit, "register:$address")
        val verified = db.tx { single("SELECT email_verified FROM users WHERE email = ?", address) { it.getInt(1) == 1 } }
        if (verified == true) { mailer.send(address, "账号已存在", "这个邮箱已经注册。如果忘记密码，可以在登录页重置。"); return }
        val hash = Passwords.hash(request.password)
        db.tx { if (verified == null) update("INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), address, hash, clock())
            else update("UPDATE users SET password_hash = ? WHERE email = ?", hash, address) }
        sendCode(address, PURPOSE_VERIFY, "验证你的邮箱")
    }
    fun verifyEmail(request: VerifyEmailRequest, kind: SessionKind): IssuedSession {
        val address = email(request.email); limited(loginLimit, "verify:$address"); consumeCode(address, PURPOSE_VERIFY, request.code)
        val id = db.tx { update("UPDATE users SET email_verified = 1 WHERE email = ?", address); single("SELECT id FROM users WHERE email = ?", address) { it.getString(1) } } ?: throw badCode
        return issue(id, address, kind)
    }
    fun loginWithPassword(request: PasswordLoginRequest, kind: SessionKind): IssuedSession {
        val address = email(request.email); limited(loginLimit, "login:$address")
        val user = db.tx { single("SELECT id, password_hash, email_verified FROM users WHERE email = ?", address) { Triple(it.getString(1), it.getString(2), it.getInt(3) == 1) } }
        if (!Passwords.verify(request.password, user?.second) || user == null || !user.third) throw badCredentials
        return issue(user.first, address, kind)
    }
    fun requestLoginCode(request: CodeRequest) {
        val address = email(request.email); limited(mailLimit, "code:$address")
        if (db.tx { single("SELECT 1 FROM users WHERE email = ? AND email_verified = 1", address) { true } } == true) sendCode(address, PURPOSE_LOGIN, "登录验证码")
    }
    fun loginWithCode(request: CodeLoginRequest, kind: SessionKind): IssuedSession {
        val address = email(request.email); limited(loginLimit, "code-login:$address"); consumeCode(address, PURPOSE_LOGIN, request.code)
        val id = db.tx { single("SELECT id FROM users WHERE email = ? AND email_verified = 1", address) { it.getString(1) } } ?: throw badCode
        return issue(id, address, kind)
    }
    fun requestPasswordReset(request: CodeRequest) {
        val address = email(request.email); limited(mailLimit, "reset:$address")
        if (db.tx { single("SELECT 1 FROM users WHERE email = ? AND email_verified = 1", address) { true } } == true) sendCode(address, PURPOSE_RESET, "重置密码")
    }
    /** Every signed-in browser and device has to sign in again afterwards. */
    fun resetPassword(request: PasswordResetRequest) {
        val address = email(request.email); password(request.password); limited(loginLimit, "reset-confirm:$address"); consumeCode(address, PURPOSE_RESET, request.code)
        val hash = Passwords.hash(request.password)
        db.tx { update("UPDATE users SET password_hash = ? WHERE email = ?", hash, address)
            update("DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE email = ?)", address) }
    }
    /** Refresh tokens rotate: the old pair stops working the moment a new one is issued. */
    fun refresh(request: RefreshRequest): IssuedSession = db.tx {
        val row = single("SELECT s.id, s.user_id, s.refresh_expires_at, u.email FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.refresh_hash = ?", Secrets.sha256(request.refreshToken)) {
            listOf(it.getString(1), it.getString(2), it.getLong(3), it.getString(4)) } ?: throw ApiException(401, "invalid_session", "登录已失效，请重新登录")
        if ((row[2] as Long) < clock()) { update("DELETE FROM sessions WHERE id = ?", row[0]); throw ApiException(401, "invalid_session", "登录已失效，请重新登录") }
        val access = Secrets.token(); val refresh = Secrets.token(); val expires = clock() + DEVICE_ACCESS_LIFETIME
        update("UPDATE sessions SET access_hash = ?, refresh_hash = ?, access_expires_at = ?, refresh_expires_at = ? WHERE id = ?",
            Secrets.sha256(access), Secrets.sha256(refresh), expires, clock() + REFRESH_LIFETIME, row[0])
        IssuedSession(row[1] as String, row[3] as String?, access, refresh, expires)
    }
    fun authenticate(token: String): Caller? = db.tx {
        single("SELECT id, user_id, kind, device_id, access_expires_at FROM sessions WHERE access_hash = ?", Secrets.sha256(token)) { row ->
            Caller(row.getString(2), row.getString(1), SessionKind.valueOf(row.getString(3)), row.getString(4)).takeIf { row.getLong(5) >= clock() } }
    }
    fun logout(caller: Caller) = db.tx { update("DELETE FROM sessions WHERE id = ? AND user_id = ?", caller.sessionId, caller.userId); Unit }
    fun account(caller: Caller): AccountView = db.tx {
        val user = single("SELECT email, password_hash FROM users WHERE id = ?", caller.userId) { it.getString(1) to (it.getString(2) != null) } ?: throw ApiException(401, "invalid_session", "登录已失效")
        AccountView(caller.userId, user.first, user.second, single("SELECT login FROM identities WHERE provider = 'github' AND user_id = ?", caller.userId) { it.getString(1) },
            zoneOf(caller.userId))
    }

    fun githubStart(redirectUri: String, linkUser: String?): String {
        val clientId = github.clientId ?: throw ApiException(503, "github_unavailable", "GitHub 登录未配置")
        val state = Secrets.token(); val verifier = Secrets.token()
        db.tx { update("DELETE FROM oauth_states WHERE expires_at < ?", clock())
            update("INSERT INTO oauth_states (state, verifier, link_user, expires_at) VALUES (?, ?, ?, ?)", state, verifier, linkUser, clock() + CODE_LIFETIME) }
        fun e(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)
        return "https://github.com/login/oauth/authorize?client_id=${e(clientId)}&redirect_uri=${e(redirectUri)}&state=${e(state)}&code_challenge=${e(Secrets.challenge(verifier))}&code_challenge_method=S256"
    }
    fun githubCallback(code: String, state: String, redirectUri: String): IssuedSession? {
        val saved = db.tx { val row = single("SELECT verifier, link_user, expires_at FROM oauth_states WHERE state = ?", state) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
            update("DELETE FROM oauth_states WHERE state = ?", state); row } ?: return null
        if (saved.third < clock()) return null
        val user = github.exchange(code, saved.first, redirectUri)?.let(github::user) ?: return null
        return signInWithGitHub(user, saved.second, SessionKind.WEB)
    }
    fun loginWithGitHubToken(request: GitHubTokenRequest): IssuedSession {
        val user = github.user(request.accessToken) ?: throw ApiException(401, "invalid_credentials", "GitHub 登录未通过")
        return signInWithGitHub(user, null, SessionKind.DEVICE)
    }
    /** Identities are never merged by matching addresses; linking needs the existing account to be signed in. */
    private fun signInWithGitHub(user: GitHubUser, linkUser: String?, kind: SessionKind): IssuedSession {
        val id = db.tx {
            val owner = single("SELECT user_id FROM identities WHERE provider = 'github' AND subject = ?", user.id) { it.getString(1) }
            if (owner != null && linkUser != null && owner != linkUser) throw ApiException(409, "identity_in_use", "这个 GitHub 账号已绑定其他用户")
            owner?.also { update("UPDATE identities SET login = ? WHERE provider = 'github' AND subject = ?", user.login, user.id) } ?: run {
                val target = linkUser ?: UUID.randomUUID().toString().also { update("INSERT INTO users (id, created_at) VALUES (?, ?)", it, clock()) }
                update("INSERT INTO identities (provider, subject, login, user_id) VALUES ('github', ?, ?, ?)", user.id, user.login, target); target
            }
        }
        return issue(id, db.tx { single("SELECT email FROM users WHERE id = ?", id) { it.getString(1) } }, kind)
    }
    /** At least one way to sign in must remain. */
    fun unlinkGitHub(caller: Caller) = db.tx {
        if (single("SELECT 1 FROM users WHERE id = ? AND email IS NOT NULL AND email_verified = 1", caller.userId) { true } != true)
            throw ApiException(409, "last_identity", "请先绑定邮箱，至少要保留一种登录方式")
        update("DELETE FROM identities WHERE provider = 'github' AND user_id = ?", caller.userId); Unit
    }

    fun registerDevice(caller: Caller, request: DeviceRegistration): DeviceRegistered {
        if (caller.kind != SessionKind.DEVICE) throw ApiException(403, "device_only", "只有 App 可以注册设备")
        if (request.installId.isBlank() || request.installId.length > 80 || request.name.isBlank() || request.name.length > 80) throw ApiException(400, "invalid_device", "设备信息无效")
        return db.tx {
            val id = single("SELECT id FROM devices WHERE user_id = ? AND install_id = ?", caller.userId, request.installId) { it.getString(1) } ?: UUID.randomUUID().toString().also {
                update("INSERT INTO devices (id, user_id, install_id, name, platform) VALUES (?, ?, ?, ?, ?)", it, caller.userId, request.installId, request.name, request.platform) }
            update("UPDATE devices SET revoked = 0, name = ?, platform = ? WHERE id = ? AND user_id = ?", request.name, request.platform, id, caller.userId)
            update("UPDATE sessions SET device_id = ? WHERE id = ? AND user_id = ?", id, caller.sessionId, caller.userId)
            DeviceRegistered(id, caller.userId)
        }.also { pushDevice(caller.userId, it.deviceId) }
    }
    private fun device(caller: Caller): String = caller.deviceId?.takeIf { caller.kind == SessionKind.DEVICE } ?: throw ApiException(403, "device_only", "请先注册设备")

    /** Settles expired leases and returns the users whose jobs changed, so their consoles can be told. */
    private fun Database.sweep(): Set<String> {
        val now = clock()
        val touched = query("SELECT DISTINCT user_id FROM jobs WHERE state IN ('LEASED', 'RUNNING') AND lease_expires_at < ?", now) { it.getString(1) }.toSet()
        if (touched.isEmpty()) return touched
        // Never acknowledged, so nothing ran: offer it again, unless it was cancelled meanwhile. Once started the outcome
        // is unknown and is never retried.
        update("UPDATE jobs SET state = 'CANCELLED', detail = '已取消', lease_expires_at = NULL, updated_at = ? WHERE state = 'LEASED' AND cancel_requested = 1 AND lease_expires_at < ?", now, now)
        update("UPDATE jobs SET state = 'QUEUED', lease_expires_at = NULL, updated_at = ? WHERE state = 'LEASED' AND lease_expires_at < ?", now, now)
        update("UPDATE jobs SET state = 'UNKNOWN', detail = '设备失联，结果未确认', lease_expires_at = NULL, updated_at = ? WHERE state = 'RUNNING' AND lease_expires_at < ?", now, now)
        return touched
    }
    /**
     * Run every few seconds: fires plans whose time has come, settles expired leases, gives waiting jobs to apps and
     * sends each idle connected device its next job.
     */
    fun tick() {
        val now = clock()
        val touched = db.tx { firePlans(now) + sweep() + assignWaiting(now) }
        touched.forEach(::pushJobs)
        dispatchAll()
    }

    /**
     * Each enabled plan fires once per scheduled time, in the user's time zone. A time missed by more than the plan's
     * latest start (the server was down) is recorded as skipped. A plan never fires for a time before it was saved.
     */
    private fun Database.firePlans(now: Long): Set<String> {
        val touched = mutableSetOf<String>()
        val due = query("""SELECT p.id, p.user_id, p.account_id, p.name, p.days_mask, p.minute_of_day, p.tasks_json, p.device_ids_json,
            p.latest_start_minutes, p.fired_up_to, u.time_zone FROM plans p JOIN users u ON u.id = p.user_id WHERE p.enabled = 1""") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getInt(6), it.getString(7), it.getString(8),
                it.getInt(9), it.getLong(10), it.getString(11)) }
        for (row in due) {
            val zone = runCatching { java.time.ZoneId.of(row[10] as String) }.getOrDefault(java.time.ZoneId.of(DEFAULT_ZONE))
            val schedule = io.github.yysassist.core.plan.PlanSchedule(io.github.yysassist.core.plan.PlanSchedule.daysOf(row[4] as Int), row[5] as Int)
            val slot = schedule.previous(java.time.Instant.ofEpochMilli(now).atZone(zone)).toInstant().toEpochMilli()
            if (slot <= row[9] as Long) continue
            val (planId, userId, accountId) = Triple(row[0] as String, row[1] as String, row[2] as String)
            update("UPDATE plans SET fired_up_to = ? WHERE id = ?", slot, planId)
            val latest = slot + (row[8] as Int) * MINUTE
            insertPlanJobs(userId, planId, row[3] as String, accountId, row[6] as String, row[7] as String, now, latest,
                "错过了计划时间：服务端当时没有运行".takeIf { now > latest })
            touched += userId
        }
        return touched
    }
    /** One waiting job per task of a plan, with the account's settings; a task whose settings are incomplete is skipped. */
    private fun Database.insertPlanJobs(userId: String, planId: String, planName: String, accountId: String, tasksJson: String, devicesJson: String,
        now: Long, latest: Long, missed: String?) {
        val account = single("SELECT name FROM game_accounts WHERE id = ?", accountId) { it.getString(1) }
        json.decodeFromString(tasks, tasksJson).forEachIndexed { index, task ->
            val (values, _) = savedConfig(accountId, task.targetId, task.taskId)
            val issue = missed ?: runCatching { latestTask(userId, task.targetId, task.taskId).second }.getOrNull()
                ?.let { validateConfiguration(it, values) }?.takeIf { it.isNotEmpty() }?.let { "「$account」的「${task.name}」设置不完整：" + it.joinToString("；") { issue -> issue.reason } }
            update("""INSERT INTO jobs (id, user_id, target_id, task_id, task_name, state, detail, created_at, updated_at, config_json, account_id, account_name,
                plan_id, plan_name, allowed_devices_json, latest_start_at, sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID().toString(), userId, task.targetId, task.taskId, task.name, if (issue == null) "WAITING" else "SKIPPED", issue.orEmpty(),
                now, now, values.toString(), accountId, account, planId, planName, devicesJson, latest, index)
        }
    }
    /** One extra run of a plan now, given to apps the same way as a timed one; the schedule is unchanged. */
    fun runPlan(caller: Caller, planId: String) {
        db.tx {
            val plan = single("SELECT name, account_id, tasks_json, device_ids_json FROM plans WHERE id = ? AND user_id = ?", planId, caller.userId) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) } ?: throw ApiException(404, "not_found", "计划不存在")
            val now = clock()
            insertPlanJobs(caller.userId, planId, plan[0], plan[1], plan[2], plan[3], now, now + IMMEDIATE_WAIT, null)
        }
        pushJobs(caller.userId)
        assignNow()
    }

    /**
     * Gives each waiting job to an online app signed in to its account, among the devices it may use. Switching
     * accounts is not supported yet, so a job no such app can take waits, and is skipped after its latest start.
     */
    private fun Database.assignWaiting(now: Long): Set<String> {
        val touched = mutableSetOf<String>()
        val waiting = query("""SELECT id, user_id, target_id, task_id, account_id, allowed_devices_json, latest_start_at FROM jobs
            WHERE state = 'WAITING' ORDER BY created_at, sequence, id""") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getLong(7)) }
        val devicesOf = HashMap<String, List<DeviceView>>()
        for (job in waiting) {
            val (id, userId) = job[0] as String to job[1] as String
            if (now > job[6] as Long) {
                update("UPDATE jobs SET state = 'SKIPPED', detail = ?, updated_at = ? WHERE id = ?", "到最晚开始时间仍没有登录着这个账号的在线应用", now, id)
                touched += userId; continue
            }
            val allowed = json.decodeFromString(idList, job[5] as String).toSet()
            val devices = devicesOf.getOrPut(userId) { deviceRows(userId, null) }
            val target = devices.asSequence().filter { it.online && (allowed.isEmpty() || it.id in allowed) && it.tasks.any { t -> t.targetId == job[2] && t.taskId == job[3] } }
                .flatMap { device -> device.apps.filter { it.gameId == job[2] && it.accountId != null && it.accountId == job[4] }.map { device to it } }
                // A device already holding this account's jobs keeps getting them, so one run's tasks stay in order on one
                // device; otherwise the least busy one.
                .minWithOrNull(compareBy({ (device, _) -> if (single("SELECT 1 FROM jobs WHERE device_id = ? AND account_id = ? AND state IN ('QUEUED', 'LEASED', 'RUNNING') LIMIT 1",
                    device.id, job[4]) { true } == true) 0 else 1 }, { (device, _) -> single("SELECT COUNT(*) FROM jobs WHERE device_id = ? AND state IN ('QUEUED', 'LEASED', 'RUNNING')", device.id) { it.getInt(1) } ?: 0 }))
                ?: continue
            update("UPDATE jobs SET state = 'QUEUED', device_id = ?, app_key = ?, updated_at = ? WHERE id = ?", target.first.id, target.second.key, now, id)
            touched += userId
        }
        return touched
    }

    /**
     * Sends [deviceId] its next queued job when it is connected and has none in hand. A job past its latest start is
     * skipped; one whose settings no longer fit the device's task version fails without being sent.
     */
    private fun dispatch(deviceId: String) {
        if (!sockets.isOnline(deviceId)) return
        val now = clock()
        val (lease, userId) = db.tx {
            val owner = single("SELECT user_id FROM devices WHERE id = ? AND revoked = 0", deviceId) { it.getString(1) } ?: return@tx null to null
            if (single("SELECT 1 FROM jobs WHERE device_id = ? AND state IN ('LEASED', 'RUNNING') LIMIT 1", deviceId) { true } == true) return@tx null to null
            // A device held on its own (paused there, or waiting for a step to be checked) would only hold the job too.
            val held = single("SELECT phase FROM devices WHERE id = ?", deviceId) { it.getString(1) } in HELD_PHASES
            var touched = false
            while (true) {
                // An account runs on one device at a time: its job waits while another device has the account's job in hand,
                // or has an older one of the account still queued, so the device that just finished cannot keep the account.
                val job = single("""SELECT id, target_id, task_id, lease_no, config_json, latest_start_at FROM jobs j WHERE device_id = ? AND state = 'QUEUED'
                    AND NOT EXISTS (SELECT 1 FROM jobs o WHERE o.account_id = j.account_id AND o.device_id <> j.device_id AND (o.state IN ('LEASED', 'RUNNING')
                        OR (o.state = 'QUEUED' AND (o.created_at < j.created_at OR (o.created_at = j.created_at AND o.sequence < j.sequence)))))
                    ORDER BY created_at, sequence, id LIMIT 1""", deviceId) {
                    listOf(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getString(5), it.getLong(6).takeUnless { _ -> it.wasNull() }) }
                    ?: return@tx null to owner.takeIf { touched }
                val jobId = job[0] as String
                val latest = job[5] as Long?
                if (latest != null && now > latest) {
                    update("UPDATE jobs SET state = 'SKIPPED', detail = '到最晚开始时间还没轮到', updated_at = ? WHERE id = ?", now, jobId); touched = true; continue
                }
                if (held) return@tx null to owner.takeIf { touched }
                val values = json.parseToJsonElement(job[4] as String).jsonObject
                val checked = runCatching { deviceTask(owner, deviceId, job[1] as String, job[2] as String) }
                val issue = checked.exceptionOrNull()?.message ?: validateConfiguration(checked.getOrThrow().second, values).takeIf { it.isNotEmpty() }
                    ?.joinToString("；") { it.reason }?.let { "设置与设备的任务版本不符：$it" }
                if (issue != null) {
                    update("UPDATE jobs SET state = 'FAILED', detail = ?, updated_at = ? WHERE id = ?", issue.take(500), now, jobId); touched = true; continue
                }
                val digest = checked.getOrThrow().first
                val version = single("SELECT version FROM catalogs WHERE user_id = ? AND digest = ?", owner, digest) { it.getString(1) }.orEmpty()
                val leaseNo = (job[3] as Long) + 1; val expires = now + ACK_LIFETIME
                update("UPDATE jobs SET state = 'LEASED', lease_no = ?, lease_expires_at = ?, version = ?, digest = ?, updated_at = ? WHERE id = ?",
                    leaseNo, expires, version, digest, now, jobId)
                return@tx JobLease(jobId, leaseNo, owner, deviceId, job[1] as String, job[2] as String, expires, version, digest, values) to owner
            }
            @Suppress("UNREACHABLE_CODE") null to null
        }
        if (lease != null && !sockets.send(deviceId, encode(ServerMessage.Job(lease)))) {
            // Not delivered: the lease expires unacknowledged and the job is offered again.
        }
        userId?.let(::pushJobs)
    }
    private fun dispatchAll() {
        db.tx { query("SELECT DISTINCT device_id FROM jobs WHERE state = 'QUEUED' AND device_id IS NOT NULL") { it.getString(1) } }.forEach(::dispatch)
    }

    /**
     * A device's report over its socket. Returns what the server has to tell it back: whether its task bundle is
     * missing here, and which of its jobs were cancelled.
     */
    fun status(caller: Caller, status: DeviceStatus): List<ServerMessage> {
        val id = device(caller); val now = clock()
        val (replies, swept) = db.tx {
            update("""UPDATE devices SET last_seen_at = ?, phase = ?, current_task = ?, queued = ?, tasks_json = ?, catalog_version = ?, catalog_digest = ?,
                apps_json = ?, concurrency = ? WHERE id = ? AND user_id = ?""",
                now, status.phase.take(40), status.currentTask?.take(120), status.queued, json.encodeToString(tasks, status.tasks.take(500).map { it.copy(targetId = it.targetId.take(80), taskId = it.taskId.take(80), name = it.name.take(80)) }),
                status.catalog?.version?.take(120), status.catalog?.digest?.take(64), json.encodeToString(apps, status.apps.take(MAX_APPS).map(::bounded)),
                status.concurrency.coerceIn(1, MAX_APPS), id, caller.userId)
            val reported = status.catalog
            val missing = reported != null && single("SELECT 1 FROM catalogs WHERE user_id = ? AND digest = ?", caller.userId, reported.digest) { true } == null
            // Only a started job is kept alive: a lease handed to a connection that died must return to the queue.
            update("UPDATE jobs SET lease_expires_at = ? WHERE device_id = ? AND user_id = ? AND state = 'RUNNING'", now + LEASE_LIFETIME, id, caller.userId)
            val swept = sweep()
            val cancelled = query("SELECT id FROM jobs WHERE device_id = ? AND user_id = ? AND cancel_requested = 1 AND state IN ('LEASED', 'RUNNING')", id, caller.userId) { it.getString(1) }
            // The wanted pause state goes back with every report, so one sent while the run had not started, or lost
            // with a dropped socket, still arrives. The device acts only on a change.
            val running = query("SELECT id, pause_requested FROM jobs WHERE device_id = ? AND user_id = ? AND state = 'RUNNING' AND cancel_requested = 0", id, caller.userId) {
                if (it.getInt(2) == 1) ServerMessage.Pause(it.getString(1)) else ServerMessage.Resume(it.getString(1)) }
            listOfNotNull(ServerMessage.CatalogNeeded.takeIf { missing }, cancelled.takeIf { it.isNotEmpty() }?.let(ServerMessage::Cancel)) + running to swept
        }
        pushDevice(caller.userId, id)
        swept.forEach(::pushJobs)
        dispatch(id)
        return replies
    }
    private fun bounded(app: AppStatus) = app.copy(key = app.key.take(120), gameId = app.gameId.take(80), name = app.name.take(80), phase = app.phase.take(40),
        currentTask = app.currentTask?.take(120))
    /** Called when a device's socket opens or closes; the console shows it as online only while connected. */
    fun connectionChanged(userId: String, deviceId: String) {
        // What the device was doing before is stale until its first report on this connection; nothing is sent till then.
        if (sockets.isOnline(deviceId)) db.tx { update("UPDATE devices SET last_seen_at = ?, phase = 'CONNECTING' WHERE id = ? AND user_id = ?", clock(), deviceId, userId) }
        pushDevice(userId, deviceId)
        if (sockets.isOnline(deviceId)) { pushDeviceData(userId, only = deviceId); assignNow(); dispatch(deviceId) }
    }
    /** Waiting jobs may fit a device that just came online or an app whose account was just set. */
    private fun assignNow() { db.tx { assignWaiting(clock()) }.forEach(::pushJobs); dispatchAll() }

    // Each push reads and publishes inside one transaction. Transactions run one at a time, so events leave in the
    // order their data was read, and a later event never carries older data than an earlier one.
    /** The last device payload sent, without its last-seen time, so a report that changes nothing else is not sent. */
    private val lastDevicePayload = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun Database.publishDevice(userId: String, view: DeviceView) {
        val quiet = json.encodeToString(DeviceView.serializer(), view.copy(lastSeenAt = null))
        if (lastDevicePayload.put(view.id, quiet) == quiet) return
        events.publish(userId, "device", json.encodeToString(DeviceView.serializer(), view))
    }
    private fun pushDevice(userId: String, deviceId: String) = db.tx { deviceRows(userId, deviceId).singleOrNull()?.let { publishDevice(userId, it) } }
    private fun pushJobs(userId: String) { db.tx { events.publish(userId, "jobs", json.encodeToString(jobList, jobRows(userId))) }; pushDeviceData(userId) }

    /** What [deviceId] shows of the user's accounts, settings and plans. */
    private fun Database.deviceData(userId: String, deviceId: String): DeviceData {
        val configs = query("SELECT account_id, target_id, task_id, values_json, revision FROM task_configs WHERE user_id = ? ORDER BY account_id, target_id, task_id", userId) {
            AccountConfig(it.getString(1), it.getString(2), it.getString(3), json.parseToJsonElement(it.getString(4)).jsonObject, it.getLong(5)) }
        // A plan's latest run is the jobs it made at its latest firing.
        val plans = planRows(userId)
        val runs = plans.mapNotNull { plan ->
            val at = single("SELECT created_at FROM jobs WHERE plan_id = ? ORDER BY created_at DESC LIMIT 1", plan.id) { it.getLong(1) } ?: return@mapNotNull null
            val rows = query("SELECT state, detail FROM jobs WHERE plan_id = ? AND created_at = ? ORDER BY sequence", plan.id, at) { JobState.valueOf(it.getString(1)) to it.getString(2) }
            PlanRun(plan.id, at, rows.map { it.first }, rows.map { it.second }.firstOrNull { it.isNotEmpty() }.orEmpty())
        }
        // What the apps are doing changes all the time and the device knows it already; only their accounts are sent.
        val apps = deviceRows(userId, deviceId).singleOrNull()?.apps.orEmpty().map { it.copy(phase = "", currentTask = null, queued = 0) }
        return DeviceData(accountRows(userId), apps, plans, configs, runs, zoneOf(userId))
    }
    /** The last data each device was sent, so an unchanged copy is not sent again. */
    private val lastDeviceData = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Sends each of the user's connected devices its data when it changed. */
    private fun pushDeviceData(userId: String, only: String? = null) {
        val devices = db.tx { query("SELECT id FROM devices WHERE user_id = ? AND revoked = 0", userId) { it.getString(1) } }
            .filter { (only == null || it == only) && sockets.isOnline(it) }
        // Read and sent in one transaction: transactions run one at a time, so a later push never carries older data.
        for (deviceId in devices) db.tx {
            val text = encode(ServerMessage.Data(deviceData(userId, deviceId)))
            if (lastDeviceData.put(deviceId, text) != text || only != null) sockets.send(deviceId, text)
        }
    }
    /**
     * What a console shows first, and again whenever it cannot catch up from the event log, with the id of the last
     * event it already covers.
     */
    fun consoleSnapshotUpTo(userId: String): Pair<ConsoleSnapshot, Long> = db.tx {
        ConsoleSnapshot(deviceRows(userId, null), jobRows(userId), accountRows(userId), planRows(userId), gameRows(userId), zoneOf(userId)) to events.latest(userId)
    }
    private fun Database.zoneOf(userId: String) = single("SELECT time_zone FROM users WHERE id = ?", userId) { it.getString(1) } ?: DEFAULT_ZONE
    fun encode(snapshot: ConsoleSnapshot) = json.encodeToString(ConsoleSnapshot.serializer(), snapshot)
    /** The bundle text must hash to the digest it claims, so a stored version can never be swapped later. */
    fun uploadCatalog(caller: Caller, upload: CatalogUpload) {
        device(caller)
        if (upload.bundle.length > MAX_CATALOG_CHARS) throw ApiException(413, "catalog_too_large", "任务包过大")
        if (hex(upload.bundle) != upload.digest) throw ApiException(400, "digest_mismatch", "任务包摘要不一致")
        val bundle = try { bundles.decodeFromString(TaskBundle.serializer(), upload.bundle) }
            catch (_: IllegalArgumentException) { throw ApiException(400, "invalid_catalog", "任务包无法解析") }
        if (bundle.version != upload.version) throw ApiException(400, "invalid_catalog", "任务包版本不一致")
        val added = db.tx { update("INSERT OR IGNORE INTO catalogs (user_id, digest, version, bundle_json, uploaded_at) VALUES (?, ?, ?, ?, ?)",
            caller.userId, upload.digest, upload.version, upload.bundle, clock()) }
        if (added > 0) pushGames(caller.userId)
    }
    /** Stored bundles never change, so each is parsed once and kept while it is in use. */
    private val parsed = object : LinkedHashMap<String, TaskBundle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TaskBundle>) = size > MAX_PARSED_BUNDLES
    }
    private fun bundle(digest: String, text: () -> String): TaskBundle = synchronized(parsed) { parsed[digest] }
        ?: bundles.decodeFromString(TaskBundle.serializer(), text()).also { synchronized(parsed) { parsed[digest] = it } }
    private fun Database.catalog(userId: String, digest: String): TaskBundle? =
        single("SELECT 1 FROM catalogs WHERE user_id = ? AND digest = ?", userId, digest) { true }?.let {
            bundle(digest) { single("SELECT bundle_json FROM catalogs WHERE user_id = ? AND digest = ?", userId, digest) { row -> row.getString(1) }!! } }
    /** The task as defined by the version the device currently runs. */
    private fun Database.deviceTask(userId: String, deviceId: String, targetId: String, taskId: String): Pair<String, TaskDefinition> {
        val digest = single("SELECT catalog_digest FROM devices WHERE id = ? AND user_id = ? AND revoked = 0", deviceId, userId) { it.getString(1) to true }
            ?: throw ApiException(404, "not_found", "设备不存在")
        val current = digest.first ?: throw ApiException(409, "catalog_missing", "设备还没有上报任务版本，请等设备在线后再试")
        val bundle = catalog(userId, current) ?: throw ApiException(409, "catalog_missing", "设备的任务版本还没有上传，请稍后再试")
        val task = bundle.tasks.singleOrNull { it.targetId == targetId && it.id == taskId } ?: throw ApiException(400, "unknown_task", "这台设备没有这个任务")
        return current to task
    }
    private fun Database.savedConfig(accountId: String, targetId: String, taskId: String): Pair<JsonObject, Long> =
        single("SELECT values_json, revision FROM task_configs WHERE account_id = ? AND target_id = ? AND task_id = ?", accountId, targetId, taskId) {
            json.parseToJsonElement(it.getString(1)).jsonObject to it.getLong(2) } ?: (JsonObject(emptyMap()) to 0L)
    /** Bundles this user's devices uploaded, newest first. */
    private fun Database.catalogsNewestFirst(userId: String): List<Pair<String, TaskBundle>> =
        query("SELECT version, digest FROM catalogs WHERE user_id = ? ORDER BY uploaded_at DESC, digest LIMIT ?", userId, MAX_BUNDLES_READ) { it.getString(1) to it.getString(2) }
            .map { (version, digest) -> version to bundle(digest) { single("SELECT bundle_json FROM catalogs WHERE user_id = ? AND digest = ?", userId, digest) { it.getString(1) }!! } }
    /**
     * The task as the newest uploaded bundle defines it. Until each game has a chosen current version, this is what
     * settings are checked against.
     */
    private fun Database.latestTask(userId: String, targetId: String, taskId: String): Pair<String, TaskDefinition> =
        catalogsNewestFirst(userId).firstNotNullOfOrNull { (version, bundle) -> bundle.tasks.singleOrNull { it.targetId == targetId && it.id == taskId }?.let { version to it } }
            ?: throw ApiException(404, "unknown_task", "还没有设备上报过这个任务")
    private fun Database.account(userId: String, accountId: String): GameAccountView =
        single("SELECT id, game_id, name, uid, created_at FROM game_accounts WHERE id = ? AND user_id = ?", accountId, userId) {
            GameAccountView(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) } ?: throw ApiException(404, "not_found", "游戏账号不存在")
    fun config(caller: Caller, accountId: String, targetId: String, taskId: String): ConfigView = db.tx {
        val account = account(caller.userId, accountId)
        if (account.gameId != targetId) throw ApiException(400, "unknown_task", "这个任务不属于该账号的游戏")
        val (version, task) = latestTask(caller.userId, targetId, taskId)
        val (values, revision) = savedConfig(accountId, targetId, taskId)
        ConfigView(accountId, targetId, taskId, task.name, version, json.encodeToJsonElement(fieldList, task.fields) as JsonArray, values, revision)
    }
    /** Settings are checked by the same rules the app applies. */
    fun saveConfig(caller: Caller, accountId: String, targetId: String, taskId: String, save: ConfigSave): ConfigView {
        db.tx {
            val account = account(caller.userId, accountId)
            if (account.gameId != targetId) throw ApiException(400, "unknown_task", "这个任务不属于该账号的游戏")
            val (_, task) = latestTask(caller.userId, targetId, taskId)
            val issues = validateConfiguration(task, save.values)
            if (issues.isNotEmpty()) throw ApiException(400, "invalid_config", issues.joinToString("；") { it.reason })
            val (_, revision) = savedConfig(accountId, targetId, taskId)
            if (revision != save.revision) throw ApiException(409, "revision_changed", "设置已在别处修改，请刷新后再改")
            update("INSERT OR REPLACE INTO task_configs (user_id, account_id, target_id, task_id, values_json, revision, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                caller.userId, accountId, targetId, taskId, save.values.toString(), revision + 1, clock())
        }
        pushDeviceData(caller.userId)
        return config(caller, accountId, targetId, taskId)
    }

    /** Games the user's devices can run: each target of the newest bundle that has it, with its tasks that can be started. */
    private fun Database.gameRows(userId: String): List<GameView> {
        val seen = LinkedHashMap<String, GameView>()
        for ((_, bundle) in catalogsNewestFirst(userId)) for (target in bundle.targets) if (target.id !in seen)
            seen[target.id] = GameView(target.id, target.name, bundle.tasks.filter { it.targetId == target.id && it.trigger == null }.map { CloudTask(it.targetId, it.id, it.name) })
        return seen.values.toList()
    }
    fun games(caller: Caller): List<GameView> = db.tx { gameRows(caller.userId) }
    private fun Database.accountRows(userId: String): List<GameAccountView> =
        query("SELECT id, game_id, name, uid, created_at FROM game_accounts WHERE user_id = ? ORDER BY game_id, created_at, id", userId) {
            GameAccountView(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) }
    fun gameAccounts(caller: Caller): List<GameAccountView> = db.tx { accountRows(caller.userId) }
    private fun accountName(save: GameAccountSave) = save.name.trim().takeIf { it.length in 1..40 } ?: throw ApiException(400, "invalid_account", "账号名称需要 1 到 40 个字")
    private fun accountUid(save: GameAccountSave) = save.uid?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 40) throw ApiException(400, "invalid_account", "UID 太长") }
    fun createGameAccount(caller: Caller, save: GameAccountSave): GameAccountView {
        val name = accountName(save); val uid = accountUid(save); val id = UUID.randomUUID().toString()
        db.tx {
            if (gameRows(caller.userId).none { it.id == save.gameId }) throw ApiException(400, "unknown_game", "还没有设备上报过这个游戏")
            if (single("SELECT 1 FROM game_accounts WHERE user_id = ? AND game_id = ? AND name = ?", caller.userId, save.gameId, name) { true } == true)
                throw ApiException(409, "account_exists", "这个游戏已经有同名账号")
            update("INSERT INTO game_accounts (id, user_id, game_id, name, uid, created_at) VALUES (?, ?, ?, ?, ?, ?)", id, caller.userId, save.gameId, name, uid, clock())
        }
        pushAccounts(caller.userId)
        return db.tx { account(caller.userId, id) }
    }
    /** The game cannot change: settings and plans were made for it. */
    fun updateGameAccount(caller: Caller, accountId: String, save: GameAccountSave): GameAccountView {
        val name = accountName(save); val uid = accountUid(save)
        db.tx {
            val current = account(caller.userId, accountId)
            if (single("SELECT 1 FROM game_accounts WHERE user_id = ? AND game_id = ? AND name = ? AND id <> ?", caller.userId, current.gameId, name, accountId) { true } == true)
                throw ApiException(409, "account_exists", "这个游戏已经有同名账号")
            update("UPDATE game_accounts SET name = ?, uid = ? WHERE id = ? AND user_id = ?", name, uid, accountId, caller.userId)
            update("UPDATE jobs SET account_name = ? WHERE account_id = ? AND user_id = ?", name, accountId, caller.userId)
        }
        pushAccounts(caller.userId); pushJobs(caller.userId); pushAllDevices(caller.userId)
        return db.tx { account(caller.userId, accountId) }
    }
    /** Its settings and plans go with it; past jobs keep the account's name. */
    fun deleteGameAccount(caller: Caller, accountId: String) {
        db.tx { account(caller.userId, accountId); update("DELETE FROM game_accounts WHERE id = ? AND user_id = ?", accountId, caller.userId) }
        pushAccounts(caller.userId); pushPlans(caller.userId); pushJobs(caller.userId); pushAllDevices(caller.userId)
    }

    private fun Database.planRows(userId: String): List<PlanView> =
        query("""SELECT id, account_id, name, days_mask, minute_of_day, tasks_json, device_ids_json, latest_start_minutes, enabled, revision, updated_at
            FROM plans WHERE user_id = ? ORDER BY account_id, minute_of_day, created_at, id""", userId) {
            PlanView(it.getString(1), it.getString(2), it.getString(3), (1..7).filter { day -> it.getInt(4) and (1 shl (day - 1)) != 0 }, it.getInt(5),
                json.decodeFromString(tasks, it.getString(6)), json.decodeFromString(idList, it.getString(7)), it.getInt(8), it.getInt(9) == 1, it.getLong(10), it.getLong(11)) }
    fun plans(caller: Caller): List<PlanView> = db.tx { planRows(caller.userId) }
    /** Everything a plan names must exist and belong to the user; the account's game decides which tasks it can hold. */
    private fun Database.checkedPlan(userId: String, account: GameAccountView, save: PlanSave): Pair<Int, List<CloudTask>> {
        val issues = buildList {
            if (save.name.isBlank() || save.name.length > 40) add("计划名称需要 1 到 40 个字")
            if (save.days.isEmpty() || save.days.any { it !in 1..7 } || save.days.distinct().size != save.days.size) add("请选择运行周期")
            if (save.minuteOfDay !in 0 until 24 * 60) add("运行时间无效")
            if (save.tasks.isEmpty()) add("请至少添加一个任务")
            if (save.tasks.map { it.targetId to it.taskId }.distinct().size != save.tasks.size) add("计划中的任务不能重复")
            if (save.latestStartMinutes !in 1..24 * 60) add("最晚开始时间需要在 1 分钟到 24 小时之间")
        }
        if (issues.isNotEmpty()) throw ApiException(400, "invalid_plan", issues.joinToString("；"))
        val offered = gameRows(userId).singleOrNull { it.id == account.gameId }?.tasks.orEmpty()
        val chosen = save.tasks.map { wanted -> offered.singleOrNull { it.targetId == wanted.targetId && it.taskId == wanted.taskId }
            ?: throw ApiException(400, "unknown_task", "计划里有这个账号的游戏没有的任务") }
        val devices = query("SELECT id FROM devices WHERE user_id = ? AND revoked = 0", userId) { it.getString(1) }.toSet()
        if (!devices.containsAll(save.deviceIds)) throw ApiException(400, "unknown_device", "计划里有不存在的设备")
        return save.days.fold(0) { mask, day -> mask or (1 shl (day - 1)) } to chosen
    }
    fun createPlan(caller: Caller, accountId: String, save: PlanSave): PlanView {
        val id = UUID.randomUUID().toString(); val now = clock()
        db.tx {
            val (mask, chosen) = checkedPlan(caller.userId, account(caller.userId, accountId), save)
            // fired_up_to starts at now: a plan never fires for a time that had already passed when it was saved.
            update("""INSERT INTO plans (id, user_id, account_id, name, days_mask, minute_of_day, tasks_json, device_ids_json, latest_start_minutes, enabled, revision,
                created_at, updated_at, fired_up_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""", id, caller.userId, accountId, save.name.trim(), mask, save.minuteOfDay,
                json.encodeToString(tasks, chosen), json.encodeToString(idList, save.deviceIds.distinct()), save.latestStartMinutes, if (save.enabled) 1 else 0, now, now, now)
        }
        pushPlans(caller.userId)
        return plans(caller).first { it.id == id }
    }
    /** A save made from an older copy is refused rather than overwriting someone else's change. */
    fun updatePlan(caller: Caller, planId: String, save: PlanSave): PlanView {
        db.tx {
            val (accountId, revision, schedule) = single("SELECT account_id, revision, days_mask, minute_of_day, enabled FROM plans WHERE id = ? AND user_id = ?", planId, caller.userId) {
                Triple(it.getString(1), it.getLong(2), Triple(it.getInt(3), it.getInt(4), it.getInt(5) == 1)) } ?: throw ApiException(404, "not_found", "计划不存在")
            if (revision != save.revision) throw ApiException(409, "revision_changed", "计划已在别处修改，请刷新后再改")
            val (mask, chosen) = checkedPlan(caller.userId, account(caller.userId, accountId), save)
            val now = clock()
            // Only a new time, or turning the plan back on, starts it afresh; renaming it just after its time keeps that run.
            val rescheduled = schedule.first != mask || schedule.second != save.minuteOfDay || (save.enabled && !schedule.third)
            update("""UPDATE plans SET name = ?, days_mask = ?, minute_of_day = ?, tasks_json = ?, device_ids_json = ?, latest_start_minutes = ?, enabled = ?,
                revision = revision + 1, updated_at = ?, fired_up_to = CASE WHEN ? THEN MAX(fired_up_to, ?) ELSE fired_up_to END WHERE id = ? AND user_id = ?""", save.name.trim(), mask, save.minuteOfDay,
                json.encodeToString(tasks, chosen), json.encodeToString(idList, save.deviceIds.distinct()), save.latestStartMinutes, if (save.enabled) 1 else 0,
                now, if (rescheduled) 1 else 0, now, planId, caller.userId)
        }
        pushPlans(caller.userId)
        return plans(caller).first { it.id == planId }
    }
    fun deletePlan(caller: Caller, planId: String) {
        db.tx { if (update("DELETE FROM plans WHERE id = ? AND user_id = ?", planId, caller.userId) == 0) throw ApiException(404, "not_found", "计划不存在") }
        pushPlans(caller.userId)
    }

    /** The account must be of the app's game. Setting the signed-in account is the user's word until the app can read it. */
    fun setAppAccounts(caller: Caller, deviceId: String, appKey: String, save: AppAccounts) {
        db.tx {
            val app = deviceRows(caller.userId, deviceId).singleOrNull()?.apps?.singleOrNull { it.key == appKey } ?: throw ApiException(404, "not_found", "应用不存在")
            for (id in listOfNotNull(save.accountId, save.defaultAccountId))
                if (account(caller.userId, id).gameId != app.gameId) throw ApiException(400, "wrong_game", "这个账号不属于该应用的游戏")
            update("INSERT OR REPLACE INTO app_accounts (device_id, app_key, user_id, account_id, default_account_id) VALUES (?, ?, ?, ?, ?)",
                deviceId, appKey, caller.userId, save.accountId, save.defaultAccountId)
        }
        pushDevice(caller.userId, deviceId)
        pushDeviceData(caller.userId)
        assignNow()
    }
    fun timeZone(caller: Caller, save: TimeZoneSave) {
        val zone = runCatching { java.time.ZoneId.of(save.timeZone).id }.getOrNull() ?: throw ApiException(400, "invalid_time_zone", "时区无效")
        // The plans' times move with the zone; one that has already passed in the new zone does not fire again today.
        db.tx { update("UPDATE users SET time_zone = ? WHERE id = ?", zone, caller.userId)
            update("UPDATE plans SET fired_up_to = MAX(fired_up_to, ?) WHERE user_id = ?", clock(), caller.userId) }
        events.publish(caller.userId, "time-zone", json.encodeToString(TimeZoneSave.serializer(), TimeZoneSave(zone)))
        pushDeviceData(caller.userId)
    }
    private fun pushAccounts(userId: String) { db.tx { events.publish(userId, "accounts", json.encodeToString(accountList, accountRows(userId))) }; pushDeviceData(userId) }
    private fun pushPlans(userId: String) { db.tx { events.publish(userId, "plans", json.encodeToString(planList, planRows(userId))) }; pushDeviceData(userId) }
    private fun pushGames(userId: String) = db.tx { events.publish(userId, "games", json.encodeToString(gameList, gameRows(userId))) }
    private fun pushAllDevices(userId: String) = db.tx { deviceRows(userId, null).forEach { publishDevice(userId, it) } }
    /**
     * Whether the session a socket or stream was opened with still holds: sign-out, a password reset and device
     * revocation delete it, and a browser session also ends when it expires.
     */
    fun sessionAlive(caller: Caller): Boolean = db.tx {
        single("SELECT kind, access_expires_at FROM sessions WHERE id = ? AND user_id = ?", caller.sessionId, caller.userId) {
            it.getString(1) == SessionKind.DEVICE.name || it.getLong(2) >= clock() } ?: false
    }
    /** A finished job frees its device for the next one. */
    fun report(caller: Caller, jobId: String, report: JobReport) {
        val id = device(caller); val now = clock()
        db.tx {
            val job = single("SELECT state, lease_no FROM jobs WHERE id = ? AND device_id = ? AND user_id = ?", jobId, id, caller.userId) { JobState.valueOf(it.getString(1)) to it.getLong(2) }
                ?: throw ApiException(404, "not_found", "作业不存在")
            if (job.second != report.leaseNo) throw ApiException(409, "stale_lease", "作业租约已失效")
            if (job.first == report.state) return@tx
            val allowed = when (job.first) {
                JobState.LEASED -> report.state in setOf(JobState.RUNNING, JobState.CANCELLED, JobState.FAILED)
                JobState.RUNNING -> report.state in setOf(JobState.COMPLETED, JobState.FAILED, JobState.UNKNOWN, JobState.CANCELLED)
                // Evidence that arrives after a lost connection may still settle an unknown outcome.
                JobState.UNKNOWN -> report.state in setOf(JobState.COMPLETED, JobState.FAILED)
                else -> false
            }
            if (!allowed) throw ApiException(409, "invalid_transition", "作业状态不能这样变更")
            val active = report.state == JobState.RUNNING
            update("UPDATE jobs SET state = ?, detail = ?, lease_expires_at = ?, pause_requested = CASE WHEN ? THEN pause_requested ELSE 0 END, updated_at = ? WHERE id = ?",
                report.state.name, report.detail.take(500), if (active) now + LEASE_LIFETIME else null, active, now, jobId)
        }
        pushJobs(caller.userId)
        // A finished job frees its account too, so a device waiting on that account may start now.
        if (report.state == JobState.RUNNING) dispatch(id) else dispatchAll()
    }

    /** Only the device holding the current lease can add to a job's log; lines past the cap are dropped. */
    fun appendEvents(caller: Caller, jobId: String, batch: JobEvents) {
        val id = device(caller)
        db.tx {
            val lease = single("SELECT lease_no FROM jobs WHERE id = ? AND device_id = ? AND user_id = ?", jobId, id, caller.userId) { it.getLong(1) }
                ?: throw ApiException(404, "not_found", "作业不存在")
            if (lease != batch.leaseNo) throw ApiException(409, "stale_lease", "作业租约已失效")
            var stored = single("SELECT COUNT(*) FROM job_events WHERE job_id = ?", jobId) { it.getInt(1) } ?: 0
            for (event in batch.events.take(MAX_EVENTS_PER_BATCH)) {
                if (stored >= MAX_EVENTS_PER_JOB) break
                stored += update("INSERT OR IGNORE INTO job_events (job_id, key, at, kind, step, action, detail, elapsed_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    jobId, event.key.take(40), event.at, event.kind.take(40), event.step.take(80), event.action.take(80), event.detail.take(400), event.elapsedMs)
            }
        }
        events.publish(caller.userId, "job-log", json.encodeToString(JobLogUpdated.serializer(), JobLogUpdated(jobId)))
    }
    /** Step ids are named from the task version the job ran on. */
    fun events(caller: Caller, jobId: String): List<JobEventView> = db.tx {
        val job = single("SELECT digest, target_id, task_id FROM jobs WHERE id = ? AND user_id = ?", jobId, caller.userId) {
            Triple(it.getString(1), it.getString(2), it.getString(3)) } ?: throw ApiException(404, "not_found", "作业不存在")
        val names = catalog(caller.userId, job.first)?.tasks?.singleOrNull { it.targetId == job.second && it.id == job.third }
            ?.steps?.associate { it.id to it.name }.orEmpty()
        query("SELECT at, kind, step, action, detail, elapsed_ms FROM job_events WHERE job_id = ? ORDER BY at, key", jobId) {
            val step = it.getString(3)
            JobEventView(it.getLong(1), it.getString(2), step, names[step] ?: step, it.getString(4), it.getString(5), it.getLong(6)) }
    }
    /** Online means the device's socket is open right now; [deviceId] null lists them all. */
    private fun Database.deviceRows(userId: String, deviceId: String?): List<DeviceView> {
        val assigned = query("SELECT device_id, app_key, account_id, default_account_id FROM app_accounts WHERE user_id = ?", userId) {
            (it.getString(1) to it.getString(2)) to (it.getString(3) to it.getString(4)) }.toMap()
        return query("""SELECT id, name, platform, last_seen_at, phase, current_task, queued, tasks_json, apps_json, concurrency FROM devices
            WHERE user_id = ? AND revoked = 0 AND (? IS NULL OR id = ?) ORDER BY name, id""", userId, deviceId, deviceId) { row ->
            val seen = row.getLong(4).takeUnless { row.wasNull() }
            val id = row.getString(1)
            val views = json.decodeFromString(apps, row.getString(9)).map { app ->
                val (current, default) = assigned[id to app.key] ?: (null to null)
                AppView(app.key, app.gameId, app.name, app.phase, app.currentTask, app.queued, current, default)
            }
            DeviceView(id, row.getString(2), row.getString(3), sockets.isOnline(id), seen, row.getString(5), row.getString(6), row.getInt(7),
                json.decodeFromString(tasks, row.getString(8)), views, row.getInt(10))
        }
    }
    fun devices(caller: Caller): List<DeviceView> = db.tx { deviceRows(caller.userId, null) }
    /** Devices that sign in again come back; until then this one can neither work nor report. Its socket is closed by the caller. */
    fun revokeDevice(caller: Caller, deviceId: String) {
        db.tx {
            if (update("UPDATE devices SET revoked = 1 WHERE id = ? AND user_id = ?", deviceId, caller.userId) == 0) throw ApiException(404, "not_found", "设备不存在")
            update("DELETE FROM sessions WHERE device_id = ? AND user_id = ?", deviceId, caller.userId)
            update("UPDATE jobs SET state = 'CANCELLED', detail = '设备已解绑', updated_at = ? WHERE device_id = ? AND user_id = ? AND state IN ('QUEUED', 'LEASED')", clock(), deviceId, caller.userId)
            update("UPDATE jobs SET state = 'UNKNOWN', detail = '设备已解绑，结果未确认', updated_at = ? WHERE device_id = ? AND user_id = ? AND state = 'RUNNING'", clock(), deviceId, caller.userId)
        }
        events.publish(caller.userId, "device-removed", json.encodeToString(RemovedDevice.serializer(), RemovedDevice(deviceId)))
        pushJobs(caller.userId)
    }
    /**
     * Runs a task now for one game account. Without a device the server picks an app signed in to the account (4.2);
     * with one, the job goes to that device's app of the game, which must be signed in to the account because switching
     * is not supported yet. Either way it has [IMMEDIATE_WAIT] to start, or it is skipped.
     */
    fun createJob(caller: Caller, request: CreateJobRequest): JobView {
        val id = UUID.randomUUID().toString(); val now = clock()
        val assigned = db.tx {
            val account = account(caller.userId, request.accountId)
            if (account.gameId != request.targetId) throw ApiException(400, "wrong_game", "这个账号不属于该任务的游戏")
            val task = gameRows(caller.userId).singleOrNull { it.id == account.gameId }?.tasks?.singleOrNull { it.taskId == request.taskId }
                ?: throw ApiException(400, "unknown_task", "这个游戏没有这个任务")
            val (values, _) = savedConfig(account.id, task.targetId, task.taskId)
            val issues = validateConfiguration(latestTask(caller.userId, task.targetId, task.taskId).second, values)
            if (issues.isNotEmpty()) throw ApiException(400, "config_required", "请先保存「${account.name}」的「${task.name}」设置：" + issues.joinToString("；") { it.reason })
            val target = request.deviceId?.let { deviceId ->
                val device = deviceRows(caller.userId, deviceId).singleOrNull() ?: throw ApiException(404, "not_found", "设备不存在")
                if (device.tasks.none { it.targetId == task.targetId && it.taskId == task.taskId }) throw ApiException(400, "unknown_task", "这台设备没有这个任务")
                val candidates = device.apps.filter { it.gameId == task.targetId && (request.appKey == null || it.key == request.appKey) }
                val app = candidates.singleOrNull() ?: throw ApiException(400, if (candidates.isEmpty()) "unknown_app" else "choose_app",
                    if (candidates.isEmpty()) "这台设备上没有这个游戏的应用" else "这台设备上有多个这个游戏的应用，请选择一个")
                if (app.accountId != account.id) throw ApiException(409, "account_mismatch",
                    "${app.name}当前登录的不是「${account.name}」，暂不支持切号：请先在游戏里登录这个账号，并在设备的应用里标明")
                // A run started on the device itself does not wait: it is refused while the account runs elsewhere.
                if (caller.kind == SessionKind.DEVICE && caller.deviceId == device.id) single("""SELECT d.name FROM jobs j JOIN devices d ON d.id = j.device_id
                    WHERE j.account_id = ? AND j.device_id <> ? AND j.state IN ('LEASED', 'RUNNING') LIMIT 1""", account.id, device.id) { it.getString(1) }
                    ?.let { throw ApiException(409, "account_busy", "「${account.name}」正在 $it 上运行，等它结束再试") }
                device.id to app.key
            }
            update("""INSERT INTO jobs (id, user_id, device_id, app_key, target_id, task_id, task_name, state, created_at, updated_at, config_json, account_id, account_name,
                latest_start_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, caller.userId, target?.first, target?.second, task.targetId, task.taskId, task.name, if (target == null) "WAITING" else "QUEUED", now, now,
                values.toString(), account.id, account.name, now + IMMEDIATE_WAIT)
            target
        }
        pushJobs(caller.userId)
        if (assigned == null) assignNow() else dispatch(assigned.first)
        return jobs(caller).first { it.id == id }
    }
    private fun Database.jobRows(userId: String): List<JobView> =
        query("""SELECT j.id, j.device_id, d.name, j.target_id, j.task_id, j.task_name, j.state, j.detail, j.cancel_requested, j.created_at, j.updated_at,
            j.account_id, j.account_name, j.app_key, j.plan_id, j.plan_name, j.latest_start_at, j.pause_requested
            FROM jobs j LEFT JOIN devices d ON d.id = j.device_id WHERE j.user_id = ? ORDER BY j.created_at DESC, j.sequence DESC, j.id LIMIT 100""", userId) { r ->
            JobView(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), JobState.valueOf(r.getString(7)), r.getString(8), r.getInt(9) == 1,
                r.getLong(10), r.getLong(11), r.getString(12), r.getString(13), r.getString(14), r.getString(15), r.getString(16),
                r.getLong(17).takeUnless { r.wasNull() }, r.getInt(18) == 1) }
    /** Leases are settled by [tick], which also tells consoles; reading never changes jobs. */
    fun jobs(caller: Caller): List<JobView> = db.tx { jobRows(caller.userId) }
    /**
     * A job that has not been sent is dropped at once; a sent one is flagged, and the device is told now if it is
     * connected and otherwise on its next report. Returns the device to notify, or null when nothing has to be sent.
     */
    fun cancelJob(caller: Caller, jobId: String): Pair<String, ServerMessage.Cancel>? {
        val notify = db.tx {
            val job = single("SELECT state, device_id FROM jobs WHERE id = ? AND user_id = ?", jobId, caller.userId) { JobState.valueOf(it.getString(1)) to it.getString(2) }
                ?: throw ApiException(404, "not_found", "作业不存在")
            when (job.first) {
                JobState.WAITING, JobState.QUEUED -> { update("UPDATE jobs SET state = 'CANCELLED', detail = '已取消', updated_at = ? WHERE id = ?", clock(), jobId); null }
                JobState.LEASED, JobState.RUNNING -> { update("UPDATE jobs SET cancel_requested = 1, updated_at = ? WHERE id = ?", clock(), jobId)
                    job.second to ServerMessage.Cancel(listOf(jobId)) }
                else -> null
            }
        }
        pushJobs(caller.userId)
        return notify
    }
    /** Pause or resume a running job. The device's own report shows when it has actually paused. */
    fun pauseJob(caller: Caller, jobId: String, pause: Boolean): Pair<String, ServerMessage> {
        val deviceId = db.tx {
            val job = single("SELECT state, device_id FROM jobs WHERE id = ? AND user_id = ?", jobId, caller.userId) { JobState.valueOf(it.getString(1)) to it.getString(2) }
                ?: throw ApiException(404, "not_found", "作业不存在")
            if (job.first != JobState.RUNNING) throw ApiException(409, "not_running", "只有运行中的作业可以暂停或继续")
            update("UPDATE jobs SET pause_requested = ?, updated_at = ? WHERE id = ?", if (pause) 1 else 0, clock(), jobId)
            job.second
        }
        pushJobs(caller.userId)
        return deviceId to if (pause) ServerMessage.Pause(jobId) else ServerMessage.Resume(jobId)
    }
    fun encode(message: ServerMessage) = json.encodeToString(ServerMessage.serializer(), message)
    fun decodeDeviceMessage(text: String): DeviceMessage = json.decodeFromString(DeviceMessage.serializer(), text)

    companion object {
        val HELD_PHASES = setOf("CONNECTING", "PAUSING", "PAUSED", "BLOCKED_UNKNOWN", "REMOVING", "SWITCHING")
        const val MINUTE = 60_000L; const val CODE_LIFETIME = 10 * MINUTE; const val MAX_CODE_ATTEMPTS = 5
        const val WEB_LIFETIME = 7 * 24 * 60 * MINUTE; const val DEVICE_ACCESS_LIFETIME = 60 * MINUTE; const val REFRESH_LIFETIME = 30 * 24 * 60 * MINUTE
        const val LEASE_LIFETIME = 2 * MINUTE; const val ACK_LIFETIME = MINUTE / 2
        /** How long a job started from the console may wait for its app before it is skipped. */
        const val IMMEDIATE_WAIT = 30 * MINUTE
        private const val MAX_APPS = 32
        private const val MAX_PARSED_BUNDLES = 32
        /** Only the newest bundles decide games and settings; older uploads are kept but no longer read. */
        private const val MAX_BUNDLES_READ = 8
        const val DEFAULT_ZONE = "Asia/Shanghai"
        private val jobList = ListSerializer(JobView.serializer())
        private val accountList = ListSerializer(GameAccountView.serializer())
        private val planList = ListSerializer(PlanView.serializer())
        private val gameList = ListSerializer(GameView.serializer())
        private val idList = ListSerializer(kotlinx.serialization.serializer<String>())
        private const val PURPOSE_VERIFY = "verify"; private const val PURPOSE_LOGIN = "login"; private const val PURPOSE_RESET = "reset"
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
        private const val MAX_CATALOG_CHARS = 4_000_000
        private const val MAX_EVENTS_PER_BATCH = 500; private const val MAX_EVENTS_PER_JOB = 3_000
        /** Newer apps may add fields this server does not know; only task and setting definitions are read here. */
        private val bundles = Json { ignoreUnknownKeys = true }
        private val fieldList = ListSerializer(ConfigurationField.serializer())
        private fun hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
