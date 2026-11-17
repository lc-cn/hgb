package io.github.yysassist.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** References are structured data. No interpolation, executable strings or cross-step scratchpad. */
@Serializable
sealed interface ActionInputBinding {
    @Serializable @SerialName("configuration")
    data class Configuration(val fieldId: String) : ActionInputBinding
    @Serializable @SerialName("output")
    data class Output(val actionId: String, val field: String) : ActionInputBinding
}

enum class ActionValueType {
    STRING, NUMBER, BOOLEAN, OBJECT, ARRAY;
    fun accepts(value: JsonElement): Boolean = when (this) {
        STRING -> value is JsonPrimitive && value.isString
        NUMBER -> value is JsonPrimitive && value.decimalOrNull() != null
        BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        OBJECT -> value is JsonObject
        ARRAY -> value is JsonArray
    }
}

internal fun ConfigurationField.valueType(): ActionValueType = when (widget) {
    FieldWidget.NUMBER, FieldWidget.RANGE -> ActionValueType.NUMBER
    FieldWidget.MULTI_CHOICE -> ActionValueType.ARRAY
    FieldWidget.DATE_RANGE -> ActionValueType.OBJECT
    else -> ActionValueType.STRING
}

fun CapabilityContract.inputIssue(values: JsonObject): String? {
    if (inputTypes.isNotEmpty() && values.keys != inputTypes.keys) return "动作参数缺失或包含未知参数"
    return knownInputIssue(values)?.reason ?: validateInputs(values)
}

fun CapabilityContract.knownInputIssue(values: JsonObject): ContentIssue? {
    if (inputTypes.isNotEmpty()) values.forEach { (key, value) ->
        val type = inputTypes[key] ?: return ContentIssue(key, "动作包含未知参数")
        if (!type.accepts(value)) return ContentIssue(key, "动作参数类型不匹配")
    }
    return validateKnownInputs(values)
}

fun CapabilityContract.outputIssue(values: JsonObject): String? = when {
    values.keys != outputTypes.keys -> "动作输出缺失或包含未声明字段"
    outputTypes.any { (key, type) -> !type.accepts(values.getValue(key)) } -> "动作输出类型不匹配"
    else -> null
}

internal fun actionBindingIssues(task: TaskDefinition, action: ActionDefinition, contract: CapabilityContract,
    previous: Map<String, CapabilityContract>): List<String> = buildList {
    if (action.bindings.isEmpty()) { contract.inputIssue(action.inputs)?.let(::add); return@buildList }
    if (action.inputs.keys.intersect(action.bindings.keys).isNotEmpty()) add("同一动作参数不能同时提供常量与引用")
    if (contract.inputTypes.keys != action.inputs.keys + action.bindings.keys) add("动作参数缺失、未知或未声明引用类型")
    contract.knownInputIssue(action.inputs)?.let { add(it.reason) }
    action.bindings.forEach { (key, source) ->
        val type = when (source) {
            is ActionInputBinding.Configuration -> {
                val field = task.fields.singleOrNull { it.id == source.fieldId }
                if (field == null) add("引用的配置字段不存在")
                else if (!field.required) add("动作引用的配置字段必须显式声明为必填")
                field?.valueType()
            }
            is ActionInputBinding.Output -> {
                val type = previous[source.actionId]?.outputTypes?.get(source.field)
                if (type == null) add("只能引用本步骤前序动作已声明的输出字段")
                type
            }
        }
        if (type != null && type != contract.inputTypes[key]) add("引用值与动作参数类型不匹配")
    }
}

/** Output values exist only after a successful action, in this one step attempt. */
fun resolveActionInputs(action: ActionDefinition, configuration: JsonObject, outputs: Map<String, JsonObject>): JsonObject {
    require(action.inputs.keys.intersect(action.bindings.keys).isEmpty()) { "常量与引用参数冲突" }
    val bound = action.bindings.mapValues { (_, source) -> resolveProgramValue(source, configuration, outputs) }
    return JsonObject(action.inputs + bound)
}

internal fun configuredActionIssues(task: TaskDefinition, values: JsonObject, contracts: List<CapabilityContract>): List<ContentIssue> = buildList {
    task.steps.forEach { step ->
        step.nodes().repeats().forEach { repeat ->
            if (runCatching { repeatCount(repeat.count, values) }.isFailure)
                add(ContentIssue(repeatConfiguration(repeat.count) ?: "step/${step.id}", "重复次数需为明确的非负整数且不能溢出"))
        }
        step.actionDefinitions().forEach actionLoop@ { action ->
        val contract = contracts.singleOrNull { it.id == action.capability && it.version == action.contractVersion }
        val fields = action.bindings.values.filterIsInstance<ActionInputBinding.Configuration>().map { it.fieldId }.distinct()
        val location = fields.singleOrNull() ?: "step/${step.id}/action/${action.id}"
        if (contract == null) { add(ContentIssue(location, "容器不支持所需动作或合同版本")); return@actionLoop }
        // Independently known constraints still apply even when another parameter needs an output.
        val known = try { resolveActionInputs(action.copy(bindings = action.bindings.filterValues {
            it is ActionInputBinding.Configuration
        }), values, emptyMap()) }
        catch (_: IllegalArgumentException) { add(ContentIssue(location, "配置引用不可用")); return@actionLoop }
        val problem = contract.knownInputIssue(known)
        if (problem != null) {
            val field = (action.bindings[problem.location] as? ActionInputBinding.Configuration)?.fieldId
            add(ContentIssue(field ?: location, problem.reason))
        } else if (action.bindings.values.none { it is ActionInputBinding.Output }) {
            contract.inputIssue(known)?.let { add(ContentIssue(location, it)) }
        }
    } }
}.distinct()
