package io.github.yysassist.core.content

import kotlinx.serialization.Serializable

/**
 * A program shared by steps of several tasks, such as returning to the home screen. A step names one
 * with `use` and keeps its own identity; everything after reading sees an ordinary expanded step.
 */
@Serializable
data class Routine(val id: String, val program: List<ProgramNode>)

/** Replaces every `use` step with its routine. Each expanded step is then validated like any other. */
internal fun TaskBundle.expandRoutines(): Pair<TaskBundle, List<ContentIssue>> {
    val issues = mutableListOf<ContentIssue>()
    if (routines.any { it.id.isBlank() } || routines.map { it.id }.distinct().size != routines.size)
        issues += ContentIssue("routines", "公共步骤身份为空或重复")
    routines.filter { it.program.isEmpty() }.forEach { issues += ContentIssue("routine/${it.id}", "公共步骤程序不能为空") }
    val used = tasks.flatMap { task -> task.steps.mapNotNull { it.use } }.toSet()
    routines.filter { it.id !in used }.forEach { issues += ContentIssue("routine/${it.id}", "公共步骤未被任何任务使用") }
    val expanded = tasks.map { task ->
        task.copy(steps = task.steps.map { step ->
            val id = step.use ?: return@map step
            val location = "target/${task.targetId}/task/${task.id}/step/${step.id}"
            if (step.program != null || step.actions.isNotEmpty()) issues += ContentIssue(location, "use与actions、program不能同时指定")
            val routine = routines.firstOrNull { it.id == id }
            if (routine == null) { issues += ContentIssue(location, "引用了未声明的公共步骤"); step }
            else step.copy(use = null, actions = emptyList(), program = routine.program)
        })
    }
    return copy(tasks = expanded, routines = emptyList()) to issues
}
