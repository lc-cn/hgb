package io.github.yysassist.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A field is shown, and validated, only while [field]'s current value contains [contains]. */
@Serializable
data class FieldCondition(val field: String, val contains: String)

/**
 * Makes a task passive: seeing any of [texts] on screen submits it, as long as the multiple-choice field
 * [enabledBy] has a selection. Everything else about the task is ordinary, so it runs through the one executor.
 */
@Serializable
data class TaskTrigger(val texts: List<String>, val enabledBy: String, val cooldownMs: Long = 30_000) {
    fun matches(seen: Collection<String>): Boolean = seen.any { text -> texts.any { text.contains(it) } }
    fun enabled(values: JsonObject): Boolean = (values[enabledBy] as? JsonArray)?.isNotEmpty() == true
}

/** High enough to preempt ordinary tasks once their input has safely stopped. */
const val TRIGGER_MIN_PRIORITY = 1_000L
private val TRIGGER_COOLDOWN_MS = 1_000L..3_600_000L

fun ConfigurationField.isVisible(values: JsonObject): Boolean {
    val condition = visibleWhen ?: return true
    return when (val value = values[condition.field]) {
        is JsonArray -> value.any { it is JsonPrimitive && it.isString && it.content == condition.contains }
        is JsonPrimitive -> value.isString && value.content == condition.contains
        else -> false
    }
}

internal fun fieldConditionIssues(task: TaskDefinition): List<ContentIssue> = task.fields.mapNotNull { field ->
    val condition = field.visibleWhen ?: return@mapNotNull null
    val owner = task.fields.singleOrNull { it.id == condition.field }
    val reason = when {
        owner == null || owner.id == field.id -> "显示条件引用的字段不存在"
        owner.visibleWhen != null -> "显示条件只能引用始终显示的字段"
        owner.widget !in listOf(FieldWidget.SINGLE_CHOICE, FieldWidget.MULTI_CHOICE) -> "显示条件只能引用选择字段"
        owner.options.none { it.value == condition.contains } -> "显示条件引用的选项不存在"
        else -> null
    }
    reason?.let { ContentIssue(field.id, it) }
}

internal fun triggerIssues(task: TaskDefinition): List<String> {
    val trigger = task.trigger ?: return emptyList()
    val switch = task.fields.singleOrNull { it.id == trigger.enabledBy }
    return listOfNotNull(
        "被动任务缺少触发文字".takeIf { trigger.texts.isEmpty() || trigger.texts.any(String::isBlank) },
        "被动任务必须由必填的多选字段启用".takeIf { switch?.widget != FieldWidget.MULTI_CHOICE || !switch.required || switch.visibleWhen != null },
        "被动任务的冷却时间无效".takeIf { trigger.cooldownMs !in TRIGGER_COOLDOWN_MS },
        "被动任务的优先级不能低于 $TRIGGER_MIN_PRIORITY".takeIf { task.priority < TRIGGER_MIN_PRIORITY },
    )
}

/** Stops one invitation on screen from being answered again on every later observation. */
class TriggerGate {
    private val lastAdmitted = mutableMapOf<String, Long>()
    fun admit(key: String, cooldownMs: Long, now: Long): Boolean {
        val last = lastAdmitted[key]
        if (last != null && now - last in 0 until cooldownMs) return false
        lastAdmitted[key] = now
        return true
    }
}
