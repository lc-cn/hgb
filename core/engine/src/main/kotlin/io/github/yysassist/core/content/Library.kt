package io.github.yysassist.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * What a game's tasks share, written once: named regions, a page-state library and default inputs. Tasks refer to
 * them by name; reading the bundle resolves every reference into the literal form the engine checks, so the
 * published text stays small and a region or state is changed in one place.
 */
@Serializable
data class TargetLibrary(
    /** Name -> [x, y, width, height] on the target's reference frame. */
    val regions: Map<String, List<Int>> = emptyMap(),
    val states: List<StateDefinition> = emptyList(),
    /**
     * Default inputs: `"*"` for every action, a capability id for that capability, `"text"` / `"image"` for state
     * conditions (e.g. minimumConfidence / minimumScore). Only inputs an action declares and leaves out are filled.
     */
    val defaults: Map<String, JsonObject> = emptyMap(),
    /** Pages that can come up over any other (a lost connection, a popup): every page wait handles them first. */
    val interruptions: List<Interruption> = emptyList(),
)

/** How the state of an interruption page is named; a page wait matching it and another state reports the interruption. */
const val INTERRUPTION_STATE = "interruption:"

/**
 * An interruption page: its [state] (a library state's id, or one written in full) and the snippet [use] (with [with])
 * that clears it. A page wait meeting it runs that, then waits again; more than [maximum] times leaves the step unknown.
 */
@Serializable
data class Interruption(val id: String, val name: String? = null, val state: JsonElement, val use: String,
    val with: JsonObject = JsonObject(emptyMap()), val maximum: Int = 3) {
    /** The state id a guarded wait reports for this page; never one of a task's own states. */
    val stateId: String get() = "$INTERRUPTION_STATE$id"
}

/**
 * A named page state. [base] contributes its conditions first. Conditions are written either in full
 * (`{"text": "寮信息", "within": "HALL_INFO"}`) or as `"寮信息@HALL_INFO"` (for templates: `"foster-plus@MY_SLOT"`).
 * [params] make one definition serve several places, e.g. "card i is at max level" with the card's region.
 */
@Serializable
data class StateDefinition(
    val id: String,
    val name: String? = null,
    val base: String? = null,
    val params: List<SnippetParam> = emptyList(),
    val text: List<JsonElement> = emptyList(),
    val absentText: List<JsonElement> = emptyList(),
    val present: List<JsonElement> = emptyList(),
    val absent: List<JsonElement> = emptyList(),
)

/** Capabilities whose `states` input takes page states, written by name or in full. */
private val STATE_INPUTS = setOf("screen.awaitScreen", "screen.expectScreen", "screen.classifyScreen", "screen.classifyRegion")

/** Resolves library references and defaults in every action; the result carries no library. */
internal fun TaskBundle.resolveLibrary(capabilities: List<CapabilityContract>): Pair<TaskBundle, List<ContentIssue>> {
    val issues = mutableListOf<ContentIssue>()
    val libraries = targets.associate { it.id to (it.library ?: TargetLibrary()) }
    targets.forEach { target ->
        val library = target.library ?: return@forEach
        val ids = library.states.map { it.id }
        if (ids.distinct().size != ids.size) issues += ContentIssue("target/${target.id}/library", "状态身份重复")
        val interruptions = library.interruptions.map { it.id }
        if (interruptions.any { it.isBlank() } || interruptions.distinct().size != interruptions.size) issues += ContentIssue("target/${target.id}/library", "干扰页身份为空或重复")
        library.interruptions.filter { it.state !is JsonPrimitive && it.state !is JsonObject }
            .forEach { issues += ContentIssue("target/${target.id}/library", "干扰页 ${it.id} 的页面需写成单个状态") }
        library.interruptions.filter { it.maximum !in 1..10 }.forEach { issues += ContentIssue("target/${target.id}/library", "干扰页 ${it.id} 的处理次数需为 1–10") }
        library.regions.forEach { (name, rect) ->
            if (rect.size != 4 || rect[2] <= 0 || rect[3] <= 0 || rect[0] < 0 || rect[1] < 0) issues += ContentIssue("target/${target.id}/library", "区域 $name 需为 x,y,宽,高")
        }
    }
    val resolved = tasks.map { task ->
        val library = libraries[task.targetId] ?: TargetLibrary()
        task.copy(steps = task.steps.map { step ->
            val location = "target/${task.targetId}/task/${task.id}/step/${step.id}"
            val resolver = LibraryResolver(library, capabilities) { issues += ContentIssue(location, it) }
            step.copy(program = step.program?.let(resolver::nodes), actions = step.actions.map(resolver::action))
        })
    }
    return copy(tasks = resolved, targets = targets.map { it.copy(library = null) }) to issues
}

private class LibraryResolver(val library: TargetLibrary, val capabilities: List<CapabilityContract>, val issue: (String) -> Unit) {
    fun nodes(nodes: List<ProgramNode>): List<ProgramNode> = nodes.map { node -> when (node) {
        is ProgramNode.Action -> ProgramNode.Action(action(node.action))
        is ProgramNode.Branch -> node.copy(cases = node.cases.map { it.copy(program = nodes(it.program)) })
        is ProgramNode.Until -> node.copy(program = nodes(node.program))
        is ProgramNode.Repeat -> node.copy(program = nodes(node.program))
        is ProgramNode.Block -> node.copy(program = nodes(node.program))
        is ProgramNode.ForEach -> node.copy(program = nodes(node.program))
        is ProgramNode.Exit, is ProgramNode.UseSnippet -> node
        is ProgramNode.Guarded -> ProgramNode.Guarded(action(node.wait), node.guards.map { guard ->
            guard.copy(state = (states(JsonArray(listOf(guard.state))) as JsonArray).single(), program = nodes(guard.program)) })
    } }

    fun action(action: ActionDefinition): ActionDefinition {
        val contract = capabilities.singleOrNull { it.id == action.capability && it.version == action.contractVersion }
        var inputs = regions(action.inputs) as JsonObject
        if (action.capability in STATE_INPUTS && "states" in inputs) inputs = JsonObject(inputs + ("states" to states(inputs["states"])))
        // Defaults: only declared inputs, never ones given as a constant or bound to an output or setting.
        if (contract != null) {
            val filled = inputs.toMutableMap()
            listOfNotNull(library.defaults["*"], library.defaults[action.capability]).forEach { defaults ->
                defaults.forEach { (key, value) ->
                    if (key in contract.inputTypes && key !in filled && key !in action.bindings) filled[key] = value
                }
            }
            inputs = JsonObject(filled)
        }
        return action.copy(inputs = inputs)
    }

    /** `"within": "NAME"` anywhere becomes the named rectangle. */
    private fun regions(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::regions))
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "within" && value is JsonPrimitive && value.isString) region(value.content) else regions(value)
        })
        else -> element
    }
    private fun region(name: String): JsonElement = library.regions[name]?.let { rect -> JsonArray(rect.map(::JsonPrimitive)) }
        ?: run { issue("引用了未定义的区域 $name"); JsonPrimitive(name) }

    /** A state written as a name or `{"state": id, "with": {...}, "as": id}` becomes its conditions. */
    private fun states(element: JsonElement?): JsonElement {
        val list = element as? JsonArray ?: return element ?: JsonArray(emptyList())
        return JsonArray(list.map { entry -> when {
            entry is JsonPrimitive && entry.isString -> state(entry.content, JsonObject(emptyMap()), entry.content)
            entry is JsonObject && "state" in entry -> {
                val id = (entry["state"] as? JsonPrimitive)?.content.orEmpty()
                state(id, entry["with"] as? JsonObject ?: JsonObject(emptyMap()), (entry["as"] as? JsonPrimitive)?.content ?: id)
            }
            entry is JsonObject -> inline(entry)
            else -> entry
        } })
    }

    /** A state written in full in the action: its conditions get the same shorthand and defaults as library ones. */
    private fun inline(entry: JsonObject): JsonObject = JsonObject(entry.mapValues { (key, value) -> when {
        key in setOf("text", "absentText") && value is JsonArray -> JsonArray(value.map { condition(it, "text") })
        key in setOf("present", "absent") && value is JsonArray -> JsonArray(value.map { condition(it, "image") })
        else -> regions(value)
    } })

    private fun state(id: String, with: JsonObject, name: String, seen: Set<String> = emptySet()): JsonElement {
        val definition = library.states.singleOrNull { it.id == id } ?: run { issue("引用了未定义的状态 $id"); return JsonObject(mapOf("id" to JsonPrimitive(name))) }
        if (id in seen) { issue("状态 $id 循环派生"); return JsonObject(mapOf("id" to JsonPrimitive(name))) }
        val values = definition.params.associate { param ->
            param.name to (with[param.name] ?: param.default ?: run { issue("状态 $id 缺少参数 ${param.name}"); JsonNull })
        }
        val base = definition.base?.let { state(it, with, name, seen + id) as? JsonObject }
        fun list(key: String, own: List<JsonElement>, kind: String): JsonArray =
            JsonArray(((base?.get(key) as? JsonArray).orEmpty()) + own.map { condition(substitute(it, values), kind) })
        return JsonObject(buildMap {
            put("id", JsonPrimitive(name))
            listOf("text" to (definition.text to "text"), "absentText" to (definition.absentText to "text"),
                "present" to (definition.present to "image"), "absent" to (definition.absent to "image")).forEach { (key, pair) ->
                val built = list(key, pair.first, pair.second)
                if (built.isNotEmpty()) put(key, built)
            }
        })
    }

    private fun substitute(element: JsonElement, values: Map<String, JsonElement>): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { substitute(it, values) })
        is JsonObject -> if (element.keys == setOf("\$param")) values[(element["\$param"] as? JsonPrimitive)?.content] ?: run { issue("状态参数未提供"); JsonNull }
            else JsonObject(element.mapValues { (_, v) -> substitute(v, values) })
        else -> element
    }

    /** `"字@区域"` or a full object; fills the confidence / score default and resolves the region name. */
    private fun condition(element: JsonElement, kind: String): JsonElement {
        val full = if (element is JsonPrimitive && element.isString) {
            val (subject, place) = element.content.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            JsonObject(buildMap {
                put(if (kind == "image") "template" else "text", JsonPrimitive(subject))
                place?.let { put("within", JsonPrimitive(it)) }
            })
        } else element
        val withRegion = regions(full)
        val defaults = library.defaults[kind] ?: return withRegion
        return if (withRegion is JsonObject) JsonObject(defaults.filterKeys { it !in withRegion } + withRegion) else withRegion
    }
}
