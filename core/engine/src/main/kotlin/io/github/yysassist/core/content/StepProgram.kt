package io.github.yysassist.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal

/** Data-only control flow. Blocks own their outputs; a step remains the reentry unit. */
@Serializable
sealed interface ProgramNode {
    @Serializable @SerialName("action")
    data class Action(val action: ActionDefinition) : ProgramNode
    @Serializable @SerialName("branch")
    data class Branch(val value: ActionInputBinding, val cases: List<ProgramCase>) : ProgramNode
    @Serializable @SerialName("repeat")
    data class Repeat(val count: JsonElement, val program: List<ProgramNode>) : ProgramNode
    /**
     * Runs [program] until [value] (an output of one of its own top-level actions) equals [equals], at
     * most [maximum] times. Reaching the maximum is never success: the step becomes unknown.
     */
    @Serializable @SerialName("until")
    data class Until(val value: ActionInputBinding, val equals: JsonPrimitive, val maximum: JsonElement, val program: List<ProgramNode>) : ProgramNode
}
@Serializable
data class ProgramCase(val equals: JsonPrimitive, val program: List<ProgramNode>)

/** Definitely available definitions and outputs newer than every possible preceding input. */
data class ActionFlowContext(val previous: Map<String, ActionDefinition>, val freshOutputs: Set<String>)

fun StepDefinition.nodes(): List<ProgramNode> = program ?: actions.map(ProgramNode::Action)
fun StepDefinition.actionDefinitions(): List<ActionDefinition> = nodes().allActions()
private fun List<ProgramNode>.allActions(): List<ActionDefinition> = flatMap { node -> when (node) {
    is ProgramNode.Action -> listOf(node.action)
    is ProgramNode.Branch -> node.cases.flatMap { it.program.allActions() }
    is ProgramNode.Repeat -> node.program.allActions()
    is ProgramNode.Until -> node.program.allActions()
} }
internal fun List<ProgramNode>.repeats(): List<ProgramNode.Repeat> = flatMap { node -> when (node) {
    is ProgramNode.Action -> emptyList()
    is ProgramNode.Branch -> node.cases.flatMap { it.program.repeats() }
    is ProgramNode.Repeat -> listOf(node) + node.program.repeats()
    is ProgramNode.Until -> node.program.repeats()
} }
internal fun List<ProgramNode>.untils(): List<ProgramNode.Until> = flatMap { node -> when (node) {
    is ProgramNode.Action -> emptyList()
    is ProgramNode.Branch -> node.cases.flatMap { it.program.untils() }
    is ProgramNode.Repeat -> node.program.untils()
    is ProgramNode.Until -> listOf(node) + node.program.untils()
} }
/** Loop bound: a literal or required numeric configuration, at least one. */
fun untilMaximum(value: JsonElement, configuration: JsonObject): Long = repeatCount(value, configuration).also { require(it in 1..10_000) }

fun resolveProgramValue(source: ActionInputBinding, configuration: JsonObject, outputs: Map<String, JsonObject>): JsonElement = when (source) {
    is ActionInputBinding.Configuration -> requireNotNull(configuration[source.fieldId]) { "配置引用不可用" }
    is ActionInputBinding.Output -> requireNotNull(outputs[source.actionId]?.get(source.field)) { "前序动作输出不可用" }
}
internal fun repeatConfiguration(value: JsonElement): String? = (value as? JsonObject)?.takeIf {
    it.keys == setOf("type", "fieldId") && it["type"] == JsonPrimitive("configuration")
}?.get("fieldId")?.let { it as? JsonPrimitive }?.takeIf { it.isString && it.content.isNotBlank() }?.content

fun repeatCount(value: JsonElement, configuration: JsonObject): Long {
    val actual = if (value is JsonObject) requireNotNull(configuration[requireNotNull(repeatConfiguration(value))]) else value
    val number = (actual as? JsonPrimitive)?.decimalOrNull()
    return requireNotNull(number).longValueExact().also { require(it >= 0) }
}
private fun primitiveType(value: JsonElement): ActionValueType? = when {
    value !is JsonPrimitive || value == JsonNull -> null
    value.isString -> ActionValueType.STRING
    value.booleanOrNull != null -> ActionValueType.BOOLEAN
    value.decimalOrNull() != null -> ActionValueType.NUMBER
    else -> null
}
fun sameProgramValue(left: JsonElement, right: JsonElement): Boolean {
    val type = primitiveType(left) ?: return false
    if (type != primitiveType(right)) return false
    return if (type == ActionValueType.NUMBER) (left as JsonPrimitive).decimalOrNull()!!.compareTo((right as JsonPrimitive).decimalOrNull()!!) == 0
        else left == right
}

/** Handle kinds an action consumed, directly or through the handles it was given. */
private fun ancestry(action: ActionDefinition, context: ActionFlowContext, contract: (ActionDefinition) -> CapabilityContract?,
    seen: Set<String> = emptySet()): Set<String> {
    if (action.id in seen) return emptySet()
    val definition = contract(action) ?: return emptySet()
    return definition.inputHandles.keys.flatMap { parameter ->
        val binding = action.bindings[parameter] as? ActionInputBinding.Output ?: return@flatMap emptyList()
        val source = context.previous[binding.actionId] ?: return@flatMap emptyList()
        listOfNotNull(contract(source)?.outputHandles?.get(binding.field)) + ancestry(source, context, contract, seen + action.id)
    }.toSet()
}

/** Generic handle rules: typed source, still fresh, permitted lineage, and no leaking into plain values. */
internal fun handleIssues(action: ActionDefinition, definition: CapabilityContract, context: ActionFlowContext,
    contract: (ActionDefinition) -> CapabilityContract?): List<String> = buildList {
    definition.inputHandles.forEach { (parameter, need) ->
        val binding = action.bindings[parameter] as? ActionInputBinding.Output
        if (binding == null) { add("观察、目标或区域只能引用本步骤前序动作的输出"); return@forEach }
        val source = context.previous[binding.actionId] ?: return@forEach // reported by binding checks
        val kind = contract(source)?.outputHandles?.get(binding.field)
        when {
            kind == null || kind !in need.kinds -> add("引用的输出不是此参数所需的观察、目标或区域")
            source.id !in context.freshOutputs && !need.allowStale -> add("引用的观察、目标或区域产生于可能的输入之前，已失效")
            // Lineage only: a region handle may itself be required while region-derived sources are refused.
            ancestry(source, context, contract).intersect(need.forbiddenAncestors).isNotEmpty() -> add("引用的句柄来源不允许用于此动作")
        }
    }
    action.bindings.forEach { (parameter, binding) ->
        if (binding !is ActionInputBinding.Output || parameter in definition.inputHandles) return@forEach
        val source = context.previous[binding.actionId] ?: return@forEach
        if (contract(source)?.outputHandles?.containsKey(binding.field) == true) add("观察、目标或区域不能作为普通参数传递")
    }
}

/** A bounded abstract state, not a list of all branch paths or an unrolling of repeat counts. */
private enum class Proof { NONE, VERIFIED, UNVERIFIED }
private data class Flow(val context: ActionFlowContext, val proofs: Set<Proof>)

internal fun programIssues(bundle: TaskBundle, task: TaskDefinition, step: StepDefinition,
    contracts: List<CapabilityContract>): List<ContentIssue> = buildList {
    fun issue(path: String, reason: String) { add(ContentIssue(path, reason)) }
    if (step.program != null && step.actions.isNotEmpty()) issue("program", "program与actions不能同时指定")
    if (step.nodes().isEmpty()) issue("program", "步骤程序不能为空")
    val actions = step.actionDefinitions()
    if (actions.map { it.id }.distinct().size != actions.size) issue("program", "动作身份重复")
    fun contract(action: ActionDefinition) = contracts.singleOrNull { it.id == action.capability && it.version == action.contractVersion }
    fun block(nodes: List<ProgramNode>, initial: Flow, path: String): Flow {
        var flow = initial
        nodes.forEachIndexed { index, node ->
            val location = "$path/$index"
            when (node) {
                is ProgramNode.Action -> {
                    val action = node.action
                    if (action.id.isBlank()) issue(location, "缺少动作身份")
                    val definition = contract(action)
                    if (definition == null) issue(location, "容器不支持所需动作或合同版本")
                    else {
                        val previous = flow.context.previous.mapNotNull { (id, a) -> contract(a)?.let { id to it } }.toMap()
                        actionBindingIssues(task, action, definition, previous).forEach { issue(location, it) }
                        handleIssues(action, definition, flow.context, ::contract).forEach { issue(location, it) }
                        definition.validateDefinition(bundle, task, flow.context, action)?.let { issue(location, it) }
                        val proofs = when {
                            // A result line (task.result) only records what happened; it leaves the proof as it was.
                            action.capability == "task.result" -> flow.proofs
                            definition.role == ActionRole.VERIFICATION -> flow.proofs.map {
                                if (it == Proof.NONE && !definition.sendsDeviceInput) Proof.NONE else Proof.VERIFIED
                            }.toSet()
                            definition.role == ActionRole.OBSERVATION || definition.sendsDeviceInput -> setOf(Proof.UNVERIFIED)
                            else -> flow.proofs.map { if (it == Proof.NONE) it else Proof.UNVERIFIED }.toSet()
                        }
                        val fresh = if (definition.sendsDeviceInput) emptySet() else flow.context.freshOutputs
                        flow = Flow(ActionFlowContext(flow.context.previous + (action.id to action), fresh + action.id), proofs)
                    }
                }
                is ProgramNode.Branch -> {
                    val source = node.value as? ActionInputBinding.Output
                    val configured = node.value as? ActionInputBinding.Configuration
                    val type = if (configured != null) ActionValueType.STRING
                        else source?.let { binding -> flow.context.previous[binding.actionId]?.let(::contract)?.outputTypes?.get(binding.field) }
                    if (configured != null) {
                        // A case left out on purpose stops the step as unknown when the user picks that option.
                        val field = task.fields.singleOrNull { it.id == configured.fieldId }
                        if (field == null || !field.required || field.widget != FieldWidget.SINGLE_CHOICE) issue(location, "配置分支必须引用必填的单选字段")
                        else if (node.cases.any { case -> field.options.none { it.value == case.equals.content } }) issue(location, "分支值不是该配置字段的选项")
                    } else if (type !in setOf(ActionValueType.STRING, ActionValueType.NUMBER, ActionValueType.BOOLEAN))
                        issue(location, "分支必须引用本块前序动作的字符串、布尔或数值输出")
                    if (source != null && flow.context.previous[source.actionId]?.let(::contract)?.outputHandles?.containsKey(source.field) == true)
                        issue(location, "分支不能比较观察、目标或区域句柄")
                    if (node.cases.isEmpty()) issue(location, "分支缺少明确值")
                    node.cases.forEachIndexed { caseIndex, case ->
                        if (primitiveType(case.equals) != type || primitiveType(case.equals) == null) issue(location, "分支值类型不匹配")
                        if (node.cases.take(caseIndex).any { sameProgramValue(it.equals, case.equals) }) issue(location, "分支值重复")
                    }
                    val ends = node.cases.mapIndexed { i, case -> block(case.program, flow, "$location/case/$i") }
                    if (ends.isNotEmpty()) flow = Flow(flow.context.copy(freshOutputs =
                        ends.fold(flow.context.freshOutputs) { fresh, end -> fresh.intersect(end.context.freshOutputs) }),
                        ends.flatMap { it.proofs }.toSet())
                }
                is ProgramNode.Until -> {
                    val fieldId = repeatConfiguration(node.maximum)
                    val field = task.fields.singleOrNull { it.id == fieldId }
                    val literal = if (node.maximum is JsonObject) null else runCatching { untilMaximum(node.maximum, JsonObject(emptyMap())) }.getOrNull()
                    if (fieldId == null && literal == null || node.maximum is JsonObject && (field == null || !field.required ||
                            field.valueType() != ActionValueType.NUMBER || field.minimum?.decimalOrNull()?.let { it >= BigDecimal.ONE } != true))
                        issue(location, "循环上限需为 1–10000 的整数，或最小值不小于 1 的必填数值配置")
                    if (node.program.isEmpty()) issue(location, "循环体不能为空")
                    val source = node.value as? ActionInputBinding.Output
                    val producer = source?.let { s -> node.program.filterIsInstance<ProgramNode.Action>().map { it.action }.singleOrNull { it.id == s.actionId } }
                    val type = producer?.let(::contract)?.outputTypes?.get(source.field)
                    if (producer == null || type !in setOf(ActionValueType.STRING, ActionValueType.NUMBER, ActionValueType.BOOLEAN))
                        issue(location, "循环条件必须引用循环体顶层动作的字符串、布尔或数值输出")
                    else if (producer.let(::contract)?.outputHandles?.containsKey(source.field) == true) issue(location, "循环条件不能比较观察、目标或区域句柄")
                    if (type != null && primitiveType(node.equals) != type) issue(location, "循环条件值类型不匹配")
                    // Every iteration starts from the outer flow with earlier-iteration locals discarded.
                    val first = block(node.program, flow, "$location/until")
                    val reentry = Flow(flow.context.copy(freshOutputs = flow.context.freshOutputs.intersect(first.context.freshOutputs)), first.proofs)
                    val mayRepeat = literal?.let { it > 1 } ?: true
                    val next = if (mayRepeat && reentry != flow) block(node.program, reentry, "$location/until") else first
                    val possible = if (mayRepeat) listOf(first, next) else listOf(first)
                    flow = Flow(flow.context.copy(freshOutputs = possible.fold(flow.context.freshOutputs) { fresh, end -> fresh.intersect(end.context.freshOutputs) }),
                        possible.flatMap { it.proofs }.toSet())
                }
                is ProgramNode.Repeat -> {
                    val fieldId = repeatConfiguration(node.count)
                    val field = task.fields.singleOrNull { it.id == fieldId }
                    val literal = if (node.count is JsonObject) null else runCatching { repeatCount(node.count, JsonObject(emptyMap())) }.getOrNull()
                    if (fieldId == null && literal == null || node.count is JsonObject && (field == null || !field.required || field.valueType() != ActionValueType.NUMBER))
                        issue(location, "重复次数需为非负整数或必填数值配置引用")
                    val mayZero = literal == 0L || literal == null && (field?.minimum?.decimalOrNull()?.let { it <= BigDecimal.ZERO } != false)
                    val mayRun = literal?.let { it > 0 } ?: (field?.maximum?.decimalOrNull()?.let { it >= BigDecimal.ONE } != false)
                    val mayRepeat = literal?.let { it > 1 } ?: (field?.maximum?.decimalOrNull()?.let { it >= BigDecimal(2) } != false)
                    // Check even a zero-count body. A second iteration starts with no previous
                    // iteration's local outputs, and with any outer observation invalidated by input.
                    val first = block(node.program, flow, "$location/repeat")
                    val reentry = Flow(flow.context.copy(freshOutputs = flow.context.freshOutputs.intersect(first.context.freshOutputs)), first.proofs)
                    val next = if (mayRepeat && reentry != flow) block(node.program, reentry, "$location/repeat") else first
                    val possible = buildList { if (mayZero) add(flow); if (mayRun) { add(first); if (mayRepeat) add(next) } }
                    if (possible.isNotEmpty()) flow = Flow(flow.context.copy(freshOutputs =
                        possible.fold(flow.context.freshOutputs) { fresh, end -> fresh.intersect(end.context.freshOutputs) }),
                        possible.flatMap { it.proofs }.toSet())
                }
            }
        }
        return flow
    }
    val end = block(step.nodes(), Flow(ActionFlowContext(emptyMap(), emptySet()), setOf(Proof.NONE)), "program")
    if (Proof.UNVERIFIED in end.proofs) issue("program", "观察步骤必须在每条执行路径以核对动作结束")
}.distinct()
