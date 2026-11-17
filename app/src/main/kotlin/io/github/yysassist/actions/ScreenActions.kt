package io.github.yysassist.actions

import android.accessibilityservice.GestureDescription
import io.github.yysassist.RuntimeCommandResult
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.device.*
import io.github.yysassist.vision.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.UUID

/** APK primitives. Observations and semantic targets belong to one step attempt, never a task file. */
internal class ScreenActions(
    private val observer: () -> VersionedTextObserver,
    private val permitted: (AttemptTicket) -> Boolean,
    private val device: () -> ObservationDevice? = { GameAccessibilityService.connection.value?.bridge },
    private val journal: (suspend (ExecutorCommand) -> RuntimeCommandResult)? = null,
) : ActionProvider {
    companion object {
        const val READ = "screen.readText"; const val EXPECT = "screen.expectText"
        const val LOCATE = "screen.locateText"; const val TAP = "screen.tap"
        const val CLASSIFY = "screen.classifyText"
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun threshold(inputs: JsonObject): BigDecimal? = (inputs["minimumConfidence"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
    private fun duration(inputs: JsonObject): Long? = try {
        (inputs["durationMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact()
            ?.takeIf { it in 1..GestureDescription.getMaxGestureDuration() }
    } catch (_: ArithmeticException) { null }
    private val modelKeys = setOf("detector", "recognizer", "dictionary")
    private val readKeys = modelKeys + "packageName"
    private val textTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER)
    private fun knownText(inputs: JsonObject): ContentIssue? = when {
        "text" in inputs && inputs.text("text").isNullOrBlank() -> ContentIssue("text", "核对文字不能为空")
        "minimumConfidence" in inputs && threshold(inputs) == null -> ContentIssue("minimumConfidence", "置信度需为零到一之间的数值")
        else -> null
    }
    private fun completeText(inputs: JsonObject): String? = if (inputs.keys != textTypes.keys || inputs.text("observation").isNullOrBlank() ||
        inputs.text("text").isNullOrBlank() || threshold(inputs) == null) "请指定观察、文字及零到一之间的置信度" else null
    private fun priorSource(flow: ActionFlowContext, action: ActionDefinition, parameter: String, field: String, capability: String): ActionDefinition? {
        val source = action.bindings[parameter] as? ActionInputBinding.Output ?: return null
        val found = flow.previous[source.actionId] ?: return null
        if (source.field != field || found.capability != capability || found.contractVersion != 1) return null
        return found.takeIf { it.id in flow.freshOutputs }
    }
    private fun validStates(inputs: JsonObject): Boolean {
        val states = inputs["states"] as? JsonArray ?: return false
        if (states.isEmpty()) return false
        val definitions = states.map { it as? JsonObject ?: return false }
        return definitions.all { state -> state.keys == setOf("id", "text", "minimumConfidence") &&
            !state.text("id").isNullOrBlank() && !state.text("text").isNullOrBlank() && threshold(state) != null } &&
            definitions.map { it.text("id") }.distinct().size == definitions.size
    }
    override val contracts = listOf(
        CapabilityContract(READ, 1, inputTypes = readKeys.associateWith { ActionValueType.STRING },
            outputTypes = mapOf("observation" to ActionValueType.STRING), role = ActionRole.OBSERVATION,
            validateDefinition = { bundle, task, _, action -> when {
                action.bindings.isNotEmpty() -> "截图目标与模型资源必须由任务版本明确指定"
                bundle.targets.singleOrNull { it.id == task.targetId }?.androidPackages?.contains(action.inputs.text("packageName")) != true -> "截图包名不属于任务目标"
                modelKeys.any { key -> bundle.resources.none { it.id == action.inputs.text(key) } } -> "文字识别引用了未声明的模型资源"
                else -> null
            } }) { inputs -> if (inputs.keys != readKeys || readKeys.any { inputs.text(it).isNullOrBlank() }) "请提供完整的截图目标与模型资源" else null },
        CapabilityContract(EXPECT, 1, inputTypes = textTypes, outputTypes = mapOf("matched" to ActionValueType.BOOLEAN),
            role = ActionRole.VERIFICATION, validateKnownInputs = ::knownText,
            validateDefinition = { _, _, step, action -> if (priorSource(step, action, "observation", "observation", READ) == null)
                "文字核对必须引用最近一次输入之后、本步骤前序文字观察" else null }, validateInputs = ::completeText),
        CapabilityContract(LOCATE, 1, inputTypes = textTypes, outputTypes = mapOf("target" to ActionValueType.STRING),
            validateKnownInputs = ::knownText,
            validateDefinition = { _, _, step, action -> if (priorSource(step, action, "observation", "observation", READ) == null)
                "文字定位必须引用本步骤当前输入阶段的前序文字观察" else null }, validateInputs = ::completeText),
        CapabilityContract(CLASSIFY, 1,
            inputTypes = mapOf("observation" to ActionValueType.STRING, "states" to ActionValueType.ARRAY),
            outputTypes = mapOf("state" to ActionValueType.STRING),
            validateKnownInputs = { inputs -> if ("states" in inputs && !validStates(inputs))
                ContentIssue("states", "请明确提供身份唯一的状态、文字及零到一之间的置信度") else null },
            validateDefinition = { _, _, flow, action -> when {
                "states" in action.bindings -> "状态证据必须由任务版本明确指定"
                priorSource(flow, action, "observation", "observation", READ) == null -> "状态分类必须引用当前输入阶段的前序文字观察"
                else -> null
            } }) { inputs -> if (inputs.keys != setOf("observation", "states") || inputs.text("observation").isNullOrBlank() || !validStates(inputs))
                "请指定观察和完整的状态证据" else null },
        CapabilityContract(TAP, 1, inputTypes = mapOf("target" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER),
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            validateKnownInputs = { inputs -> if ("durationMs" in inputs && duration(inputs) == null)
                ContentIssue("durationMs", "手势时长需为平台允许的正整数毫秒") else null },
            validateDefinition = { _, _, step, action -> if (priorSource(step, action, "target", "target", LOCATE) == null)
                "点击必须引用本步骤当前输入阶段的前序定位目标" else null }) { inputs ->
            if (inputs.keys != setOf("target", "durationMs") || inputs.text("target").isNullOrBlank() || duration(inputs) == null)
                "请明确指定定位目标及有效手势时长" else null
        },
    )
    private data class Observation(val bridge: ObservationDevice, val result: TextReadResult.Observed, val models: OcrModelReferences)
    private data class Target(val observation: Observation, val text: String, val minimum: BigDecimal)
    private class Invocation(val intent: DeviceActionIntent) {
        var admission: DeviceActionWriteResult? = null
        var channel: DeviceInputChannel? = null
        var call: InputGestureCall? = null
        var settlement: InputStopObservation? = null
    }
    private sealed interface Acquisition {
        data class Ready(val value: Observation) : Acquisition
        data class Problem(val result: ActionResult) : Acquisition
    }
    private fun deviceReason(reason: DeviceRejection) = when (reason) {
        DeviceRejection.DISCONNECTED -> "无障碍连接已断开"
        DeviceRejection.WRONG_FOREGROUND -> "前台不是目标应用"
        DeviceRejection.SCREEN_CHANGED -> "屏幕方向、尺寸或连接已变化"
        DeviceRejection.DECODE_FAILED -> "截图解码失败"
        else -> "设备观察被拒绝（${reason.name}）"
    }
    private fun readReason(value: TextReadResult.Unavailable): String {
        val message = when (value.problem) {
            TextReadProblem.FRAME_INVALID -> "截图不可用"
            TextReadProblem.RESOURCE_UNAVAILABLE -> "任务版本的模型资源不可用"
            TextReadProblem.STORAGE_ERROR -> "模型资源读取失败"
            TextReadProblem.UNSUPPORTED_MODEL -> "模型合同不兼容"
            TextReadProblem.UNSUPPORTED_ENVIRONMENT -> "当前设备不支持此识别后端"
            TextReadProblem.ENCODING_FAILED -> "截图编码失败"
            TextReadProblem.ENGINE_UNAVAILABLE -> "识别服务未取得观察"
            TextReadProblem.INVALID_OUTPUT -> "识别返回了无效结果"
        }
        return message + listOfNotNull(value.detail).plus(value.diagnostics).takeIf { it.isNotEmpty() }
            ?.joinToString("；", "（", "）").orEmpty()
    }
    private suspend fun observe(attempt: StepAttempt, bundle: TaskBundle, bridge: ObservationDevice, packageName: String, models: OcrModelReferences): Acquisition {
        if (!permitted(attempt.ticket)) return Acquisition.Problem(ActionResult.Interrupted)
        val capture = bridge.capture(packageName)
        if (!permitted(attempt.ticket)) {
            (capture as? FrameResult.Ready)?.frame?.close()
            return Acquisition.Problem(ActionResult.Interrupted)
        }
        if (capture is FrameResult.Unavailable) return Acquisition.Problem(ActionResult.Unavailable(
            deviceReason(capture.reason) + (capture.platformCode?.let { "（截图错误 $it）" } ?: "")))
        check(capture is FrameResult.Ready)
        capture.frame.use { frame ->
            val result = observer().observe(bundle.version, models, frame) { permitted(attempt.ticket) }
            currentCoroutineContext().ensureActive()
            if (!permitted(attempt.ticket) || result == TextReadResult.Interrupted) return Acquisition.Problem(ActionResult.Interrupted)
            if (result is TextReadResult.Unavailable) return Acquisition.Problem(ActionResult.Unavailable(readReason(result)))
            check(result is TextReadResult.Observed)
            if (result.version != bundle.version || result.stamp !== frame.stamp)
                return Acquisition.Problem(ActionResult.Unavailable("观察期间目标画面已变化"))
            bridge.validateObservation(result.stamp)?.let { return Acquisition.Problem(ActionResult.Unavailable(deviceReason(it))) }
            return Acquisition.Ready(Observation(bridge, result, models))
        }
    }
    private fun uniqueTarget(observation: Observation, text: String, minimum: BigDecimal): RecognizedText? {
        val matches = observation.result.candidates.filter { it.text == text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }
        val match = matches.singleOrNull() ?: return null
        val bounds = match.bounds; val geometry = observation.result.stamp.geometry
        return match.takeIf { bounds.left >= 0 && bounds.top >= 0 && bounds.width > 0 && bounds.height > 0 &&
            bounds.left.toLong() + bounds.width <= geometry.width && bounds.top.toLong() + bounds.height <= geometry.height }
    }
    private fun RuntimeCommandResult.writeResult(): DeviceActionWriteResult? =
        ((this as? RuntimeCommandResult.Applied)?.reply as? ExecutorReply.DeviceActionRecorded)?.result

    override fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession = object : ActionSession {
        private val observations = mutableMapOf<String, Observation>()
        private val targets = mutableMapOf<String, Target>()
        private val invocations = mutableListOf<Invocation>()
        private var businessEvidence: StepOutcome.Completed? = null
        private var terminalAction: String? = null
        override fun prepareAction(action: ActionDefinition, completesStep: Boolean) {
            terminalAction = action.id.takeIf { completesStep }
        }
        private suspend fun settle(invocation: Invocation): InputStopObservation {
            invocation.settlement?.let { return it }
            val result = withContext(NonCancellable) {
                if (invocation.admission is DeviceActionWriteResult.Rejected) return@withContext InputStopObservation.Quiet(StepOutcome.Interrupted)
                if (invocation.admission != DeviceActionWriteResult.Recorded) return@withContext InputStopObservation.Unconfirmed("动作意图或重复调用的旧输入尚未确认")
                val drain = invocation.channel?.revoke()
                val delivery = if (drain == InputDrain.Unconfirmed) DeviceActionDelivery.UNCONFIRMED
                else when (invocation.call?.observeAfterRevoke()) {
                    null, is GestureResult.Rejected -> DeviceActionDelivery.NOT_SENT
                    GestureResult.Completed -> DeviceActionDelivery.COMPLETED
                    GestureResult.Cancelled -> DeviceActionDelivery.CANCELLED
                    GestureResult.Unconfirmed -> DeviceActionDelivery.UNCONFIRMED
                }
                val evidence = when (delivery) {
                    DeviceActionDelivery.NOT_SENT -> "输入所有者确认未投递"
                    DeviceActionDelivery.COMPLETED -> "平台完成回调"
                    DeviceActionDelivery.CANCELLED -> "平台取消回调"
                    DeviceActionDelivery.UNCONFIRMED -> "平台输入状态未确认"
                }
                val saved = journal!!(ExecutorCommand.RecordDeviceActionReceipt(attempt.ticket, invocation.intent.id, DeviceActionReceipt(delivery, evidence)))
                if (saved.writeResult() !in listOf(DeviceActionWriteResult.Recorded, DeviceActionWriteResult.AlreadyRecorded))
                    InputStopObservation.Unconfirmed("动作回执未能持久确认")
                else if (drain == InputDrain.Unconfirmed || delivery == DeviceActionDelivery.UNCONFIRMED)
                    InputStopObservation.Unconfirmed("平台输入尚未确认静默")
                else InputStopObservation.Quiet(if (delivery == DeviceActionDelivery.NOT_SENT) StepOutcome.Interrupted
                    else StepOutcome.Unknown("输入已静默，业务结果尚未核对"))
            }
            invocation.settlement = result
            return result
        }
        override suspend fun perform(action: ActionDefinition, inputs: JsonObject): ActionResult {
            currentCoroutineContext().ensureActive()
            if (!permitted(attempt.ticket)) return ActionResult.Interrupted
            return when (action.capability) {
                READ -> {
                    val bridge = device() ?: return ActionResult.Unavailable("未连接无障碍服务，未取得画面")
                    val models = OcrModelReferences(MaaTextBackend.CONTRACT, modelKeys.associateWith { checkNotNull(inputs.text(it)) })
                    when (val acquired = observe(attempt, bundle, bridge, checkNotNull(inputs.text("packageName")), models)) {
                        is Acquisition.Problem -> acquired.result
                        is Acquisition.Ready -> {
                            val token = UUID.randomUUID().toString(); observations[token] = acquired.value
                            ActionResult.Produced(buildJsonObject { put("observation", token) })
                        }
                    }
                }
                EXPECT, LOCATE, CLASSIFY -> {
                    val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
                    observation.bridge.validateObservation(observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
                    if (!permitted(attempt.ticket)) return ActionResult.Interrupted
                    if (action.capability == CLASSIFY) {
                        val matches = inputs.getValue("states").jsonArray.map { it.jsonObject }.filter { state ->
                            observation.result.candidates.any { it.text == state.text("text") && it.confidence.isFinite() &&
                                BigDecimal.valueOf(it.confidence) >= checkNotNull(threshold(state)) }
                        }
                        if (matches.size != 1) return ActionResult.Unavailable(if (matches.isEmpty())
                            "本次画面未能确认任何声明状态" else "本次画面同时命中多个声明状态")
                        return ActionResult.Produced(buildJsonObject { put("state", checkNotNull(matches.single().text("id"))) })
                    }
                    val expected = checkNotNull(inputs.text("text")); val minimum = checkNotNull(threshold(inputs))
                    if (action.capability == LOCATE) {
                        if (uniqueTarget(observation, expected, minimum) == null) return ActionResult.Unavailable("本次画面不能唯一定位目标文字")
                        val token = UUID.randomUUID().toString(); targets[token] = Target(observation, expected, minimum)
                        ActionResult.Produced(buildJsonObject { put("target", token) })
                    } else {
                        if (observation.result.candidates.none { it.text == expected && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum })
                            return ActionResult.Unavailable("本次画面中未能确认目标文字")
                        val evidence = "已在本次画面中核对目标文字" + observation.result.diagnostics.takeIf { it.isNotEmpty() }
                            ?.joinToString("；", "（识别清理诊断：", "）").orEmpty()
                        if (terminalAction == action.id) businessEvidence = StepOutcome.Completed(evidence)
                        ActionResult.Verified(buildJsonObject { put("matched", true) }, evidence)
                    }
                }
                TAP -> tap(action, inputs)
                else -> error("未注册的屏幕动作")
            }
        }
        override fun verifiedStepCompleted(evidence: String) {
            businessEvidence = StepOutcome.Completed(evidence)
        }
        private suspend fun tap(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val target = targets[inputs.text("target")] ?: return ActionResult.Unavailable("本步骤的定位目标不可用")
            val bridge = target.observation.bridge as? InputDevicePort ?: return ActionResult.Unavailable("当前观察来源未提供输入所有权")
            if (journal == null) return ActionResult.Unavailable("未连接持久化执行器")
            // Resolve the semantic target again inside the input action; do not click old coordinates.
            val fresh = when (val acquired = observe(attempt, bundle, bridge, checkNotNull(target.observation.result.stamp.packageName), target.observation.models)) {
                is Acquisition.Problem -> return acquired.result
                is Acquisition.Ready -> acquired.value
            }
            val match = uniqueTarget(fresh, target.text, target.minimum) ?: return ActionResult.Unavailable("点击前不能唯一确认目标文字")
            val invocation = Invocation(DeviceActionIntent(UUID.randomUUID().toString(), attempt.ticket, action.id,
                action.capability, action.contractVersion, UUID.randomUUID().toString()))
            invocations += invocation; businessEvidence = null
            suspend fun dispatch(): ActionResult {
                // Cancellation cannot lose whether this accepted command committed. It never opens input.
                val admission = withContext(NonCancellable) {
                    journal(ExecutorCommand.RecordDeviceAction(invocation.intent)).also {
                        invocation.admission = it.writeResult() ?: (it as? RuntimeCommandResult.Rejected)?.let { rejected -> DeviceActionWriteResult.Rejected(rejected.message) }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (invocation.admission != DeviceActionWriteResult.Recorded) return ActionResult.Unavailable(
                    (admission as? RuntimeCommandResult.Rejected)?.message ?: "未取得新的持久化动作许可")
                if (!permitted(attempt.ticket)) return ActionResult.Interrupted
                observations.clear(); targets.clear()
                val channel = bridge.openInput(attempt.ticket, permitted) ?: return if (!permitted(attempt.ticket)) ActionResult.Interrupted
                    else ActionResult.Unavailable("输入通道当前不可用")
                invocation.channel = channel
                val bounds = match.bounds
                val call = channel.gestureCall(fresh.result.stamp, listOf(ScreenPoint(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)), checkNotNull(duration(inputs)))
                invocation.call = call
                return when (val result = call.execute()) {
                    GestureResult.Completed -> ActionResult.Produced(buildJsonObject { put("delivered", true) })
                    is GestureResult.Rejected -> if (!permitted(attempt.ticket)) ActionResult.Interrupted else ActionResult.Unavailable(deviceReason(result.reason))
                    GestureResult.Cancelled -> ActionResult.Unavailable("平台取消了手势，业务结果尚未核对")
                    GestureResult.Unconfirmed -> ActionResult.Unavailable("平台输入状态未确认")
                }
            }
            val answer = try { dispatch() } finally { settle(invocation) }
            return (invocation.settlement as? InputStopObservation.Unconfirmed)?.let { ActionResult.Unavailable(it.reason) } ?: answer
        }
        override suspend fun revoke(): InputStopObservation {
            val settled = invocations.map { settle(it) }
            settled.filterIsInstance<InputStopObservation.Unconfirmed>().firstOrNull()?.let { return it }
            businessEvidence?.let { return InputStopObservation.Quiet(it) }
            return settled.filterIsInstance<InputStopObservation.Quiet>().firstOrNull { it.outcome is StepOutcome.Unknown }
                ?: InputStopObservation.Quiet(StepOutcome.Interrupted)
        }
    }
}
