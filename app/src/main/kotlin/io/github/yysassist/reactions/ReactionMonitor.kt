package io.github.yysassist.reactions

import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.core.content.TaskDefinition
import io.github.yysassist.core.content.TriggerGate
import io.github.yysassist.core.execution.ExecutorSnapshot
import io.github.yysassist.core.execution.SubmissionKind
import io.github.yysassist.core.execution.SubmissionRef
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal interface ReactionPort {
    fun snapshot(): ExecutorSnapshot
    suspend fun armed(targetId: String): List<TaskDefinition>
    fun enqueue(address: TaskAddress, commandId: String)
}

internal class RuntimeReactionPort(private val runtime: ExecutionRuntime) : ReactionPort {
    override fun snapshot() = runtime.state.value
    override suspend fun armed(targetId: String) = runtime.armedTriggers(targetId)
    override fun enqueue(address: TaskAddress, commandId: String) { runtime.enqueueTask(address, commandId) }
}

/**
 * Watches text the running task has already read and submits passive tasks to the one executor.
 * It sends no input and takes no screenshots of its own; deciding what to answer belongs to the task.
 */
internal class ReactionMonitor(private val port: ReactionPort, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private class Seen(val context: ExecutionContext, val texts: List<String>)
    // Only the newest screen matters, so a slow check can never build up a backlog.
    private val latest = Channel<Seen>(Channel.CONFLATED)
    private val gate = TriggerGate()

    init {
        scope.launch {
            for (seen in latest) try { check(seen) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed check only misses this screen; the running task is unaffected. */ }
        }
    }

    fun observed(context: ExecutionContext, texts: List<String>) { latest.trySend(Seen(context, texts)) }

    private suspend fun check(seen: Seen) {
        val state = port.snapshot()
        // After the user pauses, nothing new may start until they continue.
        if (state.manualHold) return
        port.armed(seen.context.targetId).filter { it.trigger!!.matches(seen.texts) }.forEach { task ->
            val ref = SubmissionRef(SubmissionKind.TASK, task.id)
            val active = (listOfNotNull(state.current) + state.waiting).any { it.request.ref == ref && it.request.context.targetId == task.targetId }
            val now = clock()
            if (!active && gate.admit("${task.targetId}/${task.id}", task.trigger!!.cooldownMs, now))
                port.enqueue(TaskAddress(task.targetId, task.id), "reaction/${task.targetId}/${task.id}/$now")
        }
    }
}
