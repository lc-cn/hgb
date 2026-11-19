package io.github.yysassist.core.execution

import io.github.yysassist.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceActionRecordTest {
    private val context = ExecutionContext("fixture", Principal.Anonymous)
    private fun executor(): TaskExecutor = TaskExecutor(context) { 123L }.apply {
        enqueue("request", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "task"), context, "v1",
            listOf(TaskSnapshot("task", "任务", 1, listOf("step")))))
    }
    private fun intent(e: TaskExecutor, id: String = "call-1") = DeviceActionIntent(id, e.state.value.activeTicket!!,
        "tap", "screen.tap", 1, "attempt-local-frame-1")
    private fun write(e: TaskExecutor, command: ExecutorCommand): DeviceActionWriteResult {
        val next = e.prepare(command)
        assertTrue(next.effects.isEmpty()); e.commit(next)
        return (next.reply as ExecutorReply.DeviceActionRecorded).result
    }
    private val complete = DeviceActionReceipt(DeviceActionDelivery.COMPLETED, "平台完成回调")

    @Test fun intentIsDetachedUntilCommitAndDoesNotGrantAnotherLeaseOrCompleteTheStep() {
        val e = executor(); val call = intent(e); val before = e.state.value
        val prepared = e.prepare(ExecutorCommand.RecordDeviceAction(call))
        assertEquals(before, e.state.value)
        assertEquals(DeviceActionWriteResult.Recorded, (prepared.reply as ExecutorReply.DeviceActionRecorded).result)
        assertTrue(prepared.effects.isEmpty()); e.commit(prepared)
        assertEquals(DeviceActionRecord(call, 123L), e.state.value.deviceActions.single())
        assertEquals(before.activeTicket, e.state.value.activeTicket); assertEquals(before.facts, e.state.value.facts)
        assertTrue(e.permitsInput(call.ticket)); assertTrue(e.mayHaveDeliveredInput(call.ticket))
    }

    @Test fun identicalIdentityIsIdempotentButNeverReturnsNewAdmissionTwice() {
        val e = executor(); val call = intent(e)
        assertEquals(DeviceActionWriteResult.Recorded, write(e, ExecutorCommand.RecordDeviceAction(call)))
        val stored = e.encodeState()
        assertEquals(DeviceActionWriteResult.AlreadyRecorded, write(e, ExecutorCommand.RecordDeviceAction(call)))
        assertTrue(write(e, ExecutorCommand.RecordDeviceAction(call.copy(actionId = "other"))) is DeviceActionWriteResult.Rejected)
        assertTrue(write(e, ExecutorCommand.RecordDeviceAction(call.copy(id = "", observationReference = ""))) is DeviceActionWriteResult.Rejected)
        assertEquals(stored, e.encodeState())
    }

    @Test fun closingInputRejectsNewIntentsAndOldDuplicatesCannotReopenIt() {
        val e = executor(); val call = intent(e)
        write(e, ExecutorCommand.RecordDeviceAction(call)); e.pause()
        val paused = e.encodeState()
        assertTrue(write(e, ExecutorCommand.RecordDeviceAction(call.copy(id = "new-call"))) is DeviceActionWriteResult.Rejected)
        assertEquals(DeviceActionWriteResult.AlreadyRecorded, write(e, ExecutorCommand.RecordDeviceAction(call)))
        assertFalse(e.permitsInput(call.ticket)); assertEquals(paused, e.encodeState())
    }

    @Test fun platformReceiptIsNotBusinessSuccessAndConflictingEvidenceCannotOverwriteIt() {
        val e = executor(); val call = intent(e)
        write(e, ExecutorCommand.RecordDeviceAction(call)); val before = e.state.value
        val receipt = ExecutorCommand.RecordDeviceActionReceipt(call.ticket, call.id, complete)
        assertEquals(DeviceActionWriteResult.Recorded, write(e, receipt))
        assertEquals(before.copy(deviceActions = e.state.value.deviceActions), e.state.value)
        assertEquals(DeviceActionWriteResult.AlreadyRecorded, write(e, receipt))
        val stored = e.encodeState()
        assertTrue(write(e, receipt.copy(receipt = complete.copy(delivery = DeviceActionDelivery.CANCELLED))) is DeviceActionWriteResult.Rejected)
        assertTrue(write(e, receipt.copy(ticket = call.ticket.copy(generation = 999))) is DeviceActionWriteResult.Rejected)
        assertTrue(write(e, receipt.copy(invocationId = "absent")) is DeviceActionWriteResult.Rejected)
        assertTrue(write(e, receipt.copy(receipt = complete.copy(evidence = ""))) is DeviceActionWriteResult.Rejected)
        assertEquals(stored, e.encodeState())
    }

    @Test fun lateReceiptRemainsWithTheOldAttemptAndCannotResolveUnknownOrReviveRemovedWork() {
        val e = executor(); val call = intent(e)
        write(e, ExecutorCommand.RecordDeviceAction(call))
        e.remove(call.ticket.runId); e.inputQuiescent(call.ticket, StepOutcome.Unknown("输入已静默，业务结果未核对"))
        val unknown = e.state.value
        write(e, ExecutorCommand.RecordDeviceActionReceipt(call.ticket, call.id, complete))
        assertEquals(unknown.copy(deviceActions = e.state.value.deviceActions), e.state.value)
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, e.state.value.phase)
        val restored = TaskExecutor.fromEncodedState(e.encodeState())
        assertEquals(e.state.value, restored.state.value)
        restored.resolveUnknown(call.ticket, StepOutcome.Interrupted)
        assertNull(restored.state.value.current)
        assertEquals(call, restored.state.value.deviceActions.single().intent)
    }

    @Test fun anInputRecordContradictsNoInputRecoveryUnlessTheOwnerProvedItWasNotSent() {
        val e = executor(); val call = intent(e)
        write(e, ExecutorCommand.RecordDeviceAction(call))
        val before = e.encodeState()
        assertTrue(runCatching { e.prepare(ExecutorCommand.RecoverNoInput(call.ticket)) }.isFailure)
        assertEquals(before, e.encodeState())
        write(e, ExecutorCommand.RecordDeviceActionReceipt(call.ticket, call.id,
            DeviceActionReceipt(DeviceActionDelivery.NOT_SENT, "输入所有者确认未投递")))
        assertFalse(e.mayHaveDeliveredInput(call.ticket))
        val next = e.prepare(ExecutorCommand.RecoverNoInput(call.ticket)); e.commit(next)
        assertNotEquals(call.ticket, e.state.value.activeTicket)
        assertEquals(call, e.state.value.deviceActions.single().intent)
    }

    @Test fun oldPackedAttemptsWithoutActionFieldsStillLoadAndCorruptRecordOwnershipIsRejected() {
        val e = executor(); val json = Json
        assertFalse(e.encodeState().contains("\"deviceActions\""))
        val root = json.parseToJsonElement(e.encodeState()).jsonObject
        val old = JsonObject(root + ("attempts" to JsonArray(root.getValue("attempts").jsonArray.map {
            JsonObject(it.jsonObject - "deviceActions")
        })))
        assertEquals(e.state.value, TaskExecutor.fromEncodedState(old.toString()).state.value)
        val call = intent(e); write(e, ExecutorCommand.RecordDeviceAction(call))
        val stored = e.encodeState()
        assertTrue(runCatching { TaskExecutor.fromEncodedState(stored.replace("attempt-local-frame-1", "")) }.isFailure)
        // A second record under the same attempt must still have a globally unique invocation ID.
        val withCall = json.parseToJsonElement(stored).jsonObject
        val attempt = withCall.getValue("attempts").jsonArray.single().jsonObject
        val record = attempt.getValue("deviceActions").jsonArray.single()
        val payload = record.jsonObject.getValue("intent").jsonObject
        val wrongTicket = JsonObject(payload.getValue("ticket").jsonObject + ("generation" to JsonPrimitive(999)))
        val wrongOwner = JsonObject(record.jsonObject + ("intent" to JsonObject(payload + ("ticket" to wrongTicket))))
        val invalidOwner = JsonObject(withCall + ("attempts" to JsonArray(listOf(JsonObject(attempt +
            ("deviceActions" to JsonArray(listOf(wrongOwner))))))))
        assertTrue(runCatching { TaskExecutor.fromEncodedState(invalidOwner.toString()) }.isFailure)
        val duplicate = JsonObject(withCall + ("attempts" to JsonArray(listOf(JsonObject(attempt +
            ("deviceActions" to JsonArray(listOf(record, record))))))))
        assertTrue(runCatching { TaskExecutor.fromEncodedState(duplicate.toString()) }.isFailure)
    }
}
