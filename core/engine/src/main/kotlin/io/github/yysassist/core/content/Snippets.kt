package io.github.yysassist.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal

/**
 * A reusable program with typed parameters, such as "press friend row N" or "return to the courtyard". A step may
 * be one (`use` + `with`) or call one anywhere in its program ([ProgramNode.UseSnippet]). Expanded when the bundle
 * is read: parameters and forEach items are substituted as constants, ids get the call's prefix, and the result is
 * validated like any hand-written program. So a parameter can feed inputs the App requires to be literal (offsets,
 * regions, page states), and can never be a runtime output.
 */
@Serializable
data class Snippet(
    val id: String,
    val params: List<SnippetParam> = emptyList(),
    /** Name -> an output of an action inside, readable after the call as an output of the call's id. */
    val exports: Map<String, SnippetExport> = emptyMap(),
    val program: List<ProgramNode>,
)
@Serializable
data class SnippetParam(val name: String, val type: SnippetParamType, val default: JsonElement? = null)
@Serializable
enum class SnippetParamType { TEXT, NUMBER, BOOLEAN, REGION, ANY }
@Serializable
data class SnippetExport(val action: String, val field: String)

private const val PARAM = "\$param"
private const val ITEM = "\$item"
private val ARITHMETIC = setOf("\$add", "\$sub", "\$mul")
private const val MAX_DEPTH = 16

/** Expands snippets and forEach in every step; the result has neither, nor any `$` reference. */
internal fun TaskBundle.expandSnippets(): Pair<TaskBundle, List<ContentIssue>> {
    val issues = mutableListOf<ContentIssue>()
    if (snippets.any { it.id.isBlank() } || snippets.map { it.id }.distinct().size != snippets.size)
        issues += ContentIssue("snippets", "片段身份为空或重复")
    snippets.filter { it.program.isEmpty() }.forEach { issues += ContentIssue("snippet/${it.id}", "片段程序不能为空") }
    snippets.forEach { snippet ->
        if (snippet.params.map { it.name }.distinct().size != snippet.params.size) issues += ContentIssue("snippet/${snippet.id}", "片段参数重名")
    }
    val used = mutableSetOf<String>()
    val expanded = tasks.map { task ->
        task.copy(steps = task.steps.map { step ->
            val location = "target/${task.targetId}/task/${task.id}/step/${step.id}"
            val expander = Expander(snippets.associateBy { it.id }, used, issues, location)
            val id = step.use
            if (id == null) {
                if (step.with != null) issues += ContentIssue(location, "with只能和use一起使用")
                val program = step.program ?: return@map step
                step.copy(program = expander.expand(program, Scope.ROOT))
            } else {
                if (step.program != null || step.actions.isNotEmpty()) issues += ContentIssue(location, "use与actions、program不能同时指定")
                val snippet = snippets.firstOrNull { it.id == id }
                if (snippet == null) { issues += ContentIssue(location, "引用了未声明的片段"); return@map step }
                used += id
                val scope = expander.arguments(snippet, step.with ?: JsonObject(emptyMap()), Scope.ROOT) ?: return@map step
                step.copy(use = null, with = null, actions = emptyList(),
                    program = expander.expand(snippet.program, scope.copy(locals = snippet.program.localIds(), stack = listOf(id))))
            }
        })
    }
    snippets.filter { it.id !in used }.forEach { issues += ContentIssue("snippet/${it.id}", "片段未被任何任务使用") }
    return copy(tasks = expanded, snippets = emptyList()) to issues
}

/** Ids defined anywhere inside these nodes: actions and named loops. */
private fun List<ProgramNode>.localIds(): Set<String> = walk().mapNotNull { node -> when (node) {
    is ProgramNode.Action -> node.action.id
    is ProgramNode.UseSnippet -> node.id
    is ProgramNode.ForEach -> node.id
    else -> node.loopId()
} }.toSet()

/**
 * What one level of expansion sees: parameter values, the current forEach item, the copy index of each enclosing
 * forEach, the id prefix, which ids belong to the program being copied, and the snippet call chain.
 */
private data class Scope(
    val params: Map<String, JsonElement>, val item: JsonObject?, val indexes: Map<String, Int>,
    val prefix: String, val locals: Set<String>, val stack: List<String>, val inSnippet: Boolean,
    /** Where ids not defined here resolve: the enclosing program for a forEach copy, nothing for a snippet. */
    val outer: Scope? = null,
    /** Loops an exit may name inside the current snippet; null outside snippets. A snippet cannot end a loop around its call. */
    val snippetLoops: Set<String>? = null,
    val depth: Int = 0,
) {
    fun rename(id: String): String = if (id in locals) prefix + id else outer?.rename(id) ?: id
    companion object { val ROOT = Scope(emptyMap(), null, emptyMap(), "", emptySet(), emptyList(), false) }
}

private class Expander(
    val snippets: Map<String, Snippet>, val used: MutableSet<String>, val issues: MutableList<ContentIssue>, val location: String,
) {
    /** Snippet exports seen so far in this step: (call id, export name) -> the expanded action output. */
    private val aliases = mutableMapOf<Pair<String, String>, Pair<String, String>>()
    /** Prefixes of expanded snippet calls: their insides are reachable only through exports. */
    private val calls = mutableSetOf<String>()
    private fun issue(reason: String) { issues += ContentIssue(location, reason) }

    fun arguments(snippet: Snippet, with: JsonObject, outer: Scope): Scope? {
        val unknown = with.keys - snippet.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) { issue("片段 ${snippet.id} 没有参数：${unknown.joinToString()}"); return null }
        val values = snippet.params.associate { param ->
            val given = with[param.name]?.let { value(it, outer) } ?: param.default
            if (given == null) { issue("片段 ${snippet.id} 缺少参数 ${param.name}"); return null }
            if (!fits(param.type, given)) { issue("片段 ${snippet.id} 的参数 ${param.name} 类型不符"); return null }
            param.name to given
        }
        return Scope(values, null, emptyMap(), outer.prefix, emptySet(), outer.stack, true)
    }

    private fun fits(type: SnippetParamType, value: JsonElement): Boolean = when (type) {
        SnippetParamType.ANY -> true
        SnippetParamType.TEXT -> value is JsonPrimitive && value.isString
        SnippetParamType.NUMBER -> value is JsonPrimitive && !value.isString && value.decimalOrNull() != null
        SnippetParamType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        // A rectangle, or the name of one in the game's library (checked when the library is resolved).
        SnippetParamType.REGION -> value is JsonArray && value.size == 4 && value.all { it is JsonPrimitive && it.decimalOrNull() != null } ||
            value is JsonPrimitive && value.isString
        // A configuration binding passed through to an action input.
    } || isConfiguration(value)

    private fun isConfiguration(value: JsonElement) = value is JsonObject && value["type"] == JsonPrimitive("configuration")

    /** Substitutes `$param`, `$item` and arithmetic; everything else is copied as written. */
    fun value(element: JsonElement, scope: Scope): JsonElement? = when (element) {
        is JsonArray -> JsonArray(element.map { value(it, scope) ?: return null })
        is JsonObject -> when {
            element.keys == setOf(PARAM) -> {
                val name = (element[PARAM] as? JsonPrimitive)?.contentOrNull
                if (!scope.inSnippet) { issue("参数只能在片段里引用"); null }
                else scope.params[name] ?: run { issue("引用了未声明的片段参数 $name"); null }
            }
            element.keys == setOf(ITEM) -> {
                val field = (element[ITEM] as? JsonPrimitive)?.contentOrNull
                val item = scope.item
                if (item == null) { issue("表项只能在逐项循环里引用"); null }
                else item[field] ?: run { issue("表项没有字段 $field"); null }
            }
            element.size == 1 && element.keys.single() in ARITHMETIC -> arithmetic(element, scope)
            else -> JsonObject(element.mapValues { (_, v) -> value(v, scope) ?: return null })
        }
        else -> element
    }

    private fun arithmetic(element: JsonObject, scope: Scope): JsonElement? {
        val (operator, operands) = element.entries.single()
        val numbers = (operands as? JsonArray)?.map { operand ->
            (value(operand, scope) as? JsonPrimitive)?.takeUnless { it.isString }?.decimalOrNull() ?: run { issue("算式只能用数字"); return null }
        }
        if (numbers == null || numbers.size < 2) { issue("算式至少需要两个数"); return null }
        val result = numbers.drop(1).fold(numbers.first()) { left, right -> when (operator) {
            "\$add" -> left + right
            "\$sub" -> left - right
            else -> left * right
        } }
        return JsonPrimitive(result.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it })
    }

    private fun binding(source: ActionInputBinding, scope: Scope): ActionInputBinding = when (source) {
        is ActionInputBinding.Configuration -> source
        is ActionInputBinding.Output -> {
            val renamed = scope.rename(source.actionId)
            val exported = aliases[renamed to source.field]
            if (exported == null && source.actionId !in scope.locals && calls.any { source.actionId.startsWith(it) })
                issue("片段内部的动作只能通过片段导出引用：${source.actionId}")
            exported?.let { (action, field) -> ActionInputBinding.Output(action, field) } ?: ActionInputBinding.Output(renamed, source.field)
        }
    }

    fun expand(nodes: List<ProgramNode>, scope: Scope): List<ProgramNode> {
        if (scope.stack.size + scope.depth > MAX_DEPTH) { issue("片段或逐项嵌套过深"); return emptyList() }
        return nodes.flatMap { node -> when (node) {
            is ProgramNode.Action -> {
                val action = node.action
                val inputs = mutableMapOf<String, JsonElement>()
                val bindings = action.bindings.mapValues { (_, source) -> binding(source, scope) }.toMutableMap()
                action.inputs.forEach { (key, raw) ->
                    val resolved = value(raw, scope) ?: return@forEach
                    // A parameter given a configuration binding becomes a binding, not a literal.
                    if (isConfiguration(resolved)) bindings[key] = Json.decodeFromJsonElement(ActionInputBinding.serializer(), resolved)
                    else inputs[key] = resolved
                }
                listOf(ProgramNode.Action(action.copy(id = scope.rename(action.id), inputs = JsonObject(inputs), bindings = bindings)))
            }
            is ProgramNode.Branch -> listOf(ProgramNode.Branch(binding(node.value, scope), node.cases.map { case ->
                ProgramCase((value(case.equals, scope) as? JsonPrimitive) ?: case.equals, expand(case.program, scope), case.id?.let { scope.prefix + it })
            }, node.id?.let { scope.prefix + it }))
            is ProgramNode.Until -> listOf(ProgramNode.Until(binding(node.value, scope), (value(node.equals, scope) as? JsonPrimitive) ?: node.equals,
                value(node.maximum, scope) ?: node.maximum, expand(node.program, scope), node.id?.let(scope::rename),
                node.or.map { UntilCondition(binding(it.value, scope), (value(it.equals, scope) as? JsonPrimitive) ?: it.equals) }))
            is ProgramNode.Repeat -> listOf(ProgramNode.Repeat(value(node.count, scope) ?: node.count, expand(node.program, scope), node.id?.let(scope::rename)))
            is ProgramNode.Block -> listOf(ProgramNode.Block(scope.rename(node.id), expand(node.program, scope)))
            is ProgramNode.Exit -> {
                if (scope.snippetLoops != null && node.loop !in scope.snippetLoops) issue("片段不能退出片段外的循环 ${node.loop}")
                listOf(ProgramNode.Exit(scope.rename(node.loop), scope.indexes[node.loop] ?: node.index))
            }
            is ProgramNode.ForEach -> {
                if (node.items.isEmpty()) issue("逐项循环 ${node.id} 的表不能为空")
                if (node.items.map { it.keys }.distinct().size > 1) issue("逐项循环 ${node.id} 的各项字段必须一致")
                val id = scope.rename(node.id)
                val locals = node.program.localIds()
                val copies = node.items.mapIndexed { index, item ->
                    val entry = value(item, scope) as? JsonObject ?: item
                    // Ids inside copy i get "id#i." in front; ids outside the loop resolve as they do outside.
                    expand(node.program, scope.copy(item = entry, indexes = scope.indexes + (node.id to index),
                        prefix = "$id#$index.", locals = locals, outer = scope, depth = scope.depth + 1))
                }
                listOf(ProgramNode.Block(id, copies.flatten()))
            }
            is ProgramNode.UseSnippet -> {
                val snippet = snippets[node.use]
                val call = scope.rename(node.id)
                when {
                    snippet == null -> { issue("引用了未声明的片段 ${node.use}"); emptyList() }
                    node.use in scope.stack -> { issue("片段 ${node.use} 循环引用了自己"); emptyList() }
                    else -> {
                        used += node.use
                        val inner = arguments(snippet, node.with, scope) ?: return@flatMap emptyList()
                        val loops = snippet.program.walk().mapNotNull { it.loopId() ?: (it as? ProgramNode.ForEach)?.id }.toSet()
                        val expanded = expand(snippet.program, inner.copy(prefix = "$call.", locals = snippet.program.localIds(),
                            stack = scope.stack + node.use, indexes = emptyMap(), outer = null, snippetLoops = loops))
                        calls += "$call."
                        // An export names a top-level action, or re-exports what an inner call exported. Not one inside a loop:
                        // which copy's output would it be?
                        val topActions = snippet.program.filterIsInstance<ProgramNode.Action>().map { it.action.id }.toSet()
                        val innerCalls = snippet.program.filterIsInstance<ProgramNode.UseSnippet>().map { it.id }.toSet()
                        snippet.exports.forEach { (name, export) ->
                            when (export.action) {
                                in topActions -> aliases[call to name] = "$call.${export.action}" to export.field
                                in innerCalls -> aliases["$call.${export.action}" to export.field]?.let { aliases[call to name] = it }
                                    ?: issue("片段 ${snippet.id} 转导出的 ${export.action}.${export.field} 不存在")
                                else -> issue("片段 ${snippet.id} 只能导出顶层动作或内层片段的导出：${export.action}")
                            }
                        }
                        expanded
                    }
                }
            }
        } }
    }
}
