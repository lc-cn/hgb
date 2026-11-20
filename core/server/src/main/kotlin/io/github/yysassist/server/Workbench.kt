package io.github.yysassist.server

import io.github.yysassist.core.actions.screenContracts
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A GitHub account's permission on the task repository, by the account's numeric id (logins can be renamed): admin,
 * maintain, write, triage, read or none; null when it cannot be told (GitHub unreachable, repository not visible).
 */
fun interface RepoPermissions { fun permission(subject: String, login: String): String? }

/**
 * Asks GitHub with a token the repository's owner issued for this server. The token only reads permissions (and later
 * publishes releases); it never comes from or goes to a user.
 */
class GitHubRepoPermissions(private val repository: String, private val token: String) : RepoPermissions {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private fun get(path: String): HttpResponse<String>? {
        val request = HttpRequest.newBuilder(URI("https://api.github.com$path")).timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token").header("User-Agent", "hgb-workbench").GET().build()
        return runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
    }
    override fun permission(subject: String, login: String): String? {
        // The current login of that account, in case it was renamed since it last signed in here.
        val current = get("/user/${URLEncoder.encode(subject, Charsets.UTF_8)}")?.takeIf { it.statusCode() == 200 }
            ?.let { runCatching { Json.parseToJsonElement(it.body()).jsonObject["login"]?.jsonPrimitive?.contentOrNull }.getOrNull() } ?: login
        val reply = get("/repos/$repository/collaborators/${URLEncoder.encode(current, Charsets.UTF_8)}/permission") ?: return null
        return when (reply.statusCode()) {
            200 -> runCatching { Json.parseToJsonElement(reply.body()).jsonObject["permission"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            // Not a collaborator — but only if the repository itself is visible; otherwise the server is misconfigured.
            404 -> if (get("/repos/$repository")?.statusCode() == 200) "none" else null
            else -> null
        }
    }
}

@Serializable
data class CapabilityView(
    val id: String, val version: Int, val inputs: Map<String, String>, val outputs: Map<String, String>, val role: String,
    val sendsInput: Boolean, val inputHandles: Map<String, List<String>>, val outputHandles: Map<String, String>,
)
@Serializable
data class CheckIssue(val location: String, val reason: String)
@Serializable
data class CheckResult(val ok: Boolean, val issues: List<CheckIssue>, val tasks: Int, val publishedBytes: Int, val expandedBytes: Int)

/**
 * The server side of the developer workbench. Checks run the engine's own reader with the App's own action contracts,
 * so what passes here is what the App will accept.
 */
class Workbench(private val db: Database, private val permissions: RepoPermissions?, private val clock: () -> Long = System::currentTimeMillis) {
    private val provider = object : ActionProvider {
        override val contracts = screenContracts
        override fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession = error("服务端不执行动作")
    }
    val contracts: List<CapabilityContract> = BuiltInStepWorkFactory(provider).contracts
    private val codec = BundleCodec(contracts)
    /** (user, GitHub account) -> allowed, when decided. Keyed by the account so unlinking or relinking takes effect at once. */
    private val decided = ConcurrentHashMap<Pair<String, String>, Pair<Boolean, Long>>()
    val drafts = Drafts(db, clock)

    /** Only a GitHub account that can write to the task repository may use the workbench. */
    fun requireDeveloper(caller: Caller) {
        val checker = permissions ?: throw ApiException(503, "workbench_unconfigured", "服务端还没有配置任务仓库，开发工作台不可用")
        val (subject, login) = db.tx { single("SELECT subject, login FROM identities WHERE provider = 'github' AND user_id = ?", caller.userId) {
            it.getString(1) to (it.getString(2) ?: "") } } ?: throw ApiException(403, "github_required", "请先在“我的”里绑定 GitHub")
        val key = caller.userId to subject
        val now = clock()
        decided[key]?.takeIf { (allowed, at) -> now - at < if (allowed) ALLOW_TTL else DENY_TTL }?.let { (allowed, _) ->
            if (allowed) return else throw ApiException(403, "not_developer", "这个 GitHub 账号对任务仓库没有写权限")
        }
        // Unknown is neither granted nor remembered.
        val permission = checker.permission(subject, login) ?: throw ApiException(503, "github_unavailable", "暂时查不到 GitHub 权限（或服务端的任务仓库配置有误），请稍后再试")
        val allowed = permission in setOf("admin", "maintain", "write")
        decided[key] = allowed to now
        if (!allowed) throw ApiException(403, "not_developer", "这个 GitHub 账号对任务仓库没有写权限")
    }

    fun catalog(): List<CapabilityView> = contracts.map { c ->
        CapabilityView(c.id, c.version, c.inputTypes.mapValues { it.value.name }, c.outputTypes.mapValues { it.value.name }, c.role.name,
            c.sendsDeviceInput, c.inputHandles.mapValues { it.value.kinds.toList().sorted() }, c.outputHandles)
    }

    /** Reads [text] (a task bundle in its compact, published form) exactly as the App would. */
    fun check(text: String): CheckResult {
        if (text.length > MAX_BUNDLE_CHARS) throw ApiException(413, "too_large", "任务包太大")
        if (depth(Json.parseToJsonElement(text)) > MAX_DEPTH) throw ApiException(400, "too_deep", "任务包嵌套太深")
        return when (val read = codec.read(text)) {
            is BundleReadResult.Valid -> CheckResult(true, emptyList(), read.bundle.tasks.size, text.toByteArray().size, codec.write(read.bundle).toByteArray().size)
            is BundleReadResult.Invalid -> CheckResult(false, read.issues.map { CheckIssue(it.location, it.reason) }, 0, text.toByteArray().size, 0)
        }
    }

    companion object {
        private const val ALLOW_TTL = 10 * 60 * 1000L
        private const val DENY_TTL = 60 * 1000L
        const val MAX_BUNDLE_CHARS = 4_000_000
        const val MAX_DEPTH = 64
        /** Nesting depth, counted without recursion so a hostile document cannot overflow the stack. */
        fun depth(root: JsonElement): Int {
            var deepest = 0
            val stack = ArrayDeque(listOf(root to 1))
            while (stack.isNotEmpty()) {
                val (e, d) = stack.removeLast()
                deepest = maxOf(deepest, d)
                if (d > MAX_DEPTH) return d
                when (e) { is JsonObject -> e.values.forEach { stack.add(it to d + 1) }; is JsonArray -> e.forEach { stack.add(it to d + 1) }; else -> Unit }
            }
            return deepest
        }
    }
}
