package io.github.yysassist.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
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
    /** Declared once per target, referenced by screen actions instead of repeating package and models. */
    val visionProfiles: List<VisionProfile> = emptyList(),
    /** Programs shared between steps and tasks; only present in published text, expanded when read. */
    val snippets: List<Snippet> = emptyList(),
)
/**
 * Which package to capture and which recognizer resources to use for one target. The APK owns the
 * meaning of [recognizer] and the required [models] roles; core only checks references.
 */
@Serializable
data class VisionProfile(val id: String, val targetId: String, val packageName: String, val recognizer: String, val models: Map<String, String>)
/** Data attachments from the same Release, never executable capabilities or arbitrary URLs. */
@Serializable
data class TaskResource(val id: String, val assetName: String, val bytes: Long, val sha256: String,
    /** The release (tag) holding the asset when it is not this version's own: unchanged assets are uploaded once. */
    val release: String? = null)
@Serializable
enum class IdentityPolicy { NONE, OPTIONAL, REQUIRED }
@Serializable
data class TaskCategory(val id: String, val name: String)
/** The tasks a user can start or schedule: without the account tasks the App runs by itself. */
val TaskBundle.userTasks: List<TaskDefinition> get() {
    val hidden = targets.flatMap { target -> target.accountTasks.map { target.id to it } }.toSet()
    return tasks.filter { (it.targetId to it.id) !in hidden }
}
/**
 * How the target's game accounts are handled, by two tasks of the same target: [identify] reads which account is
 * signed in (ending with an account.report action), [switchTo] signs in to the account whose game nickname is given in
 * its [NICKNAME_FIELD] setting and then identifies it. Both are run by the App, never offered as ordinary tasks.
 */
@Serializable
data class AccountProcedures(val identify: String, val switchTo: String) {
    companion object { const val NICKNAME_FIELD = "nickname" }
}
@Serializable
data class TargetProfile(
    val id: String,
    val name: String,
    val androidPackages: List<String>,
    val identityPolicy: IdentityPolicy,
    val categories: List<TaskCategory>,
    /** Absent when the game's accounts cannot be told apart or switched; jobs then run on whatever account is signed in. */
    val accounts: AccountProcedures? = null,
    /** Named regions, page states and defaults the game's tasks refer to; only in published text, resolved when read. */
    val library: TargetLibrary? = null,
) {
    /** Tasks only the App runs, for accounts; not listed for users to start. */
    val accountTasks: Set<String> get() = setOfNotNull(accounts?.identify, accounts?.switchTo)
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
    /** One line shown under the task name in the list. */
    val description: String? = null,
    /** Present on passive tasks, which start from what appears on screen instead of a button or plan. */
    val trigger: TaskTrigger? = null,
)
@Serializable
data class StepDefinition(
    val id: String, val name: String,
    val actions: List<ActionDefinition> = emptyList(),
    val program: List<ProgramNode>? = null,
    /** Id of a [Snippet] supplying this step's program, with [with] for its parameters. */
    val use: String? = null,
    val with: JsonObject? = null,
)
@Serializable
data class ActionDefinition(
    val id: String,
    val capability: String,
    val contractVersion: Int,
    val inputs: JsonObject,
    val bindings: Map<String, ActionInputBinding> = emptyMap(),
)
@Serializable
enum class FieldWidget { SINGLE_CHOICE, MULTI_CHOICE, NUMBER, RANGE, TIME, DATE, DATE_RANGE, TEXT, SWITCH }
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
    /** Heading under which the settings page groups this field. */
    val section: String? = null,
    /** Second-level setting: only applies while another choice is selected. */
    val visibleWhen: FieldCondition? = null,
    /** Optional page within the settings screen, for tasks with two distinct halves. */
    val tab: String? = null,
    /** Shown for information; the user cannot change it. */
    val readOnly: Boolean = false,
    /** One line explaining the field on the settings page. */
    val description: String? = null,
    /** Suggested value offered by the settings page; never applied without the user saving it. */
    val recommended: JsonElement? = null,
)
data class ContentIssue(val location: String, val reason: String)
internal fun JsonPrimitive.decimalOrNull(): BigDecimal? = if (isString) null else content.toBigDecimalOrNull()
sealed interface BundleReadResult {
    data class Valid(val bundle: TaskBundle) : BundleReadResult
    data class Invalid(val issues: List<ContentIssue>) : BundleReadResult
}

/** Capabilities are supplied by the APK; a bundle cannot register a new capability. */
enum class ActionRole { UTILITY, OBSERVATION, VERIFICATION }
/**
 * An input that must receive an APK-owned per-attempt handle (an observation, target or region).
 * Handles only flow through output bindings, expire at the next possible device input, and carry
 * their lineage: [forbiddenAncestors] rejects a handle derived, at any depth, from those kinds (the
 * handle's own kind is not part of its lineage).
 */
data class HandleRequirement(val kinds: Set<String>, val forbiddenAncestors: Set<String> = emptySet(),
    /** Read-only comparisons only: the handle may come from before an input (e.g. a before/after diff). */
    val allowStale: Boolean = false)
data class CapabilityContract(
    val id: String, val version: Int,
    val inputTypes: Map<String, ActionValueType> = emptyMap(),
    val outputTypes: Map<String, ActionValueType> = emptyMap(),
    val role: ActionRole = ActionRole.UTILITY,
    val sendsDeviceInput: Boolean = false,
    /** As a step's first action, the step may start while the target app is not yet in front. */
    val startsTarget: Boolean = false,
    /** Output field -> handle kind. Handle fields are STRING tokens with no meaning outside one attempt. */
    val outputHandles: Map<String, String> = emptyMap(),
    /** Input parameter -> accepted handles. Literals and configuration can never supply these. */
    val inputHandles: Map<String, HandleRequirement> = emptyMap(),
    /** Validate only present values; unresolved outputs are never replaced by fake placeholders. */
    val validateKnownInputs: (JsonObject) -> ContentIssue? = { null },
    val validateDefinition: (TaskBundle, TaskDefinition, ActionFlowContext, ActionDefinition) -> String? = { _, _, _, _ -> null },
    val validateInputs: (JsonObject) -> String?,
)
class BundleCodec(private val capabilities: List<CapabilityContract>) {
    init {
        capabilities.forEach { contract ->
            require(contract.outputHandles.all { (field, kind) -> kind.isNotBlank() && contract.outputTypes[field] == ActionValueType.STRING }) { "句柄输出必须是字符串" }
            require(contract.inputHandles.all { (field, need) -> need.kinds.isNotEmpty() && contract.inputTypes[field] == ActionValueType.STRING }) { "句柄参数必须是字符串" }
        }
    }
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun read(text: String): BundleReadResult {
        val (bundle, expansionIssues) = try {
            val element = json.parseToJsonElement(text)
            val value = json.decodeFromJsonElement<TaskBundle>(element)
            require(element.jsonObject.getValue("tasks").jsonArray.none { task ->
                task.jsonObject.getValue("steps").jsonArray.any { step ->
                    "actions" in step.jsonObject && "program" in step.jsonObject
                }
            }) { "program与actions不能同时指定" }
            val (snippetsExpanded, snippetIssues) = value.expandSnippets()
            val (resolved, libraryIssues) = snippetsExpanded.resolveLibrary(capabilities)
            resolved to (snippetIssues + libraryIssues)
        }
        catch (_: IllegalArgumentException) {
            return BundleReadResult.Invalid(listOf(ContentIssue("bundle", "任务格式不可解析或包含未知字段")))
        }
        val issues = expansionIssues + validate(bundle)
        return if (issues.isEmpty()) BundleReadResult.Valid(bundle) else BundleReadResult.Invalid(issues)
    }

    fun write(bundle: TaskBundle): String = json.encodeToString(bundle)

    /** Same APK contracts at config save/admission and at execution, without inventing defaults. */
    fun configurationIssues(task: TaskDefinition, values: JsonObject): List<ContentIssue> {
        val schema = validateConfiguration(task, values)
        if (schema.isNotEmpty()) return schema
        // A passive task with nothing selected never runs, so its action inputs are not needed.
        if (task.trigger?.enabled(values) == false) return emptyList()
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
            if (resource.release != null && !resource.release.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) issue(location, "资源所在版本名不合法")
        }
        if (bundle.resources.groupBy { it.sha256 }.values.any { resources -> resources.map { it.bytes }.distinct().size != 1 })
            issue("resources", "相同摘要的资源长度不一致")
        if (bundle.visionProfiles.map { it.id }.distinct().size != bundle.visionProfiles.size) issue("visionProfiles", "视觉配置身份重复")
        bundle.visionProfiles.forEach { profile ->
            val location = "visionProfile/${profile.id}"
            if (profile.id.isBlank() || profile.recognizer.isBlank()) issue(location, "缺少视觉配置身份或识别合同")
            val target = bundle.targets.find { it.id == profile.targetId }
            if (target == null) issue(location, "视觉配置的目标应用未声明")
            else if (profile.packageName !in target.androidPackages) issue(location, "截图包名不属于目标应用")
            if (profile.models.isEmpty() || profile.models.values.any { id -> bundle.resources.none { it.id == id } })
                issue(location, "视觉配置引用了未声明的模型资源")
        }
        if (bundle.targets.isEmpty()) issue("targets", "缺少目标应用")
        if (bundle.targets.map { it.id }.distinct().size != bundle.targets.size) issue("targets", "目标应用身份重复")
        bundle.targets.forEach { target ->
            if (target.id.isBlank() || target.name.isBlank()) issue("target/${target.id}", "缺少目标应用身份或名称")
            if (target.androidPackages.isEmpty() || target.androidPackages.any(String::isBlank) ||
                target.androidPackages.distinct().size != target.androidPackages.size) issue("target/${target.id}", "目标包名为空或重复")
            if (target.categories.isEmpty() || target.categories.any { it.id.isBlank() || it.name.isBlank() } ||
                target.categories.map { it.id }.distinct().size != target.categories.size) issue("target/${target.id}", "分类为空或身份重复")
            target.accounts?.let { procedures ->
                val identify = bundle.tasks.find { it.targetId == target.id && it.id == procedures.identify }
                val switch = bundle.tasks.find { it.targetId == target.id && it.id == procedures.switchTo }
                if (identify == null || switch == null || identify.id == switch.id) issue("target/${target.id}/accounts", "识别账号和切换账号需为本目标下两个不同的任务")
                else {
                    if (identify.fields.isNotEmpty() || identify.trigger != null) issue("target/${target.id}/accounts", "识别账号任务不能有设置或触发条件")
                    if (switch.trigger != null || switch.fields.singleOrNull()?.let { it.id == AccountProcedures.NICKNAME_FIELD && it.widget == FieldWidget.TEXT && it.required } != true)
                        issue("target/${target.id}/accounts", "切换账号任务只能有一个必填文本设置 nickname")
                }
            }
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
            fieldConditionIssues(task).forEach { issue("$location/field/${it.location}", it.reason) }
            triggerIssues(task).forEach { issue(location, it) }
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
                if (field.section?.isBlank() == true) issue(path, "分组名称为空")
                field.recommended?.let { value ->
                    if (validateConfiguration(task, JsonObject(mapOf(field.id to value))).any { it.location == field.id }) issue(path, "推荐值不符合字段约束")
                }
            }
            if (task.steps.isEmpty() || task.steps.map { it.id }.distinct().size != task.steps.size) issue(location, "步骤为空或身份重复")
            task.steps.forEach { step ->
                val path = "$location/step/${step.id}"
                if (step.id.isBlank() || step.name.isBlank()) issue(path, "步骤身份或名称为空")
                programIssues(bundle, task, step, capabilities).forEach { issue("$path/${it.location}", it.reason) }
            }
        }
    }
}
