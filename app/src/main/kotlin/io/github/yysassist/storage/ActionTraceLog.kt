package io.github.yysassist.storage

import io.github.yysassist.core.execution.ActionTraceRecord
import io.github.yysassist.core.execution.ActionTraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persists action traces off the interpreter's path, in order, through one consumer. Diagnostics only:
 * a full buffer drops the newest record and a write failure is ignored; neither affects execution.
 * Keeps the most recent [keep] rows.
 */
class ActionTraceLog(private val database: AssistDatabase, scope: CoroutineScope, private val keep: Int = 5_000,
    private val now: () -> Long = System::currentTimeMillis) : ActionTraceSink {
    private val pending = Channel<ActionTraceRecord>(capacity = 1_024)
    init {
        scope.launch(Dispatchers.IO) {
            var written = 0
            for (record in pending) runCatching {
                database.actionTraces().insert(ActionTraceEntity(runId = record.ticket.runId, generation = record.ticket.generation,
                    taskId = record.taskId, stepId = record.stepId, actionId = record.actionId, capability = record.capability,
                    contractVersion = record.contractVersion, result = record.result, reason = record.reason,
                    traceJson = record.trace?.toString(), elapsedMillis = record.elapsedMillis, recordedAt = now()))
                if (++written % 200 == 0) database.actionTraces().prune(keep)
            }
        }
    }
    override fun record(record: ActionTraceRecord) { pending.trySend(record) }
}
