package io.github.yysassist.device

import io.github.yysassist.ExecutionStartReadiness
import io.github.yysassist.StepStartCondition
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.StepAttempt
import kotlinx.coroutines.flow.*

/** No screenshots, OCR, input, game launch, account inference or second scheduler. */
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
        val needsScreen = step.actions.any { action ->
            contracts.single { it.id == action.capability && it.version == action.contractVersion }.let { it.role == ActionRole.OBSERVATION || it.sendsDeviceInput }
        }
        if (!needsScreen) return StepStartCondition.Ready
        val target = bundle.targets.single { it.id == task.targetId }
        return when (availability(target.androidPackages.toSet())) {
            DeviceAvailability.READY -> StepStartCondition.Ready
            DeviceAvailability.DISCONNECTED -> StepStartCondition.Waiting("等待无障碍连接，请在设置的运行环境中授权")
            DeviceAvailability.WRONG_FOREGROUND -> StepStartCondition.Waiting("等待目标应用回到前台")
            DeviceAvailability.DISPLAY_UNAVAILABLE -> StepStartCondition.Waiting("等待屏幕可用")
            DeviceAvailability.UNCONFIRMED -> StepStartCondition.Unconfirmed("前台应用暂无法确认，请检查目标应用与授权")
        }
    }
}
