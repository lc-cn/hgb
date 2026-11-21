package io.github.yysassist.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * A developer's working copy of one game's task set, in its compact published form, edited by node patches so the web
 * editor and the phone can change different parts at the same time. A node is any object with an id (an action's id
 * sits in its `action`), found by the ids along its path: `foster/pick/look`. Objects without an id (such as a target's
 * `library`) are passed through. Ids must be unique among everything directly under one parent.
 */
@Serializable
data class DraftView(val target: String, val baseVersion: String, val revision: Long, val body: JsonObject,
    /** Node digests of the tasks and snippets as imported, keyed `tasks/<id>` and `snippets/<id>`: what is unchanged since. */
    val bases: Map<String, String> = emptyMap())
@Serializable
data class DraftSummary(val target: String, val baseVersion: String, val revision: Long, val updatedAt: Long)
@Serializable
data class DraftImport(val baseVersion: String, val body: JsonObject)
/**
 * One edit.
 * - `set`: changes the node's own fields from [value] (an object), leaving lists and nodes under it alone; [expect] is
 *   its [shallowDigest], so edits elsewhere in the subtree do not conflict.
 * - `put`: replaces the node at [path] with [value] (same id); [expect] is its [nodeDigest] (the whole subtree).
 * - `delete`: removes the node at [path]; [expect] is its [nodeDigest].
 * - `insert`: adds [value] to the list [field] (dots pass through id-less objects, e.g. `library.states`) of the node at
 *   [path] (root when empty), after the sibling [after] or first when null.
 * Within one batch, a later patch to the same node expects the state the earlier ones left.
 */
@Serializable
data class DraftPatch(val op: String, val path: String, val value: JsonElement? = null, val field: String? = null,
    val after: String? = null, val expect: String? = null)
@Serializable
data class PatchBatch(val patches: List<DraftPatch>, val source: String = "web")
@Serializable
data class PatchConflict(val path: String, val reason: String, val current: JsonElement? = null, val digest: String? = null,
    val shallowDigest: String? = null)
@Serializable
data class PatchResult(val revision: Long, val conflicts: List<PatchConflict> = emptyList())
/** [patches] empty with [replaced]: the whole draft was imported at [revision]; reload it instead of applying. */
@Serializable
data class PatchRecord(val revision: Long, val source: String, val at: Long, val patches: List<DraftPatch>, val replaced: Boolean = false)
@Serializable
data class PatchHistory(val records: List<PatchRecord>, val complete: Boolean)

private val canonical = Json { prettyPrint = false }

/** Digest of a node's canonical form (keys sorted), subtree included: what put and delete expect. */
fun nodeDigest(node: JsonElement): String = digest(sorted(node))
/** Digest of a node's own fields: lists, and objects that are nodes themselves, left out. What set expects. */
fun shallowDigest(node: JsonObject): String = digest(sorted(JsonObject(node.filterValues { v -> v !is JsonArray && !(v is JsonObject && idOf(v) != null) })))
private fun digest(e: JsonElement) = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToString(JsonElement.serializer(), e).toByteArray())
    .joinToString("") { "%02x".format(it) }.take(16)
private fun sorted(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, v) -> sorted(v) })
    is JsonArray -> JsonArray(element.map(::sorted))
    else -> element
}
private fun idOf(node: JsonObject): String? = (node["id"] as? JsonPrimitive)?.contentOrNull
    ?: ((node["action"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

/** Immutable-tree editing: every change rebuilds the path to the root, so a failed batch leaves the original intact. */
private object Nodes {
    /** The direct child nodes of [node] with the list each sits in, passing through id-less objects. */
    fun children(node: JsonObject, at: List<String> = emptyList()): List<Pair<JsonObject, List<String>>> = node.flatMap { (key, value) -> when {
        key == "action" -> emptyList()
        value is JsonArray -> value.filterIsInstance<JsonObject>().flatMap { child ->
            if (idOf(child) != null) listOf(child to at + key) else children(child, at + key) }
        value is JsonObject && idOf(value) == null -> children(value, at + key)
        else -> emptyList()
    } }

    /** Whether [segment] names a node with [id] in the list [key]: `id`, or `list:id` when ids repeat across lists. */
    fun names(segment: String, id: String?, key: String?): Boolean {
        val colon = segment.indexOf(':')
        return if (colon < 0) id == segment else id == segment.substring(colon + 1) && key == segment.substring(0, colon)
    }

    /** The node at [path], or why not. */
    fun find(root: JsonObject, path: List<String>): Result<JsonObject> {
        var node = root
        for (segment in path) {
            val hits = children(node).filter { names(segment, idOf(it.first), it.second.lastOrNull()) }
            node = when (hits.size) {
                1 -> hits.single().first
                0 -> return Result.failure(IllegalArgumentException("找不到节点 $segment（可能已被删除或改名）"))
                else -> return Result.failure(IllegalArgumentException("编号 $segment 有歧义：同一层里出现了不止一次"))
            }
        }
        return Result.success(node)
    }

    /** [root] with the node at [path] replaced by [change] of it (null removes it). */
    fun update(root: JsonObject, path: List<String>, change: (JsonObject) -> JsonObject?): JsonObject =
        if (path.isEmpty()) change(root) ?: error("不能删除草稿根") else inObject(root, path, change)

    private fun inObject(o: JsonObject, rest: List<String>, change: (JsonObject) -> JsonObject?): JsonObject = JsonObject(o.mapValues { (key, v) -> when {
        key == "action" -> v
        v is JsonArray -> inList(v, key, rest, change)
        v is JsonObject && idOf(v) == null -> inObject(v, rest, change)
        else -> v
    } })
    private fun inList(list: JsonArray, key: String, rest: List<String>, change: (JsonObject) -> JsonObject?): JsonArray = JsonArray(list.mapNotNull { e -> when {
        e !is JsonObject -> e
        names(rest.first(), idOf(e), key) -> if (rest.size == 1) change(e) else inObject(e, rest.drop(1), change)
        idOf(e) == null -> inObject(e, rest, change)
        else -> e
    } })

    /** [node] with [value] inserted into its list at [field] after [after]. */
    fun insert(node: JsonObject, field: List<String>, value: JsonObject, after: String?): JsonObject {
        val key = field.first()
        val existing = node[key]
        if (field.size == 1) {
            val list = when (existing) { null -> JsonArray(emptyList()); is JsonArray -> existing; else -> error("$key 不是列表") }
            val at = if (after == null) 0 else list.indexOfFirst { (it as? JsonObject)?.let(::idOf) == after }.takeIf { it >= 0 }?.plus(1)
                ?: error("找不到要插在其后的节点 $after")
            return JsonObject(node + (key to JsonArray(list.toMutableList().apply { add(at, value) })))
        }
        val inner = when (existing) { null -> JsonObject(emptyMap()); is JsonObject -> existing.takeIf { idOf(it) == null } ?: error("$key 是节点，不能穿过"); else -> error("$key 不是对象") }
        return JsonObject(node + (key to insert(inner, field.drop(1), value, after)))
    }
}

class Drafts(private val db: Database, private val clock: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The caller's drafts, newest first. */
    fun list(userId: String): List<DraftSummary> = db.tx {
        query("SELECT target_id, base_version, revision, updated_at FROM dev_drafts WHERE user_id = ? ORDER BY updated_at DESC", userId) {
            DraftSummary(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) }
    }

    fun get(userId: String, target: String): DraftView = db.tx {
        find(userId, target)?.let { view ->
            val bases = single("SELECT digests FROM dev_draft_bases WHERE user_id = ? AND target_id = ?", userId, target) { it.getString(1) }
            when {
                bases != null -> view.copy(bases = json.decodeFromString<Map<String, String>>(bases))
                // Imported before bases were kept and not edited since: what it holds is still the base.
                view.revision == 1L -> view.copy(bases = keepBases(userId, target, view.body))
                else -> view
            }
        }
    } ?: throw ApiException(404, "no_draft", "还没有这个游戏的草稿")
    private fun find(userId: String, target: String): DraftView? = db.single("SELECT base_version, revision, body FROM dev_drafts WHERE user_id = ? AND target_id = ?", userId, target) {
        DraftView(target, it.getString(1), it.getLong(2), json.parseToJsonElement(it.getString(3)).jsonObject)
    }

    private fun checkTarget(target: String) {
        if (!target.matches(Regex("[A-Za-z0-9._-]{1,64}"))) throw ApiException(400, "bad_target", "草稿名只能用字母、数字和 . _ -，最长 64")
    }
    private fun checkSize(body: JsonObject) {
        val text = json.encodeToString(JsonObject.serializer(), body)
        if (text.length > Workbench.MAX_BUNDLE_CHARS) throw ApiException(413, "too_large", "草稿太大")
        if (Workbench.depth(body) > Workbench.MAX_DEPTH) throw ApiException(400, "too_deep", "草稿嵌套太深")
    }

    /** Starts the draft from a published version, or replaces it entirely (an explicit import, never a merge). */
    fun import(userId: String, target: String, draft: DraftImport): PatchResult {
        checkTarget(target); checkSize(draft.body)
        return db.tx {
            val existing = find(userId, target)
            if (existing == null && db.single("SELECT COUNT(*) FROM dev_drafts WHERE user_id = ?", userId) { it.getInt(1) }!! >= MAX_DRAFTS)
                throw ApiException(409, "too_many_drafts", "草稿最多 $MAX_DRAFTS 份")
            val revision = (existing?.revision ?: 0L) + 1
            db.update("""INSERT INTO dev_drafts (user_id, target_id, base_version, revision, body, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, target_id) DO UPDATE SET base_version = excluded.base_version, revision = excluded.revision, body = excluded.body, updated_at = excluded.updated_at""",
                userId, target, draft.baseVersion, revision, json.encodeToString(JsonObject.serializer(), draft.body), clock())
            record(userId, target, revision, "import", emptyList())
            keepBases(userId, target, draft.body)
            PatchResult(revision)
        }
    }

    /** Records what each task and snippet of [body] is, as the draft's base. */
    private fun keepBases(userId: String, target: String, body: JsonObject): Map<String, String> {
        val bases = listOf("tasks", "snippets").flatMap { field -> (body[field] as? JsonArray).orEmpty().mapNotNull { node ->
            (node as? JsonObject)?.let(::idOf)?.let { id -> "$field/$id" to nodeDigest(node) } } }.toMap()
        db.update("""INSERT INTO dev_draft_bases (user_id, target_id, digests) VALUES (?, ?, ?)
            ON CONFLICT (user_id, target_id) DO UPDATE SET digests = excluded.digests""", userId, target, json.encodeToString(bases))
        return bases
    }

    /** Applies the whole batch or nothing; a stale [DraftPatch.expect] on any node it touches is a conflict. */
    fun patch(userId: String, target: String, batch: PatchBatch): PatchResult = db.tx {
        if (batch.patches.isEmpty() || batch.patches.size > MAX_PATCHES) throw ApiException(400, "bad_patch", "一次提交 1–$MAX_PATCHES 个改动")
        val current = find(userId, target) ?: throw ApiException(404, "no_draft", "还没有这个游戏的草稿")
        var body = current.body
        val conflicts = mutableListOf<PatchConflict>()
        batch.patches.forEach { p ->
            val path = p.path.split('/').filter { it.isNotEmpty() }
            // What a conflicting editor gets back is the node as stored, not as earlier patches of this batch left it.
            fun conflict(reason: String) {
                val stored = Nodes.find(current.body, path).getOrNull()
                conflicts += PatchConflict(p.path, reason, stored, stored?.let(::nodeDigest), stored?.let(::shallowDigest))
            }
            val node = Nodes.find(body, path).getOrElse { return@forEach conflict(it.message ?: "找不到节点") }
            try {
                body = when (p.op) {
                    "set" -> {
                        val value = p.value as? JsonObject ?: return@forEach conflict("set 需要一个对象")
                        if (path.isEmpty() || p.expect != shallowDigest(node)) return@forEach conflict("这个节点在别处改过了")
                        if (value.keys.any { it == "id" || value[it] is JsonArray || (value[it] as? JsonObject)?.let(::idOf) != null })
                            return@forEach conflict("set 只改节点自身的字段，不能改编号或子列表")
                        Nodes.update(body, path) { JsonObject(it + value) }
                    }
                    "put" -> {
                        val value = p.value as? JsonObject ?: return@forEach conflict("put 需要一个对象")
                        if (path.isEmpty()) return@forEach conflict("不能整体替换草稿根，请导入")
                        if (p.expect != nodeDigest(node)) return@forEach conflict("这个节点在别处改过了")
                        if (idOf(value) != idOf(node)) return@forEach conflict("put 不能改节点的编号，请删除后插入")
                        Nodes.update(body, path) { value }
                    }
                    "delete" -> {
                        if (path.isEmpty()) return@forEach conflict("不能删除草稿根")
                        if (p.expect != nodeDigest(node)) return@forEach conflict("这个节点在别处改过了")
                        Nodes.update(body, path) { null }
                    }
                    "insert" -> {
                        val value = p.value as? JsonObject ?: return@forEach conflict("insert 需要一个对象")
                        val field = p.field?.split('.')?.takeIf { f -> f.isNotEmpty() && f.none { it.isEmpty() || it == "id" || it == "action" } }
                            ?: return@forEach conflict("insert 需要列表字段")
                        val id = idOf(value)
                        if (id != null && Nodes.children(node).any { idOf(it.first) == id }) return@forEach conflict("这一层已有编号 $id")
                        Nodes.update(body, path) { Nodes.insert(it, field, value, p.after) }
                    }
                    else -> return@forEach conflict("未知操作 ${p.op}")
                }
            } catch (problem: IllegalStateException) { conflict(problem.message ?: "改动无法应用") }
        }
        // Nothing applies when anything conflicts; the editor shows each node as it is now.
        if (conflicts.isNotEmpty()) return@tx PatchResult(current.revision, conflicts)
        checkSize(body)
        val revision = current.revision + 1
        db.update("UPDATE dev_drafts SET revision = ?, body = ?, updated_at = ? WHERE user_id = ? AND target_id = ?",
            revision, json.encodeToString(JsonObject.serializer(), body), clock(), userId, target)
        record(userId, target, revision, batch.source, batch.patches)
        PatchResult(revision)
    }

    private fun record(userId: String, target: String, revision: Long, source: String, patches: List<DraftPatch>) {
        db.update("INSERT INTO dev_patches (user_id, target_id, revision, source, at, patches) VALUES (?, ?, ?, ?, ?, ?)",
            userId, target, revision, source.take(16), clock(), json.encodeToString(patches))
        db.update("DELETE FROM dev_patches WHERE user_id = ? AND target_id = ? AND revision <= ?", userId, target, revision - KEPT_PATCHES)
    }

    /** Patches after [since]; incomplete when older ones were dropped or more remain, and the editor should reload. */
    fun since(userId: String, target: String, since: Long): PatchHistory = db.tx {
        val records = db.query("SELECT revision, source, at, patches FROM dev_patches WHERE user_id = ? AND target_id = ? AND revision > ? ORDER BY revision LIMIT ?",
            userId, target, since, PAGE + 1) { r ->
            PatchRecord(r.getLong(1), r.getString(2), r.getLong(3), json.decodeFromString(r.getString(4)), r.getString(2) == "import")
        }
        val gapless = records.firstOrNull()?.revision?.let { it == since + 1 } ?: true
        PatchHistory(records.take(PAGE), gapless && records.size <= PAGE)
    }

    companion object {
        private const val MAX_PATCHES = 200
        private const val MAX_DRAFTS = 20
        private const val KEPT_PATCHES = 200
        private const val PAGE = 500
    }
}
