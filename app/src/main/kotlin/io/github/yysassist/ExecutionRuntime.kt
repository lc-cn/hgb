package io.github.yysassist

import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

sealed interface RuntimeStatus {
    data object Loading : RuntimeStatus
    data object Ready : RuntimeStatus
    data class RecoveryBlocked(val message: String) : RuntimeStatus
    data class Failure(val message: String) : RuntimeStatus
}
sealed interface RuntimeCommandResult {
    data class Applied(val reply: ExecutorReply) : RuntimeCommandResult
    data class Rejected(val message: String) : RuntimeCommandResult
    data class Unconfirmed(val message: String) : RuntimeCommandResult
}
class RuntimeReceipt internal constructor(private val result: Deferred<RuntimeCommandResult>) {
    suspend fun await(): RuntimeCommandResult = result.await()
}
enum class TaskStartAvailability { READY, BLOCKED, UNKNOWN }
fun interface ExecutionContextReadiness {
    suspend fun verified(target: TargetProfile, requested: ExecutionContext): Boolean
}

/** Application-owned command/effect host. A cancelled screen cannot cancel an accepted command. */
class ExecutionRuntime(
    private val persistence: ExecutionPersistence,
    private val library: TaskVersionLibrary,
    private val admission: TaskAdmissionRepository,
    private val factory: StepWorkFactory,
    private val scope: CoroutineScope,
    private val contextReadiness: ExecutionContextReadiness = ExecutionContextReadiness { target, requested ->
        target.identityPolicy == IdentityPolicy.NONE && requested.principal == Principal.Anonymous && target.acceptsContext(requested)
    },
) {
    private sealed interface Message {
        data class Command(val command: ExecutorCommand, val reply: CompletableDeferred<RuntimeCommandResult>) : Message
        data class AddTask(val address: TaskAddress, val commandId: String, val reply: CompletableDeferred<RuntimeCommandResult>) : Message
        data class Admission(val candidate: TaskAdmissionDraft?, val context: ExecutionContext?, val commandId: String,
            val reply: CompletableDeferred<RuntimeCommandResult>, val failure: String?) : Message
        data class Bound(val ticket: AttemptTicket, val binding: Job, val bundle: TaskBundle?, val verified: Boolean,
            val failure: String?, val storageUnavailable: Boolean = false) : Message
        data class Finished(val ticket: AttemptTicket, val outcome: StepOutcome) : Message
        data class Drained(val ticket: AttemptTicket, val observation: InputStopObservation) : Message
        data object Readiness : Message
    }
    private data class PendingStart(val attempt: StepAttempt, var binding: Job? = null)
    private data class Running(val work: StepWork, val job: Job, val attempt: StepAttempt, val bundle: TaskBundle,
        var outcome: StepOutcome? = null, var drain: Job? = null)
    private val commands = Channel<Message>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(TaskExecutor().state.value)
    val state = mutableState.asStateFlow()
    private val mutableStatus = MutableStateFlow<RuntimeStatus>(RuntimeStatus.Loading)
    val status = mutableStatus.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()
    private var engine: TaskExecutor? = null
    private var revision = 0L
    private val pendingStarts = mutableMapOf<AttemptTicket, PendingStart>()
    private val running = mutableMapOf<AttemptTicket, Running>()
    private var restoredInput: AttemptTicket? = null
    private val readinessRevision = MutableStateFlow(0L)
    val startConditionChanges: Flow<Unit> = merge(admission.changes.map { Unit }, readinessRevision.map { Unit },
        state.map { it.context }.distinctUntilChanged().map { Unit })
    /** Input lease projection only; queue decisions remain exclusively in TaskExecutor. */
    @Volatile private var inputPermit: AttemptTicket? = null

    init {
        scope.launch {
            try { boot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fault("执行记录读取失败，原数据保留") }
            for (message in commands) {
                try { handle(message) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    fault("执行结果暂无法确认，已停止发送新动作")
                    response(message)?.complete(RuntimeCommandResult.Unconfirmed("执行结果暂无法确认"))
                }
            }
        }
    }

    fun submit(command: ExecutorCommand): RuntimeReceipt = receipt { Message.Command(command, it) }
    fun enqueueTask(address: TaskAddress, commandId: String = UUID.randomUUID().toString()): RuntimeReceipt =
        receipt { Message.AddTask(address, commandId, it) }
    fun readinessChanged() { readinessRevision.update { it + 1 }; commands.trySend(Message.Readiness) }
    /** Read-only candidate assessment; admission still rechecks config/version/context at execution. */
    suspend fun assessStart(address: TaskAddress): TaskStartAvailability = withContext(Dispatchers.IO) {
        try {
            val candidate = admission.task(address)
            val context = if (candidate.target.identityPolicy == IdentityPolicy.NONE)
                ExecutionContext(candidate.target.id, Principal.Anonymous)
            else state.value.context?.takeIf { it.targetId == candidate.target.id } ?: return@withContext TaskStartAvailability.UNKNOWN
            if (!contextReadiness.verified(candidate.target, context)) return@withContext TaskStartAvailability.UNKNOWN
            val request = candidate.request(context)
            if (factory.canStartWithoutObservation(request, library.readVersion(candidate.version))) TaskStartAvailability.READY
            else TaskStartAvailability.UNKNOWN
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ExecutionStateRejected) { TaskStartAvailability.BLOCKED }
        catch (_: Exception) { TaskStartAvailability.UNKNOWN }
    }
    fun permitsInput(ticket: AttemptTicket): Boolean = scope.isActive && status.value == RuntimeStatus.Ready && inputPermit == ticket

    private fun receipt(message: (CompletableDeferred<RuntimeCommandResult>) -> Message): RuntimeReceipt {
        val result = CompletableDeferred<RuntimeCommandResult>()
        if (!scope.isActive || commands.trySend(message(result)).isFailure)
            result.complete(RuntimeCommandResult.Unconfirmed("运行宿主不可用"))
        return RuntimeReceipt(result)
    }
    private fun response(message: Message): CompletableDeferred<RuntimeCommandResult>? = when (message) {
        is Message.Command -> message.reply
        is Message.AddTask -> message.reply
        is Message.Admission -> message.reply
        else -> null
    }
    private suspend fun boot() {
        val loaded = withContext(Dispatchers.IO) { persistence.load() }
        engine = loaded.decode(); revision = loaded.revision
        mutableState.value = engine!!.state.value
        val snapshot = state.value
        val ticket = snapshot.activeTicket
        if (ticket != null) {
            restoredInput = ticket
            val attempt = StepAttempt(ticket, snapshot.current!!)
            val safe = try {
                val bundle = withContext(Dispatchers.IO) { library.readVersion(attempt.run.request.packageVersion) }
                factory.restartHasNoDeviceInput(attempt, bundle) && !engine!!.mayHaveDeliveredInput(ticket)
            } catch (_: InvalidLocalTaskVersion) { false }
            if (safe) {
                val transition = change(ExecutorCommand.RecoverNoInput(ticket), dispatch = false)
                restoredInput = null
                mutableStatus.value = RuntimeStatus.Ready
                dispatch(transition)
            } else {
                change(ExecutorCommand.InputUnconfirmed(ticket, "重新打开后，旧步骤的输入状态未确认"), dispatch = false)
                mutableStatus.value = RuntimeStatus.RecoveryBlocked("旧步骤的输入状态未确认")
            }
        } else {
            mutableStatus.value = RuntimeStatus.Ready
            change(ExecutorCommand.ReadinessChanged)
        }
    }

    private suspend fun handle(message: Message) {
        if (status.value is RuntimeStatus.Failure) {
            response(message)?.complete(RuntimeCommandResult.Unconfirmed("执行存储不可用，原记录已保留"))
            // Outcomes remain represented by the unfinished persisted attempt; never dispatch on a storage fault.
            return
        }
        when (message) {
            is Message.Command -> completeCommand(message.command, message.reply)
            is Message.AddTask -> {
                scope.launch(Dispatchers.IO) {
                    val ready = try {
                    val candidate = admission.task(message.address)
                    val context = when (candidate.target.identityPolicy) {
                        IdentityPolicy.NONE -> ExecutionContext(candidate.target.id, Principal.Anonymous)
                        else -> state.value.context?.takeIf { it.targetId == candidate.target.id }
                            ?: throw ExecutionStateRejected("请先核对当前账号")
                    }
                    if (!contextReadiness.verified(candidate.target, context)) throw ExecutionStateRejected("请先核对当前账号")
                    Message.Admission(candidate, context, message.commandId, message.reply, null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Message.Admission(null, null, message.commandId, message.reply,
                        if (error is ExecutionStateRejected || error is InvalidLocalTaskVersion) error.message else "任务提交准备失败，请重新检查") }
                    commands.send(ready)
                }
            }
            is Message.Admission -> {
                try {
                    if (message.failure != null) throw ExecutionStateRejected(message.failure)
                    val candidate = checkNotNull(message.candidate)
                    val context = checkNotNull(message.context)
                    if (state.value.context != context) {
                        val changed = change(ExecutorCommand.VerifyContext(context))
                        if ((changed.reply as ExecutorReply.ContextChanged).result != ContextChangeResult.Accepted)
                            throw ExecutionStateRejected("当前执行尚未结束，暂不能切换目标或账号")
                    }
                    completeCommand(ExecutorCommand.Enqueue(message.commandId, candidate.request(context)), message.reply)
                } catch (rejected: ExecutionStateRejected) { reject(message.reply, rejected.message ?: "提交未通过") }
            }
            is Message.Bound -> bind(message)
            is Message.Finished -> {
                val record = running[message.ticket]
                if (record == null) {
                    // A late control hint has no business evidence. Late unknown results still reconcile.
                    if (message.outcome != StepOutcome.Interrupted) change(ExecutorCommand.StepFinished(message.ticket, message.outcome))
                }
                else { record.outcome = message.outcome; drain(message.ticket, record) }
            }
            is Message.Drained -> {
                val record = running[message.ticket]
                when (val observation = message.observation) {
                    is InputStopObservation.Unconfirmed -> {
                        change(ExecutorCommand.InputUnconfirmed(message.ticket, observation.reason))
                        mutableStatus.value = RuntimeStatus.RecoveryBlocked(observation.reason)
                    }
                    is InputStopObservation.Quiet -> {
                        running.remove(message.ticket)
                        val outcome = record?.outcome
                        val command = if (outcome == StepOutcome.Interrupted) {
                            if (observation.outcome == StepOutcome.Interrupted && engine?.permitsInput(message.ticket) == true) {
                                // A rejected admission/transition may leave the old attempt active.
                                // Re-entry requires the APK's no-input proof AND independent quiet.
                                val binding = checkNotNull(record)
                                val safe = try { factory.restartHasNoDeviceInput(binding.attempt, binding.bundle) &&
                                    engine?.mayHaveDeliveredInput(message.ticket) == false }
                                catch (_: Exception) { false }
                                if (safe) ExecutorCommand.RecoverNoInput(message.ticket, NoInputRecoveryCause.STEP_INTERRUPTION)
                                else ExecutorCommand.StepSettled(message.ticket, StepOutcome.Unknown("步骤中断后的重入条件未确认"), observation.outcome)
                            } else ExecutorCommand.InputQuiescent(message.ticket, observation.outcome)
                        } else if (outcome == null) ExecutorCommand.InputQuiescent(message.ticket, observation.outcome)
                        else ExecutorCommand.StepSettled(message.ticket, outcome, observation.outcome)
                        change(command)
                    }
                }
            }
            Message.Readiness -> {
                if (restoredInput == null) {
                    mutableStatus.value = RuntimeStatus.Ready
                    pendingStarts.values.toList().forEach(::prepareBinding)
                    change(ExecutorCommand.ReadinessChanged)
                }
            }
        }
    }

    private suspend fun completeCommand(command: ExecutorCommand, reply: CompletableDeferred<RuntimeCommandResult>) {
        try {
            val changed = change(command)
            val rejected = when (val result = changed.reply) {
                is ExecutorReply.Enqueued -> (result.result as? EnqueueResult.Rejected)?.reason
                is ExecutorReply.DeviceActionRecorded -> (result.result as? DeviceActionWriteResult.Rejected)?.reason
                else -> null
            }
            if (rejected == null) reply.complete(RuntimeCommandResult.Applied(changed.reply)) else reject(reply, rejected)
        }
        catch (rejected: ExecutionStateRejected) { reject(reply, rejected.message ?: "提交未通过") }
    }
    private fun reject(reply: CompletableDeferred<RuntimeCommandResult>, message: String) {
        reply.complete(RuntimeCommandResult.Rejected(message)); mutableMessages.tryEmit(message)
    }
    private suspend fun change(command: ExecutorCommand, dispatch: Boolean = true): PreparedExecutorTransition {
        val owner = checkNotNull(engine)
        val prepared = owner.prepare(command)
        // Revocation must not wait for disk. This cannot grant a ticket from uncommitted state.
        inputPermit?.let { if (!prepared.permitsInput(it)) inputPermit = null }
        val encoded = prepared.encodeState()
        if (encoded != prepared.baseEncodedState) {
            val next = try { withContext(Dispatchers.IO) { persistence.persist(revision, prepared) } }
            catch (rejected: ExecutionStateRejected) {
                // A rejected transaction rolled back, so only the still-committed lease can reopen.
                publishInputPermit(owner)
                throw rejected
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Read-only reconciliation of a possibly committed write. Never replay the write or effects blindly.
                val observed = try { withContext(Dispatchers.IO) { persistence.load() } }
                catch (_: Exception) { null }
                if (observed?.revision == revision + 1 && observed.encodedState == encoded) observed.revision
                else throw error
            }
            revision = next
        }
        owner.commit(prepared)
        publishInputPermit(owner)
        mutableState.value = owner.state.value
        if (dispatch) dispatch(prepared)
        return prepared
    }
    private fun publishInputPermit(owner: TaskExecutor) {
        inputPermit = owner.state.value.activeTicket?.takeIf(owner::permitsInput)
    }
    private fun dispatch(prepared: PreparedExecutorTransition) {
        prepared.effects.forEach { effect -> when (effect) {
            is ExecutorEffect.ExecuteStep -> {
                if (state.value.activeTicket == effect.attempt.ticket) {
                    PendingStart(effect.attempt).also { pendingStarts[effect.attempt.ticket] = it; prepareBinding(it) }
                }
            }
            is ExecutorEffect.RevokeInput -> {
                val pending = pendingStarts.remove(effect.ticket)
                val record = running[effect.ticket]
                when {
                    pending != null -> {
                        pending.binding?.cancel()
                        commands.trySend(Message.Drained(effect.ticket, InputStopObservation.Quiet(StepOutcome.Interrupted)))
                    }
                    record != null -> drain(effect.ticket, record)
                    restoredInput == effect.ticket -> mutableStatus.value = RuntimeStatus.RecoveryBlocked("旧输入尚未确认静默")
                }
            }
        } }
    }
    private fun prepareBinding(start: PendingStart) {
        start.binding?.cancel()
        start.binding = scope.launch(Dispatchers.IO) {
            val binding = currentCoroutineContext()[Job]!!
            val message = try {
                val bundle = library.readVersion(start.attempt.run.request.packageVersion)
                val target = bundle.targets.single { it.id == start.attempt.run.request.context.targetId }
                Message.Bound(start.attempt.ticket, binding, bundle, contextReadiness.verified(target, start.attempt.run.request.context), null)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: InvalidLocalTaskVersion) { Message.Bound(start.attempt.ticket, binding, null, false, invalid.message) }
            catch (_: Exception) { Message.Bound(start.attempt.ticket, binding, null, false, "运行条件读取失败，原记录保留", storageUnavailable = true) }
            commands.send(message)
        }
    }
    private suspend fun bind(message: Message.Bound) {
        val pending = pendingStarts[message.ticket] ?: return
        if (pending.binding !== message.binding) return
        if (state.value.activeTicket != message.ticket || engine?.permitsInput(message.ticket) != true) return
        if (message.storageUnavailable) { fault(message.failure ?: "运行条件读取失败"); return }
        val bundle = message.bundle
        if (bundle == null) {
            pendingStarts.remove(message.ticket)
            change(ExecutorCommand.StepFinished(message.ticket, StepOutcome.Failed(message.failure ?: "任务版本不可用")))
            return
        }
        if (!message.verified) {
            mutableStatus.value = RuntimeStatus.RecoveryBlocked("等待核对当前目标与账号")
            return
        }
        val work = try { factory.create(pending.attempt, bundle) }
        catch (_: Exception) {
            pendingStarts.remove(message.ticket)
            change(ExecutorCommand.StepFinished(message.ticket, StepOutcome.Failed("任务步骤不可用")))
            return
        }
        pendingStarts.remove(message.ticket)
        mutableStatus.value = RuntimeStatus.Ready
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try { commands.send(Message.Finished(message.ticket, work.execute())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { commands.send(Message.Finished(message.ticket, StepOutcome.Unknown("步骤结果未确认"))) }
        }
        running[message.ticket] = Running(work, job, pending.attempt, bundle)
        job.start()
    }
    private fun drain(ticket: AttemptTicket, record: Running) {
        if (record.drain != null) return
        record.job.cancel()
        record.drain = scope.launch {
            val observation = try { record.work.revoke() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { InputStopObservation.Unconfirmed("输入状态未确认") }
            commands.send(Message.Drained(ticket, observation))
        }
    }
    private fun fault(message: String) {
        inputPermit = null
        mutableStatus.value = RuntimeStatus.Failure(message)
        pendingStarts.values.forEach { it.binding?.cancel() }
        running.forEach { (ticket, record) -> drain(ticket, record) }
        mutableMessages.tryEmit(message)
    }
}
