package io.github.yysassist.actions

import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.device.*
import io.github.yysassist.vision.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.UUID

/** APK primitives, not game tasks. Session tokens are local to one step attempt and never persisted. */
internal class TextObservationActions(
    private val observer: () -> VersionedTextObserver,
    private val permitted: (AttemptTicket) -> Boolean,
    private val device: () -> ObservationDevice? = { GameAccessibilityService.connection.value?.bridge },
) : ReadOnlyActionProvider {
    companion object { const val READ = "screen.readText"; const val EXPECT = "screen.expectText" }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun threshold(inputs: JsonObject): BigDecimal? = (inputs["minimumConfidence"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
    private val modelKeys = setOf("detector", "recognizer", "dictionary")
    private val readKeys = modelKeys + "packageName"
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
    override val contracts = listOf(
        CapabilityContract(READ, 1, inputTypes = readKeys.associateWith { ActionValueType.STRING },
            outputTypes = mapOf("observation" to ActionValueType.STRING), role = ActionRole.OBSERVATION,
            validateDefinition = { bundle, task, _, action ->
                when {
                    action.bindings.isNotEmpty() -> "截图目标与模型资源必须由任务版本明确指定"
                    bundle.targets.singleOrNull { it.id == task.targetId }?.androidPackages?.contains(action.inputs.text("packageName")) != true -> "截图包名不属于任务目标"
                    modelKeys.any { key -> bundle.resources.none { it.id == action.inputs.text(key) } } -> "文字识别引用了未声明的模型资源"
                    else -> null
                }
            }) { inputs -> if (inputs.keys != readKeys || readKeys.any { inputs.text(it).isNullOrBlank() }) "请提供完整的截图目标与模型资源" else null },
        CapabilityContract(EXPECT, 1,
            inputTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER),
            outputTypes = mapOf("matched" to ActionValueType.BOOLEAN), role = ActionRole.VERIFICATION,
            validateKnownInputs = { inputs -> when {
                "text" in inputs && inputs.text("text").isNullOrBlank() -> ContentIssue("text", "核对文字不能为空")
                "minimumConfidence" in inputs && threshold(inputs) == null -> ContentIssue("minimumConfidence", "置信度需为零到一之间的数值")
                else -> null
            } },
            validateDefinition = { _, _, step, action ->
                val source = action.bindings["observation"] as? ActionInputBinding.Output
                val earlier = step.actions.takeWhile { it.id != action.id }.singleOrNull { it.id == source?.actionId }
                if (source?.field != "observation" || earlier?.capability != READ || earlier.contractVersion != 1)
                    "文字核对必须引用本步骤前序文字观察" else null
            }) { inputs ->
            if (inputs.keys != setOf("observation", "text", "minimumConfidence") || inputs.text("observation").isNullOrBlank() ||
                inputs.text("text").isNullOrBlank() || threshold(inputs) == null) "请指定核对文字及零到一之间的置信度" else null
        },
    )

    override fun open(attempt: StepAttempt, bundle: TaskBundle): ReadOnlyActionSession {
        data class Observation(val bridge: ObservationDevice, val result: TextReadResult.Observed)
        val values = mutableMapOf<String, Observation>()
        return ReadOnlyActionSession { action, inputs ->
            currentCoroutineContext().ensureActive()
            if (!permitted(attempt.ticket)) return@ReadOnlyActionSession ReadOnlyActionResult.Interrupted
            when (action.capability) {
                READ -> {
                    val bridge = device()
                        ?: return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable("未连接无障碍服务，未取得画面")
                    val capture = bridge.capture(checkNotNull(inputs.text("packageName")))
                    if (!permitted(attempt.ticket)) {
                        (capture as? FrameResult.Ready)?.frame?.close()
                        return@ReadOnlyActionSession ReadOnlyActionResult.Interrupted
                    }
                    if (capture is FrameResult.Unavailable) return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable(
                        deviceReason(capture.reason) + (capture.platformCode?.let { "（截图错误 $it）" } ?: ""))
                    check(capture is FrameResult.Ready)
                    capture.frame.use { frame ->
                        val models = OcrModelReferences(MaaTextBackend.CONTRACT, modelKeys.associateWith { checkNotNull(inputs.text(it)) })
                        val result = observer().observe(bundle.version, models, frame) { permitted(attempt.ticket) }
                        currentCoroutineContext().ensureActive()
                        if (!permitted(attempt.ticket) || result == TextReadResult.Interrupted)
                            return@ReadOnlyActionSession ReadOnlyActionResult.Interrupted
                        if (result is TextReadResult.Unavailable) return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable(readReason(result))
                        check(result is TextReadResult.Observed)
                        if (result.version != bundle.version || result.stamp !== frame.stamp)
                            return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable("观察期间目标画面已变化")
                        bridge.validateObservation(result.stamp)?.let {
                            return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable(deviceReason(it))
                        }
                        val token = UUID.randomUUID().toString()
                        values[token] = Observation(bridge, result)
                        ReadOnlyActionResult.Produced(buildJsonObject { put("observation", token) })
                    }
                }
                EXPECT -> {
                    val observation = values[inputs.text("observation")]
                        ?: return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable("本步骤的画面观察不可用")
                    observation.bridge.validateObservation(observation.result.stamp)?.let {
                        return@ReadOnlyActionSession ReadOnlyActionResult.Unavailable(deviceReason(it))
                    }
                    if (!permitted(attempt.ticket)) return@ReadOnlyActionSession ReadOnlyActionResult.Interrupted
                    val expected = checkNotNull(inputs.text("text")); val minimum = checkNotNull(threshold(inputs))
                    val matched = observation.result.candidates.any { it.text == expected && BigDecimal.valueOf(it.confidence) >= minimum }
                    if (!matched) ReadOnlyActionResult.Unavailable("本次画面中未能确认目标文字")
                    else ReadOnlyActionResult.Verified(buildJsonObject { put("matched", true) }, "已在本次画面中核对目标文字" +
                        observation.result.diagnostics.takeIf { it.isNotEmpty() }?.joinToString("；", "（识别清理诊断：", "）").orEmpty())
                }
                else -> error("未注册的只读动作")
            }
        }
    }
}
