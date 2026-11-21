package io.github.yysassist.device

import io.github.yysassist.ExecutionStartReadiness
import io.github.yysassist.StepStartCondition
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.StepAttempt
import kotlinx.coroutines.flow.*

/**
 * No screenshots, OCR, input, account inference or second scheduler. A step that needs the screen and finds the game
 * not running or not in front opens it first (at most every [LAUNCH_RETRY_MS]) and starts once it is in front.
 * A step that itself opens with an explicit launch action only needs the service and a lit screen.
 */
internal class DeviceExecutionReadiness(
    private val contracts: List<CapabilityContract>,
    private val availability: suspend (Set<String>) -> DeviceAvailability = { packages ->
        GameAccessibilityService.connection.value?.bridge?.availability(packages) ?: DeviceAvailability.DISCONNECTED
    },
    override val changes: Flow<Unit> = merge(GameAccessibilityService.connection.map { Unit }, GameAccessibilityService.sceneRevision.map { Unit }),
    private val bringToFront: suspend (Set<String>) -> Boolean = { packages ->
        GameAccessibilityService.connection.value?.bridge?.bringToFront(packages) == true
    },
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : ExecutionStartReadiness {
    private val launched = mutableMapOf<Set<String>, Long>()
    override suspend fun check(attempt: StepAttempt, bundle: TaskBundle): StepStartCondition {
        val task = bundle.tasks.single { it.id == attempt.run.task.id && it.targetId == attempt.run.request.context.targetId }
        val step = task.steps.single { it.id == attempt.run.stepId }
        val needsScreen = step.actionDefinitions().any { action ->
            // Waiting for a page is a verification, but it looks at the screen all the same.
            contracts.single { it.id == action.capability && it.version == action.contractVersion }.let {
                it.role == ActionRole.OBSERVATION || it.sendsDeviceInput || it.id.startsWith(SCREEN_PREFIX) }
        }
        if (!needsScreen) return StepStartCondition.Ready
        val target = bundle.targets.single { it.id == task.targetId }
        val opensTarget = (step.nodes().firstOrNull() as? ProgramNode.Action)?.action?.let { first ->
            contracts.single { it.id == first.capability && it.version == first.contractVersion }.startsTarget } == true
        return when (availability(target.androidPackages.toSet())) {
            DeviceAvailability.READY -> StepStartCondition.Ready
            DeviceAvailability.WRONG_FOREGROUND -> if (opensTarget) StepStartCondition.Ready else open(target.androidPackages.toSet())
            DeviceAvailability.DISCONNECTED -> StepStartCondition.Waiting(WAIT_ACCESSIBILITY)
            DeviceAvailability.DISPLAY_UNAVAILABLE -> StepStartCondition.Waiting(WAIT_DISPLAY)
            DeviceAvailability.LOCKED -> StepStartCondition.Waiting(WAIT_UNLOCK)
            DeviceAvailability.UNCONFIRMED -> StepStartCondition.Unconfirmed("前台应用暂无法确认，请检查目标应用与授权")
        }
    }

    /** Opens the game, unless that was just tried and is still under way; waits for it to come to the front either way. */
    private suspend fun open(packages: Set<String>): StepStartCondition {
        val now = clock()
        val last = synchronized(launched) { launched[packages] }
        if (last != null && now - last < LAUNCH_RETRY_MS) return StepStartCondition.Waiting(WAIT_LAUNCHING)
        synchronized(launched) { launched[packages] = now }
        return StepStartCondition.Waiting(if (bringToFront(packages)) WAIT_LAUNCHING else WAIT_FOREGROUND)
    }
    companion object {
        const val LAUNCH_RETRY_MS = 15_000L
        /** Capabilities that look at or act on the game's screen. */
        const val SCREEN_PREFIX = "screen."
    }
}

/** The waits that mean the device cannot see the screen or press buttons; the 运行条件中断 rule watches only these. */
internal const val WAIT_FOREGROUND = "等待目标应用回到前台"
internal const val WAIT_LAUNCHING = "正在打开游戏并切到前台"
internal const val WAIT_ACCESSIBILITY = "等待无障碍连接，请在设置的运行环境中授权"
internal const val WAIT_DISPLAY = "等待屏幕可用"
internal const val WAIT_UNLOCK = "等待解锁手机"
internal val CAPABILITY_WAITS = setOf(WAIT_FOREGROUND, WAIT_LAUNCHING, WAIT_ACCESSIBILITY, WAIT_DISPLAY, WAIT_UNLOCK)
