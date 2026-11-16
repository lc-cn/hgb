package io.github.yysassist.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal

/** Versioned content contract. No remote classes, scripts or UI markup are executable. */
@Serializable
data class TaskBundle(
    val formatVersion: Int,
    val version: String,
    val updatedAt: String,
    val changelog: String,
    val tasks: List<TaskDefinition>,
    val targets: List<TargetProfile>,
    val resources: List<TaskResource> = emptyList(),
)
/** Data attachments from the same Release, never executable capabilities or arbitrary URLs. */
@Serializable
data class TaskResource(val id: String, val assetName: String, val bytes: Long, val sha256: String)
@Serializable
enum class IdentityPolicy { NONE, OPTIONAL, REQUIRED }
@Serializable
data class TaskCategory(val id: String, val name: String)
@Serializable
data class TargetProfile(
    val id: String,
    val name: String,
    val androidPackages: List<String>,
    val identityPolicy: IdentityPolicy,
    val categories: List<TaskCategory>,
) {
    /** Identity policy does not prove foreground ownership; the Android input adapter must also check it. */
    fun acceptsContext(context: ExecutionContext?): Boolean {
        if (context == null || !context.isValid() || context.targetId != id) return false
        return when (identityPolicy) {
            IdentityPolicy.NONE -> context.principal == Principal.Anonymous
            IdentityPolicy.OPTIONAL -> true
            IdentityPolicy.REQUIRED -> context.principal is Principal.Account
        }
    }
}
@Serializable
data class TaskDefinition(
    val id: String,
    val targetId: String,
    val name: String,
    val category: String,
    val priority: Long,
    val fields: List<ConfigurationField> = emptyList(),
    val steps: List<StepDefinition>,
)
@Serializable
data class StepDefinition(val id: String, val name: String, val actions: List<ActionDefinition>)
@Serializable
data class ActionDefinition(
    val id: String,
    val capability: String,
    val contractVersion: Int,
    val inputs: JsonObject,
    val bindings: Map<String, ActionInputBinding> = emptyMap(),
)
@Serializable
enum class FieldWidget { SINGLE_CHOICE, MULTI_CHOICE, NUMBER, RANGE, TIME, DATE, DATE_RANGE, TEXT }
@Serializable
data class FieldOption(val value: String, val label: String)
@Serializable
data class ConfigurationField(
    val id: String,
    val label: String,
    val widget: FieldWidget,
    val required: Boolean = false,
    val unit: String? = null,
    val minimum: JsonPrimitive? = null,
    val maximum: JsonPrimitive? = null,
    val step: JsonPrimitive? = null,
    val options: List<FieldOption> = emptyList(),
)
data class ContentIssue(val location: String, val reason: String)
internal fun JsonPrimitive.decimalOrNull(): BigDecimal? = if (isString) null else content.toBigDecimalOrNull()
sealed interface BundleReadResult {
    data class Valid(val bundle: TaskBundle) : BundleReadResult
    data class Invalid(val issues: List<ContentIssue>) : BundleReadResult
}

/** Capabilities are supplied by the APK; a bundle cannot register a new capability. */
data class CapabilityContract(
    val id: String, val version: Int,
    val inputTypes: Map<String, ActionValueType> = emptyMap(),
    val outputTypes: Map<String, ActionValueType> = emptyMap(),
    val validateInputs: (JsonObject) -> String?,
)
class BundleCodec(private val capabilities: List<CapabilityContract>) {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun read(text: String): BundleReadResult {
        val bundle = try { json.decodeFromString<TaskBundle>(text) }
        catch (_: IllegalArgumentException) {
            return BundleReadResult.Invalid(listOf(ContentIssue("bundle", "任务格式不可解析或包含未知字段")))
        }
        val issues = validate(bundle)
        return if (issues.isEmpty()) BundleReadResult.Valid(bundle) else BundleReadResult.Invalid(issues)
    }

    fun write(bundle: TaskBundle): String = json.encodeToString(bundle)

    /** Same APK contracts at config save/admission and at execution, without inventing defaults. */
    fun configurationIssues(task: TaskDefinition, values: JsonObject): List<ContentIssue> {
        val schema = validateConfiguration(task, values)
        if (schema.isNotEmpty()) return schema
        return configuredActionIssues(task, values, capabilities)
    }

    fun validate(bundle: TaskBundle): List<ContentIssue> = buildList {
        fun issue(location: String, reason: String) { add(ContentIssue(location, reason)) }
        if (bundle.formatVersion != 1) issue("formatVersion", "不支持的任务格式版本")
        if (bundle.version.isBlank()) issue("version", "缺少任务版本")
        if (bundle.updatedAt.isBlank()) issue("updatedAt", "缺少更新时间")
        if (bundle.tasks.isEmpty()) issue("tasks", "任务集为空")
        if (bundle.resources.map { it.id }.distinct().size != bundle.resources.size) issue("resources", "资源身份重复")
        if (bundle.resources.map { it.assetName }.distinct().size != bundle.resources.size) issue("resources", "资源附件名称重复")
        bundle.resources.forEach { resource ->
            val location = "resource/${resource.id}"
            if (resource.id.isBlank()) issue(location, "缺少资源身份")
            if (!resource.assetName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) || resource.assetName == "task-bundle.json")
                issue(location, "资源必须引用独立的 Release 附件名称")
            if (resource.bytes <= 0 || !resource.sha256.matches(Regex("[0-9a-f]{64}"))) issue(location, "资源缺少有效长度或 SHA-256")
        }
        if (bundle.resources.groupBy { it.sha256 }.values.any { resources -> resources.map { it.bytes }.distinct().size != 1 })
            issue("resources", "相同摘要的资源长度不一致")
        if (bundle.targets.isEmpty()) issue("targets", "缺少目标应用")
        if (bundle.targets.map { it.id }.distinct().size != bundle.targets.size) issue("targets", "目标应用身份重复")
        bundle.targets.forEach { target ->
            if (target.id.isBlank() || target.name.isBlank()) issue("target/${target.id}", "缺少目标应用身份或名称")
            if (target.androidPackages.isEmpty() || target.androidPackages.any(String::isBlank) ||
                target.androidPackages.distinct().size != target.androidPackages.size) issue("target/${target.id}", "目标包名为空或重复")
            if (target.categories.isEmpty() || target.categories.any { it.id.isBlank() || it.name.isBlank() } ||
                target.categories.map { it.id }.distinct().size != target.categories.size) issue("target/${target.id}", "分类为空或身份重复")
        }
        if (bundle.tasks.map { it.targetId to it.id }.distinct().size != bundle.tasks.size) issue("tasks", "任务身份重复")
        bundle.tasks.forEach { task ->
            val location = "target/${task.targetId}/task/${task.id}"
            val target = bundle.targets.find { it.id == task.targetId }
            if (target == null) issue(location, "目标应用未声明")
            else if (target.categories.none { it.id == task.category }) issue(location, "分类不属于目标应用")
            if (task.id.isBlank() || task.name.isBlank() || task.category.isBlank()) issue(location, "缺少任务身份、名称或分类")
            if (task.priority < 0) issue(location, "优先级不能为负值")
            if (task.fields.map { it.id }.distinct().size != task.fields.size) issue(location, "配置字段重复")
            task.fields.forEach { field ->
                val path = "$location/field/${field.id}"
                if (field.id.isBlank() || field.label.isBlank()) issue(path, "缺少字段身份或名称")
                val minimum = field.minimum?.decimalOrNull()
                val maximum = field.maximum?.decimalOrNull()
                val increment = field.step?.decimalOrNull()
                if (listOfNotNull(field.minimum, field.maximum, field.step).any { it.decimalOrNull() == null }) issue(path, "数值约束必须为有效十进制数值")
                if (minimum != null && maximum != null && minimum > maximum) issue(path, "范围起止无效")
                if (increment != null && increment <= BigDecimal.ZERO) issue(path, "步长必须大于零")
                if (field.widget == FieldWidget.RANGE && (field.minimum == null || field.maximum == null || field.step == null))
                    issue(path, "滑块必须有明确范围和步长")
                else if (field.widget == FieldWidget.RANGE && ExactRangeScale.from(field) == null)
                    issue(path, "滑块范围无法精确表示，需使用精确数值控件")
                if (field.widget in listOf(FieldWidget.SINGLE_CHOICE, FieldWidget.MULTI_CHOICE) && field.options.isEmpty())
                    issue(path, "选择字段缺少选项")
                if (field.options.any { it.value.isBlank() || it.label.isBlank() } ||
                    field.options.map { it.value }.distinct().size != field.options.size) issue(path, "选项身份或名称无效")
            }
            if (task.steps.isEmpty() || task.steps.map { it.id }.distinct().size != task.steps.size) issue(location, "步骤为空或身份重复")
            task.steps.forEach { step ->
                val path = "$location/step/${step.id}"
                if (step.id.isBlank() || step.name.isBlank() || step.actions.isEmpty()) issue(path, "步骤身份、名称或动作为空")
                if (step.actions.map { it.id }.distinct().size != step.actions.size) issue(path, "动作身份重复")
                val previous = mutableMapOf<String, CapabilityContract>()
                step.actions.forEach { action ->
                    val actionPath = "$path/action/${action.id}"
                    if (action.id.isBlank()) issue(actionPath, "缺少动作身份")
                    val contract = capabilities.find { it.id == action.capability && it.version == action.contractVersion }
                    if (contract == null) issue(actionPath, "容器不支持所需动作或合同版本")
                    else {
                        actionBindingIssues(task, action, contract, previous).forEach { issue(actionPath, it) }
                        previous[action.id] = contract
                    }
                }
            }
        }
    }
}
