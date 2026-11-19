package io.github.yysassist.core.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.yysassist.core.model.ExecutionContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Single command owner. It never waits for I/O under its lock. Effects are executed by the host;
 * cancelling an effect is NOT evidence that device input stopped. Only inputQuiescent acknowledges it.
 * This first core is in-memory; durable transactions/recovery are a separate storage integration.
 */
@OptIn(ExperimentalSerializationApi::class)
class TaskExecutor(initialContext: ExecutionContext? = null, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private enum class Handoff { PAUSE, STOP, REMOVE, PREEMPT, UNKNOWN }
    @Serializable
    private data class Pending(val ticket: AttemptTicket, val handoff: Handoff)
    @Serializable
    private data class Receipt(val request: ExecutionRequest, val result: EnqueueResult)
    @Serializable
    private data class AttemptRecord(val attempt: StepAttempt, val reconciliation: StepOutcome? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val deviceActions: List<DeviceActionRecord> = emptyList())
    @Serializable
    private data class StoredState(
        val format: Int,
        val context: ExecutionContext?, val manualHold: Boolean, val current: RunProgress?,
        val waiting: List<RunProgress>, val active: StepAttempt?, val pending: Pending?,
        val observedDuringHandoff: StepOutcome?, val endAfterResolution: Handoff?,
        val serial: Long, val generation: Long, val attempts: List<AttemptRecord>,
        val receipts: Map<String, Receipt>, val unknowns: List<UnresolvedResult>, val facts: List<ExecutionFact>,
    )
    @Serializable
    private data class StoredRun(val runId: Long, val sequence: Long, val request: ExecutionRequest, val priority: Long)
    @Serializable
    private data class StoredPosition(val runId: Long, val taskIndex: Int, val stepIndex: Int, val failedTaskIds: List<String>)
    @Serializable
    private data class StoredAttempt(val ticket: AttemptTicket, val position: StoredPosition, val reconciliation: StepOutcome?,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val deviceActions: List<DeviceActionRecord> = emptyList())
    @Serializable
    private data class StoredUncertainty(val ticket: AttemptTicket, val reason: String)
    @Serializable
    private data class PackedState(
        val format: Int, val context: ExecutionContext?, val manualHold: Boolean,
        val runs: List<StoredRun>, val current: StoredPosition?, val waiting: List<StoredPosition>,
        val active: AttemptTicket?, val pending: Pending?, val observedDuringHandoff: StepOutcome?,
        val endAfterResolution: Handoff?, val serial: Long, val generation: Long,
        val attempts: List<StoredAttempt>, val receipts: Map<String, Receipt>,
        val unknowns: List<StoredUncertainty>, val facts: List<ExecutionFact>,
    )

    companion object {
        /** Facts kept in the rewritten state; older history lives in the host's append-only store. */
        const val FACT_LIMIT = 300
        const val RECEIPT_LIMIT = 256
        const val RECENT_RUNS = 20
        private val storedJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        /** Reconstructs recorded facts only. This does not resume work or prove old input quiet. */
        fun fromEncodedState(encoded: String, clock: () -> Long = System::currentTimeMillis): TaskExecutor {
            val root = storedJson.parseToJsonElement(encoded).jsonObject
            return TaskExecutor(clock = clock).apply {
                val value = when (root["format"]?.jsonPrimitive?.intOrNull) {
                    1 -> storedJson.decodeFromJsonElement<StoredState>(root)
                    2 -> unpack(storedJson.decodeFromJsonElement<PackedState>(root))
                    else -> throw IllegalArgumentException("执行存储格式无效")
                }
                validateStored(value); restore(value)
            }
        }
    }

    private var context = initialContext.also { require(it == null || it.isValid()) }
    private var manualHold = false
    private var current: RunProgress? = null
    private val waiting = mutableListOf<RunProgress>()
    private var active: StepAttempt? = null
    private var pending: Pending? = null
    private var observedDuringHandoff: StepOutcome? = null
    private var endAfterResolution: Handoff? = null
    private var serial = 0L
    private var generation = 0L
    private var revision = 0L
    private val attempts = mutableMapOf<AttemptTicket, AttemptRecord>()
    private val receipts = mutableMapOf<String, Receipt>()
    private val unknowns = linkedMapOf<AttemptTicket, UnresolvedResult>()
    private val facts = mutableListOf<ExecutionFact>()
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<ExecutorSnapshot> = mutableState.asStateFlow()

    /** Prepare a detached reduction so failed storage cannot leak an uncommitted UI state or input. */
    @Synchronized
    fun prepare(command: ExecutorCommand): PreparedExecutorTransition {
        val before = capture()
        val draft = TaskExecutor(clock = clock).apply { restore(before) }
        var reply: ExecutorReply = ExecutorReply.Applied
        val effects = when (command) {
            is ExecutorCommand.Enqueue -> draft.enqueue(command.commandId, command.request).let {
                reply = ExecutorReply.Enqueued(it.result); it.effects
            }
            ExecutorCommand.Pause -> draft.pause()
            ExecutorCommand.Stop -> draft.stop()
            is ExecutorCommand.PauseCurrent -> if (draft.current?.runId == command.runId) draft.pause() else emptyList()
            is ExecutorCommand.StopCurrent -> if (draft.current?.runId == command.runId) draft.stop() else emptyList()
            is ExecutorCommand.ResumeCurrent -> if (draft.current?.runId == command.runId) draft.resume(command.mode) else emptyList()
            is ExecutorCommand.Remove -> draft.remove(command.runId)
            is ExecutorCommand.Resume -> draft.resume(command.mode)
            is ExecutorCommand.StepFinished -> draft.stepFinished(command.ticket, command.outcome)
            is ExecutorCommand.InputQuiescent -> draft.inputQuiescent(command.ticket, command.outcome)
            is ExecutorCommand.StepSettled -> draft.stepFinished(command.ticket, command.result) +
                draft.inputQuiescent(command.ticket, command.quietOutcome)
            is ExecutorCommand.RecoverNoInput -> draft.recoverNoInput(command.ticket, command.cause)
            is ExecutorCommand.InputUnconfirmed -> draft.inputUnconfirmed(command.ticket, command.reason)
            is ExecutorCommand.RecordDeviceAction -> {
                reply = ExecutorReply.DeviceActionRecorded(draft.recordDeviceAction(command.intent)); emptyList()
            }
            is ExecutorCommand.RecordDeviceActionReceipt -> {
                reply = ExecutorReply.DeviceActionRecorded(draft.recordDeviceActionReceipt(command.ticket, command.invocationId, command.receipt)); emptyList()
            }
            is ExecutorCommand.ResolveUnknown -> draft.resolveUnknown(command.ticket, command.outcome)
            is ExecutorCommand.VerifyContext -> {
                reply = ExecutorReply.ContextChanged(draft.setVerifiedContext(command.context)); emptyList()
            }
            ExecutorCommand.ReadinessChanged -> draft.readinessChanged()
        }
        return PreparedExecutorTransition(this, revision, draft, encodeStored(before),
            draft.serial.takeIf { it > before.serial }, reply, effects.toList())
    }

    /** The host is the only command owner and commits only after its storage transaction succeeds. */
    @Synchronized
    fun commit(prepared: PreparedExecutorTransition): List<ExecutorEffect> {
        check(prepared.owner === this && prepared.baseRevision == revision) { "执行状态已变化，不能提交过期命令" }
        restore(prepared.draft.capture(), publish = false)
        publish()
        return prepared.effects
    }

    @Synchronized
    fun encodeState(): String = encodeStored(capture())

    private fun position(run: RunProgress) = StoredPosition(run.runId, run.taskIndex, run.stepIndex, run.failedTaskIds)
    private fun encodeStored(value: StoredState): String {
        val definitions = linkedMapOf<Long, StoredRun>()
        val all = listOfNotNull(value.current) + value.waiting + value.attempts.map { it.attempt.run }
        all.forEach { run ->
            val definition = StoredRun(run.runId, run.sequence, run.request, run.priority)
            val previous = definitions.putIfAbsent(run.runId, definition)
            check(previous == null || previous == definition) { "同一轮执行定义不一致" }
        }
        return storedJson.encodeToString(PackedState(2, value.context, value.manualHold, definitions.values.toList(),
            value.current?.let(::position), value.waiting.map(::position), value.active?.ticket, value.pending,
            value.observedDuringHandoff, value.endAfterResolution, value.serial, value.generation,
            value.attempts.map { StoredAttempt(it.attempt.ticket, position(it.attempt.run), it.reconciliation, it.deviceActions) },
            value.receipts, value.unknowns.map { StoredUncertainty(it.attempt.ticket, it.reason) }, value.facts))
    }

    private fun unpack(value: PackedState): StoredState {
        require(value.format == 2)
        val definitions = value.runs.associateBy { it.runId }
        require(definitions.size == value.runs.size) { "执行定义身份重复" }
        fun expand(position: StoredPosition): RunProgress {
            val definition = definitions[position.runId] ?: throw IllegalArgumentException("执行定义缺失")
            return RunProgress(definition.runId, definition.sequence, definition.request, definition.priority,
                position.taskIndex, position.stepIndex, position.failedTaskIds)
        }
        val records = value.attempts.map { AttemptRecord(StepAttempt(it.ticket, expand(it.position)), it.reconciliation, it.deviceActions) }
        val byTicket = records.associateBy { it.attempt.ticket }
        fun attempt(ticket: AttemptTicket) = byTicket[ticket]?.attempt ?: throw IllegalArgumentException("执行尝试缺失")
        val current = value.current?.let(::expand)
        val waiting = value.waiting.map(::expand)
        require((listOfNotNull(current) + waiting + records.map { it.attempt.run }).map { it.runId }.toSet() == definitions.keys) {
            "执行定义没有对应进度"
        }
        return StoredState(1, value.context, value.manualHold, current, waiting, value.active?.let(::attempt), value.pending,
            value.observedDuringHandoff, value.endAfterResolution, value.serial, value.generation, records, value.receipts,
            value.unknowns.map { UnresolvedResult(attempt(it.ticket), it.reason) }, value.facts)
    }

    private fun capture() = StoredState(format = 1, context = context, manualHold = manualHold, current = current,
        waiting = waiting.toList(), active = active, pending = pending, observedDuringHandoff = observedDuringHandoff,
        endAfterResolution = endAfterResolution, serial = serial, generation = generation, attempts = attempts.values.toList(),
        receipts = receipts.toMap(), unknowns = unknowns.values.toList(), facts = facts.toList())

    private fun restore(value: StoredState, publish: Boolean = true) {
        context = value.context; manualHold = value.manualHold; current = value.current
        waiting.clear(); waiting.addAll(value.waiting)
        active = value.active; pending = value.pending; observedDuringHandoff = value.observedDuringHandoff
        endAfterResolution = value.endAfterResolution; serial = value.serial; generation = value.generation
        attempts.clear(); value.attempts.forEach { attempts[it.attempt.ticket] = it }
        receipts.clear(); receipts.putAll(value.receipts)
        unknowns.clear(); value.unknowns.forEach { unknowns[it.attempt.ticket] = it }
        facts.clear(); facts.addAll(value.facts)
        if (publish) mutableState.value = snapshot()
    }

    private fun validateStored(value: StoredState) {
        require(value.format == 1 && value.serial >= 0 && value.generation >= 0) { "执行存储格式无效" }
        require(value.context == null || value.context.isValid()) { "执行身份无效" }
        val live = listOfNotNull(value.current) + value.waiting
        val runs = live + value.attempts.map { it.attempt.run }
        require(live.map { it.runId }.distinct().size == live.size) { "执行队列身份重复" }
        require(runs.all { run ->
            run.runId in 1..value.serial && run.sequence in 1..value.serial && validate(run.request) == null &&
                run.taskIndex in run.request.tasks.indices && run.stepIndex in run.task.stepIds.indices &&
                run.priority == run.request.tasks.fold(0L) { sum, task -> Math.addExact(sum, task.priority) }
        }) { "执行进度无效" }
        val storedAttempts = value.attempts.associateBy { it.attempt.ticket }
        require(storedAttempts.size == value.attempts.size && storedAttempts.keys.all {
            it.generation in 1..value.generation && it.runId in 1..value.serial
        } && value.attempts.all { it.attempt.ticket.runId == it.attempt.run.runId }) { "执行尝试身份无效" }
        require(value.active == null || (storedAttempts[value.active.ticket]?.attempt == value.active &&
            value.active.run == value.current)) { "当前尝试与进度不符" }
        require(value.pending == null || value.pending.ticket == value.active?.ticket) { "输入交接身份不符" }
        require(value.observedDuringHandoff == null || value.pending != null) { "交接结果没有对应输入" }
        require(value.unknowns.map { it.attempt.ticket }.distinct().size == value.unknowns.size && value.unknowns.all {
            it.reason.isNotBlank() && storedAttempts[it.attempt.ticket]?.attempt == it.attempt
        }) { "未决证据身份不符" }
        value.attempts.mapNotNull { it.reconciliation }.forEach { requireOutcome(it); require(it !is StepOutcome.Unknown) }
        val actions = value.attempts.flatMap { record ->
            require(record.deviceActions.all { it.intent.ticket == record.attempt.ticket && it.intent.isValid() &&
                (it.receipt == null || it.receipt.evidence.isNotBlank()) }) { "输入记录与执行尝试不符" }
            record.deviceActions
        }
        require(actions.map { it.intent.id }.distinct().size == actions.size) { "输入调用身份重复" }
        value.observedDuringHandoff?.let(::requireOutcome)
        require(value.receipts.keys.none(String::isBlank)) { "命令身份无效" }
    }

    @Synchronized
    fun enqueue(commandId: String, request: ExecutionRequest): EnqueueResponse {
        if (commandId.isBlank()) return EnqueueResponse(EnqueueResult.Rejected("缺少命令身份"), emptyList())
        val frozen = request.copy(tasks = request.tasks.map { it.copy(stepIds = it.stepIds.toList()) })
        receipts[commandId]?.let {
            return EnqueueResponse(
                if (it.request == frozen) it.result else EnqueueResult.Rejected("同一命令身份的内容发生变化"),
                emptyList(),
            )
        }
        val error = validate(frozen)
        val duplicate = (listOfNotNull(current) + waiting).find {
            it.request.context.targetId == frozen.context.targetId && it.request.ref == frozen.ref
        }
        val effects = mutableListOf<ExecutorEffect>()
        val result = when {
            error != null -> EnqueueResult.Rejected(error)
            duplicate != null -> EnqueueResult.Duplicate(duplicate.runId)
            else -> {
                val priority = frozen.tasks.fold(0L) { sum, task -> Math.addExact(sum, task.priority) }
                val run = RunProgress(++serial, serial, frozen, priority)
                waiting += run
                fact("enqueued", run)
                val running = current
                if (!manualHold && unknowns.isEmpty() && pending == null && running != null &&
                    run.request.context == context && run.priority > running.priority
                ) revoke(Handoff.PREEMPT, effects)
                schedule(effects)
                EnqueueResult.Accepted(run.runId)
            }
        }
        receipts[commandId] = Receipt(frozen, result)
        publish()
        return EnqueueResponse(result, effects)
    }

    @Synchronized
    fun pause(): List<ExecutorEffect> = change { effects ->
        manualHold = true
        if (active != null) revoke(Handoff.PAUSE, effects)
    }

    @Synchronized
    fun stop(): List<ExecutorEffect> = change { effects ->
        manualHold = true
        if (current == null) return@change
        endAfterResolution = Handoff.STOP
        if (active != null) revoke(Handoff.STOP, effects)
        else finishRequestedEnd()
    }

    @Synchronized
    fun remove(runId: Long): List<ExecutorEffect> = change { effects ->
        if (current?.runId == runId) {
            endAfterResolution = Handoff.REMOVE
            if (active != null) revoke(Handoff.REMOVE, effects)
            else { finishRequestedEnd(); schedule(effects) }
        } else {
            val run = waiting.find { it.runId == runId } ?: return@change
            waiting.remove(run)
            fact("cancelled", run, "移出队列")
        }
    }

    @Synchronized
    fun resume(mode: ResumeMode): List<ExecutorEffect> = change { effects ->
        if (pending != null || unknowns.isNotEmpty()) return@change
        // A repeated resume while running cannot restart a task or mint another attempt.
        if (!manualHold) return@change
        if (mode == ResumeMode.RESTART_CURRENT_TASK) {
            current = current?.copy(stepIndex = 0)
            current?.let { fact("task-restarted", it) }
        }
        manualHold = false
        schedule(effects)
    }

    @Synchronized
    fun stepFinished(ticket: AttemptTicket, outcome: StepOutcome): List<ExecutorEffect> = change { effects ->
        require(outcome != StepOutcome.Interrupted) { "工作器中断不能证明输入已静默" }
        requireOutcome(outcome)
        val record = attempts[ticket] ?: return@change
        val attempt = record.attempt
        fact("step-observed", attempt.run, outcome.toString())
        if (pending?.ticket == ticket) {
            observedDuringHandoff = merge(observedDuringHandoff, outcome)
            return@change
        }
        if (active?.ticket != ticket) {
            if (outcome is StepOutcome.Unknown) {
                observeLateUnknown(record, outcome, effects)
            }
            return@change
        }
        active = null
        applyOutcome(attempt, outcome)
        schedule(effects)
    }

    /** Called only after the device owner independently proves the revoked input channel is quiet. */
    @Synchronized
    fun inputQuiescent(ticket: AttemptTicket, outcome: StepOutcome): List<ExecutorEffect> = change { effects ->
        requireOutcome(outcome)
        val record = attempts[ticket] ?: return@change
        val transition = pending?.takeIf { it.ticket == ticket }
        if (transition == null) {
            fact("revocation-observed", record.attempt.run, outcome.toString())
            if (outcome is StepOutcome.Unknown) observeLateUnknown(record, outcome, effects)
            return@change
        }
        val attempt = record.attempt
        val resolved = merge(observedDuringHandoff, outcome)
        pending = null
        observedDuringHandoff = null
        active = null
        applyOutcome(attempt, resolved)
        finishRequestedEnd()
        if (transition.handoff == Handoff.PREEMPT && current != null && !manualHold) {
            waiting += current!!
            current = null
        }
        schedule(effects)
    }

    /** Explicitly observed business evidence, not a timeout or a "dismiss error" operation. */
    @Synchronized
    fun resolveUnknown(ticket: AttemptTicket, outcome: StepOutcome): List<ExecutorEffect> = change { effects ->
        require(outcome is StepOutcome.Completed || outcome is StepOutcome.Failed || outcome == StepOutcome.Interrupted)
        requireOutcome(outcome)
        val unresolved = unknowns[ticket] ?: return@change
        if (pending != null) return@change
        unknowns.remove(ticket)
        attempts[ticket] = attempts.getValue(ticket).copy(reconciliation = outcome)
        fact("result-resolved", unresolved.attempt.run, outcome.toString())
        if (current?.runId == ticket.runId && current?.taskIndex == unresolved.attempt.run.taskIndex &&
            current?.stepIndex == unresolved.attempt.run.stepIndex
        ) applyOutcome(unresolved.attempt, outcome)
        finishRequestedEnd()
        schedule(effects)
    }

    /** Identity changes are admitted only with a quiet executor; game identity verification belongs to host. */
    @Synchronized
    fun setVerifiedContext(newContext: ExecutionContext): ContextChangeResult {
        require(newContext.isValid())
        if (active != null || pending != null) return ContextChangeResult.InputNotQuiet
        if (unknowns.isNotEmpty()) return ContextChangeResult.UnresolvedResult
        val previous = context
        val live = listOfNotNull(current) + waiting
        if (previous != null && previous != newContext && live.isNotEmpty() &&
            (previous.targetId != newContext.targetId || live.any { it.request.context.targetId != previous.targetId }))
            return ContextChangeResult.CrossTargetWorkPending
        if (previous != null && previous.targetId == newContext.targetId && previous.principal != newContext.principal) {
            (listOfNotNull(current) + waiting).forEach { fact("cancelled", it, "账号已切换") }
            current = null
            waiting.clear()
            manualHold = true
        }
        context = newContext
        publish()
        return ContextChangeResult.Accepted
    }

    /** Host calls after device/identity readiness changes; it never clears a user's hold. */
    @Synchronized
    fun readinessChanged(): List<ExecutorEffect> = change { effects -> schedule(effects) }

    @Synchronized
    fun permitsInput(ticket: AttemptTicket): Boolean =
        active?.ticket == ticket && pending == null && !manualHold && unknowns.isEmpty()

    @Synchronized
    fun mayHaveDeliveredInput(ticket: AttemptTicket): Boolean = attempts[ticket]?.deviceActions.orEmpty()
        .any { it.receipt?.delivery != DeviceActionDelivery.NOT_SENT }

    /** Admission is recorded by the same owner/transaction as execution state, never by telemetry. */
    @Synchronized
    private fun recordDeviceAction(intent: DeviceActionIntent): DeviceActionWriteResult {
        if (!intent.isValid()) return DeviceActionWriteResult.Rejected("动作调用身份或观察依据无效")
        val existing = attempts.values.flatMap { it.deviceActions }.find { it.intent.id == intent.id }
        if (existing != null) return if (existing.intent == intent) DeviceActionWriteResult.AlreadyRecorded
            else DeviceActionWriteResult.Rejected("同一动作调用身份的内容发生变化")
        if (!permitsInput(intent.ticket)) return DeviceActionWriteResult.Rejected("原步骤尝试已不能接收新输入")
        val record = attempts[intent.ticket] ?: return DeviceActionWriteResult.Rejected("执行尝试不存在")
        attempts[intent.ticket] = record.copy(deviceActions = record.deviceActions + DeviceActionRecord(intent, clock()))
        publish()
        return DeviceActionWriteResult.Recorded
    }

    /** Late platform evidence belongs to the old invocation; it cannot advance a newer attempt. */
    @Synchronized
    private fun recordDeviceActionReceipt(ticket: AttemptTicket, id: String, receipt: DeviceActionReceipt): DeviceActionWriteResult {
        if (id.isBlank() || receipt.evidence.isBlank()) return DeviceActionWriteResult.Rejected("动作回执身份或证据缺失")
        val attempt = attempts[ticket] ?: return DeviceActionWriteResult.Rejected("执行尝试不存在")
        val index = attempt.deviceActions.indexOfFirst { it.intent.id == id }
        if (index < 0) return DeviceActionWriteResult.Rejected("动作回执没有对应意图")
        val record = attempt.deviceActions[index]
        if (record.receipt != null) return if (record.receipt == receipt) DeviceActionWriteResult.AlreadyRecorded
            else DeviceActionWriteResult.Rejected("动作回执与已记录证据冲突")
        val records = attempt.deviceActions.toMutableList().also { it[index] = record.copy(receipt = receipt) }
        attempts[ticket] = attempt.copy(deviceActions = records)
        publish()
        return DeviceActionWriteResult.Recorded
    }

    @Synchronized
    private fun recoverNoInput(ticket: AttemptTicket, cause: NoInputRecoveryCause): List<ExecutorEffect> {
        if (active?.ticket != ticket) return emptyList()
        require(!mayHaveDeliveredInput(ticket)) { "已记录的输入不能作为无输入步骤自动重入" }
        if (pending != null) return inputQuiescent(ticket, StepOutcome.Interrupted)
        return change { effects ->
            active = null
            fact(if (cause == NoInputRecoveryCause.PROCESS_RESTART) "process-step-reentered" else "step-reentered", current!!)
            schedule(effects)
        }
    }

    @Synchronized
    private fun inputUnconfirmed(ticket: AttemptTicket, reason: String): List<ExecutorEffect> = change { effects ->
        require(reason.isNotBlank())
        val record = attempts[ticket] ?: return@change
        if (active?.ticket == ticket) {
            revoke(Handoff.UNKNOWN, effects)
            val outcome = StepOutcome.Unknown(reason)
            unknowns[ticket] = UnresolvedResult(record.attempt, reason)
            observedDuringHandoff = merge(observedDuringHandoff, outcome)
            fact("input-unconfirmed", record.attempt.run, reason)
        } else observeLateUnknown(record, StepOutcome.Unknown(reason), effects)
    }

    private fun revoke(handoff: Handoff, effects: MutableList<ExecutorEffect>) {
        val ticket = active?.ticket ?: return
        if (pending != null) return
        pending = Pending(ticket, handoff)
        effects += ExecutorEffect.RevokeInput(ticket)
    }

    private fun schedule(effects: MutableList<ExecutorEffect>) {
        if (manualHold || pending != null || unknowns.isNotEmpty() || active != null) return
        val eligible = (listOfNotNull(current) + waiting).filter { it.request.context == context }
        val best = eligible.sortedWith(compareByDescending<RunProgress> { it.priority }.thenBy { it.sequence }).firstOrNull()
            ?: return
        if (current != best) {
            current?.let { waiting += it }
            waiting.remove(best)
            current = best
        }
        val ticket = AttemptTicket(best.runId, ++generation)
        val attempt = StepAttempt(ticket, best)
        active = attempt
        attempts[ticket] = AttemptRecord(attempt)
        fact("step-started", best)
        effects += ExecutorEffect.ExecuteStep(attempt)
    }

    private fun applyOutcome(attempt: StepAttempt, outcome: StepOutcome) {
        if (outcome is StepOutcome.Unknown) {
            unknowns[attempt.ticket] = UnresolvedResult(attempt, outcome.reason)
            return
        }
        val run = current?.takeIf { it.runId == attempt.ticket.runId } ?: return
        when (outcome) {
            is StepOutcome.Completed -> {
                fact("step-completed", run, outcome.evidence)
                if (run.stepIndex + 1 < run.task.stepIds.size) current = run.copy(stepIndex = run.stepIndex + 1)
                else nextTask(run, false)
            }
            is StepOutcome.Failed -> { fact("task-failed", run, outcome.reason); nextTask(run, true) }
            StepOutcome.Interrupted -> Unit
            is StepOutcome.Unknown -> error("handled above")
        }
    }

    private fun observeLateUnknown(record: AttemptRecord, outcome: StepOutcome.Unknown, effects: MutableList<ExecutorEffect>) {
        // Reconciliation is fresh evidence for this attempt. A replay of its old uncertainty cannot undo it.
        if (record.reconciliation != null) return
        unknowns[record.attempt.ticket] = UnresolvedResult(record.attempt, outcome.reason)
        if (active != null) revoke(Handoff.UNKNOWN, effects)
    }

    private fun nextTask(run: RunProgress, failed: Boolean) {
        val failures = run.failedTaskIds + if (failed) listOf(run.task.id) else emptyList()
        if (!failed) fact("task-completed", run)
        if (run.taskIndex + 1 < run.request.tasks.size) {
            current = run.copy(taskIndex = run.taskIndex + 1, stepIndex = 0, failedTaskIds = failures)
        } else {
            val result = when (failures.size) {
                0 -> RunResult.COMPLETED
                run.request.tasks.size -> RunResult.FAILED
                else -> RunResult.PARTIAL_FAILURE
            }
            fact("run-ended", run, result.name)
            current = null
        }
    }

    private fun finishRequestedEnd() {
        if (endAfterResolution == null || pending != null || active != null || unknowns.isNotEmpty()) return
        current?.let { fact("cancelled", it, endAfterResolution!!.name) }
        current = null
        endAfterResolution = null
    }

    private fun merge(observed: StepOutcome?, quiet: StepOutcome): StepOutcome = when {
        observed == null || observed == quiet -> quiet
        observed is StepOutcome.Unknown -> observed
        quiet is StepOutcome.Unknown -> quiet
        quiet == StepOutcome.Interrupted -> observed
        observed == StepOutcome.Interrupted -> quiet
        else -> StepOutcome.Unknown("工作器与输入通道的结果证据冲突")
    }

    private fun requireOutcome(outcome: StepOutcome) {
        require(when (outcome) {
            is StepOutcome.Completed -> outcome.evidence.isNotBlank()
            is StepOutcome.Failed -> outcome.reason.isNotBlank()
            is StepOutcome.Unknown -> outcome.reason.isNotBlank()
            StepOutcome.Interrupted -> true
        })
    }

    private fun validate(request: ExecutionRequest): String? {
        if (request.ref.id.isBlank() || !request.context.isValid() || request.packageVersion.isBlank()) return "提交身份或版本缺失"
        if (request.tasks.isEmpty()) return "没有可执行任务"
        if (request.ref.kind != SubmissionKind.PLAN && (request.tasks.size != 1 || request.tasks[0].id != request.ref.id)) return "任务引用不一致"
        if (request.tasks.map { it.id }.distinct().size != request.tasks.size) return "计划中任务重复"
        if (request.tasks.any { it.id.isBlank() || it.name.isBlank() || it.priority < 0 || it.stepIds.isEmpty() ||
                it.stepIds.any(String::isBlank) || it.stepIds.distinct().size != it.stepIds.size }) return "任务步骤或优先级无效"
        return try { request.tasks.fold(0L) { sum, task -> Math.addExact(sum, task.priority) }; null }
        catch (_: ArithmeticException) { "计划优先级超出范围" }
    }

    private fun fact(type: String, run: RunProgress, detail: String = "") {
        facts += ExecutionFact(type, run.runId, run.task.id, detail,
            taskName = run.task.name, context = run.request.context,
            packageVersion = run.request.packageVersion, occurredAtEpochMillis = clock(),
            id = (facts.maxOfOrNull { it.id } ?: 0) + 1)
    }
    /**
     * Keeps the state that is rewritten on every transition proportional to live work, not to history:
     * recent facts (full history is appended elsewhere by id), recent command receipts, and attempts of
     * live, unknown or recently finished runs (late results of those are still recognized).
     */
    private fun compact() {
        if (facts.size > FACT_LIMIT) facts.subList(0, facts.size - FACT_LIMIT).clear()
        if (receipts.size > RECEIPT_LIMIT) receipts.keys.take(receipts.size - RECEIPT_LIMIT).forEach(receipts::remove)
        val keep = (listOfNotNull(current, active?.run) + waiting).map { it.runId }.toMutableSet()
        keep += unknowns.keys.map { it.runId }
        pending?.let { keep += it.ticket.runId }
        keep += attempts.keys.map { it.runId }.distinct().sortedDescending().take(RECENT_RUNS)
        attempts.keys.filter { it.runId !in keep }.forEach(attempts::remove)
    }
    private inline fun change(block: (MutableList<ExecutorEffect>) -> Unit): List<ExecutorEffect> {
        val effects = mutableListOf<ExecutorEffect>()
        block(effects)
        publish()
        return effects
    }
    private fun publish() { compact(); revision++; mutableState.value = snapshot() }
    private fun snapshot(): ExecutorSnapshot = ExecutorSnapshot(
        context,
        when {
            pending != null -> when (pending!!.handoff) {
                Handoff.REMOVE -> ExecutorPhase.REMOVING
                Handoff.PREEMPT, Handoff.UNKNOWN -> ExecutorPhase.SWITCHING
                Handoff.PAUSE, Handoff.STOP -> ExecutorPhase.PAUSING
            }
            unknowns.isNotEmpty() -> ExecutorPhase.BLOCKED_UNKNOWN
            manualHold -> ExecutorPhase.PAUSED
            active != null -> ExecutorPhase.RUNNING
            else -> ExecutorPhase.IDLE
        },
        manualHold, current, waiting.sortedWith(compareByDescending<RunProgress> { it.priority }.thenBy { it.sequence }),
        active?.ticket, unknowns.values.toList(), facts.toList(),
        attempts.values.flatMap { it.deviceActions },
    )
}
