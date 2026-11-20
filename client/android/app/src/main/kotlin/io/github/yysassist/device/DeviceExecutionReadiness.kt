package io.github.yysassist.device

import io.github.yysassist.ExecutionStartReadiness
import io.github.yysassist.StepStartCondition
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.StepAttempt
import kotlinx.coroutines.flow.*

/**
 * No screenshots, OCR, input, account inference or second scheduler, and it never launches anything.
 * A step that itself opens with an explicit launch action only needs the service and a lit screen.
 */
internal class DeviceExecutionReadiness(
    private val contracts: List<CapabilityContract>,
    private val availability: suspend (Set<String>) -> DeviceAvailability = { packages ->
        GameAccessibilityService.connection.value?.bridge?.availability(packages) ?: DeviceAvailability.DISCONNECTED
    },
    override val changes: Flow<Unit> = merge(GameAccessibilityService.connection.map { Unit }, GameAccessibilityService.sceneRevision.map { Unit }),
) : ExecutionStartReadiness {
    override suspend fun check(attempt: StepAttempt, bundle: TaskBundle): StepStartCondition {
        val task = bundle.tasks.single { it.id == attempt.run.task.id && it.targetId == attempt.run.request.context.targetId }
        val step = task.steps.single { it.id == attempt.run.stepId }
        val needsScreen = step.actionDefinitions().any { action ->
            contracts.single { it.id == action.capability && it.version == action.contractVersion }.let { it.role == ActionRole.OBSERVATION || it.sendsDeviceInput }
        }
        if (!needsScreen) return StepStartCondition.Ready
        val target = bundle.targets.single { it.id == task.targetId }
        val opensTarget = (step.nodes().firstOrNull() as? ProgramNode.Action)?.action?.let { first ->
            contracts.single { it.id == first.capability && it.version == first.contractVersion }.startsTarget } == true
        return when (availability(target.androidPackages.toSet())) {
            DeviceAvailability.READY -> StepStartCondition.Ready
            DeviceAvailability.WRONG_FOREGROUND -> if (opensTarget) StepStartCondition.Ready else StepStartCondition.Waiting(WAIT_FOREGROUND)
            DeviceAvailability.DISCONNECTED -> StepStartCondition.Waiting(WAIT_ACCESSIBILITY)
            DeviceAvailability.DISPLAY_UNAVAILABLE -> StepStartCondition.Waiting(WAIT_DISPLAY)
            DeviceAvailability.UNCONFIRMED -> StepStartCondition.Unconfirmed("前台应用暂无法确认，请检查目标应用与授权")
        }
    }
}

/** The waits that mean the device cannot see the screen or press buttons; the 运行条件中断 rule watches only these. */
internal const val WAIT_FOREGROUND = "等待目标应用回到前台"
internal const val WAIT_ACCESSIBILITY = "等待无障碍连接，请在设置的运行环境中授权"
internal const val WAIT_DISPLAY = "等待屏幕可用"
internal val CAPABILITY_WAITS = setOf(WAIT_FOREGROUND, WAIT_ACCESSIBILITY, WAIT_DISPLAY)
