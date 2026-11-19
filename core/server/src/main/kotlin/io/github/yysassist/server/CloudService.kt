package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.github.yysassist.protocol.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
enum class SessionKind { WEB, DEVICE }
data class Caller(val userId: String, val sessionId: String, val kind: SessionKind, val deviceId: String?)
data class IssuedSession(val userId: String, val email: String?, val accessToken: String, val refreshToken: String?, val accessExpiresAt: Long)

/** All business rules. Every query that reads user data is scoped by the caller's user id. */
class CloudService(private val db: Database, private val mailer: Mailer, val github: GitHubIdentity, private val secret: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tasks = ListSerializer(CloudTask.serializer())
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
        AccountView(caller.userId, user.first, user.second, single("SELECT login FROM identities WHERE provider = 'github' AND user_id = ?", caller.userId) { it.getString(1) })
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
        }
    }
    private fun device(caller: Caller): String = caller.deviceId?.takeIf { caller.kind == SessionKind.DEVICE } ?: throw ApiException(403, "device_only", "请先注册设备")

    private fun Database.sweep() {
        val now = clock()
        // Never acknowledged, so nothing ran: offer it again. Once started the outcome is unknown and is never retried.
        update("UPDATE jobs SET state = 'QUEUED', lease_expires_at = NULL, updated_at = ? WHERE state = 'LEASED' AND lease_expires_at < ?", now, now)
        update("UPDATE jobs SET state = 'UNKNOWN', detail = '设备失联，结果未确认', lease_expires_at = NULL, updated_at = ? WHERE state = 'RUNNING' AND lease_expires_at < ?", now, now)
    }
    fun heartbeat(caller: Caller, beat: DeviceHeartbeat): HeartbeatReply {
        val id = device(caller); val now = clock()
        return db.tx {
            update("UPDATE devices SET last_seen_at = ?, phase = ?, current_task = ?, queued = ?, tasks_json = ? WHERE id = ? AND user_id = ?",
                now, beat.phase.take(40), beat.currentTask?.take(120), beat.queued, json.encodeToString(tasks, beat.tasks.take(500)), id, caller.userId)
            // Only a started job is kept alive: a lease handed to a connection that died must return to the queue.
            update("UPDATE jobs SET lease_expires_at = ? WHERE device_id = ? AND user_id = ? AND state = 'RUNNING'", now + LEASE_LIFETIME, id, caller.userId)
            sweep()
            HeartbeatReply(query("SELECT id FROM jobs WHERE device_id = ? AND user_id = ? AND cancel_requested = 1 AND state IN ('LEASED', 'RUNNING')", id, caller.userId) { it.getString(1) })
        }
    }
    fun nextJob(caller: Caller): JobLease? {
        val id = device(caller); val now = clock()
        return db.tx {
            sweep()
            val job = single("SELECT id, target_id, task_id, lease_no FROM jobs WHERE device_id = ? AND user_id = ? AND state = 'QUEUED' ORDER BY created_at, id LIMIT 1", id, caller.userId) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) } ?: return@tx null
            val lease = (job[3] as Long) + 1; val expires = now + ACK_LIFETIME
            update("UPDATE jobs SET state = 'LEASED', lease_no = ?, lease_expires_at = ?, updated_at = ? WHERE id = ?", lease, expires, now, job[0])
            JobLease(job[0] as String, lease, caller.userId, id, job[1] as String, job[2] as String, expires)
        }
    }
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
            update("UPDATE jobs SET state = ?, detail = ?, lease_expires_at = ?, updated_at = ? WHERE id = ?", report.state.name, report.detail.take(500), if (active) now + LEASE_LIFETIME else null, now, jobId)
        }
    }

    fun devices(caller: Caller): List<DeviceView> = db.tx {
        query("SELECT id, name, platform, last_seen_at, phase, current_task, queued, tasks_json FROM devices WHERE user_id = ? AND revoked = 0 ORDER BY name, id", caller.userId) { row ->
            val seen = row.getLong(4).takeUnless { row.wasNull() }
            DeviceView(row.getString(1), row.getString(2), row.getString(3), seen != null && clock() - seen < ONLINE_WINDOW, seen, row.getString(5), row.getString(6), row.getInt(7),
                json.decodeFromString(tasks, row.getString(8)))
        }
    }
    /** Devices that sign in again come back; until then this one can neither work nor report. */
    fun revokeDevice(caller: Caller, deviceId: String) = db.tx {
        if (update("UPDATE devices SET revoked = 1 WHERE id = ? AND user_id = ?", deviceId, caller.userId) == 0) throw ApiException(404, "not_found", "设备不存在")
        update("DELETE FROM sessions WHERE device_id = ? AND user_id = ?", deviceId, caller.userId)
        update("UPDATE jobs SET state = 'CANCELLED', detail = '设备已解绑', updated_at = ? WHERE device_id = ? AND user_id = ? AND state IN ('QUEUED', 'LEASED')", clock(), deviceId, caller.userId)
        update("UPDATE jobs SET state = 'UNKNOWN', detail = '设备已解绑，结果未确认', updated_at = ? WHERE device_id = ? AND user_id = ? AND state = 'RUNNING'", clock(), deviceId, caller.userId); Unit
    }
    fun createJob(caller: Caller, request: CreateJobRequest): JobView {
        val id = UUID.randomUUID().toString(); val now = clock()
        db.tx {
            val offered = single("SELECT tasks_json FROM devices WHERE id = ? AND user_id = ? AND revoked = 0", request.deviceId, caller.userId) { json.decodeFromString(tasks, it.getString(1)) }
                ?: throw ApiException(404, "not_found", "设备不存在")
            val task = offered.firstOrNull { it.targetId == request.targetId && it.taskId == request.taskId } ?: throw ApiException(400, "unknown_task", "这台设备没有这个任务")
            update("INSERT INTO jobs (id, user_id, device_id, target_id, task_id, task_name, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)",
                id, caller.userId, request.deviceId, task.targetId, task.taskId, task.name, now, now)
        }
        return jobs(caller).first { it.id == id }
    }
    fun jobs(caller: Caller): List<JobView> = db.tx {
        sweep()
        query("""SELECT j.id, j.device_id, d.name, j.target_id, j.task_id, j.task_name, j.state, j.detail, j.cancel_requested, j.created_at, j.updated_at
            FROM jobs j JOIN devices d ON d.id = j.device_id WHERE j.user_id = ? ORDER BY j.created_at DESC, j.id LIMIT 100""", caller.userId) { r ->
            JobView(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), JobState.valueOf(r.getString(7)), r.getString(8), r.getInt(9) == 1, r.getLong(10), r.getLong(11)) }
    }
    fun cancelJob(caller: Caller, jobId: String) = db.tx {
        val state = single("SELECT state FROM jobs WHERE id = ? AND user_id = ?", jobId, caller.userId) { JobState.valueOf(it.getString(1)) } ?: throw ApiException(404, "not_found", "作业不存在")
        when (state) {
            JobState.QUEUED -> update("UPDATE jobs SET state = 'CANCELLED', detail = '已取消', updated_at = ? WHERE id = ?", clock(), jobId)
            JobState.LEASED, JobState.RUNNING -> update("UPDATE jobs SET cancel_requested = 1, updated_at = ? WHERE id = ?", clock(), jobId)
            else -> Unit
        }; Unit
    }

    companion object {
        const val MINUTE = 60_000L; const val CODE_LIFETIME = 10 * MINUTE; const val MAX_CODE_ATTEMPTS = 5
        const val WEB_LIFETIME = 7 * 24 * 60 * MINUTE; const val DEVICE_ACCESS_LIFETIME = 60 * MINUTE; const val REFRESH_LIFETIME = 30 * 24 * 60 * MINUTE
        const val LEASE_LIFETIME = 2 * MINUTE; const val ACK_LIFETIME = MINUTE / 2; const val ONLINE_WINDOW = MINUTE
        private const val PURPOSE_VERIFY = "verify"; private const val PURPOSE_LOGIN = "login"; private const val PURPOSE_RESET = "reset"
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
