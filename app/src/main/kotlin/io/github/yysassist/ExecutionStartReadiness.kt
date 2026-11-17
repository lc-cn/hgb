package io.github.yysassist

import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.execution.StepAttempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Prerequisites before a worker exists; neither waiting nor lookup failure is a business result. */
sealed interface StepStartCondition {
    data object Ready : StepStartCondition
    data class Waiting(val message: String) : StepStartCondition
    data class Unconfirmed(val message: String) : StepStartCondition
}
fun interface ExecutionStartReadiness {
    suspend fun check(attempt: StepAttempt, bundle: TaskBundle): StepStartCondition
    /** Invalidation only. The host rechecks actual conditions and retains ownership of commands. */
    val changes: Flow<Unit> get() = emptyFlow()
}
