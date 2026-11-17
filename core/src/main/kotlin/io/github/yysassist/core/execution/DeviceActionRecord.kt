package io.github.yysassist.core.execution

import kotlinx.serialization.Serializable

/** A single APK-owned input invocation, not a capability name or a resume cursor. */
@Serializable
data class DeviceActionIntent(
    val id: String,
    val ticket: AttemptTicket,
    val actionId: String,
    val capability: String,
    val contractVersion: Int,
    /** Attempt-local observation identity; never a screenshot or an executable payload. */
    val observationReference: String,
) {
    internal fun isValid() = id.isNotBlank() && actionId.isNotBlank() && capability.isNotBlank() &&
        contractVersion > 0 && observationReference.isNotBlank()
}

/** Transport evidence only. COMPLETED does not mean the requested business operation succeeded. */
@Serializable
enum class DeviceActionDelivery { NOT_SENT, COMPLETED, CANCELLED, UNCONFIRMED }

@Serializable
data class DeviceActionReceipt(val delivery: DeviceActionDelivery, val evidence: String)

@Serializable
data class DeviceActionRecord(
    val intent: DeviceActionIntent,
    val recordedAtEpochMillis: Long,
    val receipt: DeviceActionReceipt? = null,
)

sealed interface DeviceActionWriteResult {
    /** Persist this transition before sending input. This does not grant an input lease. */
    data object Recorded : DeviceActionWriteResult
    /** The invocation already exists. A duplicate receipt is not permission to send again. */
    data object AlreadyRecorded : DeviceActionWriteResult
    data class Rejected(val reason: String) : DeviceActionWriteResult
}
