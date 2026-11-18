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
        const val DERIVE = "screen.deriveRegion"; const val READ_REGION = "screen.readRegionText"
        const val MATCH = "screen.matchImage"; const val CLASSIFY_REGION = "screen.classifyRegion"
        const val LONG_PRESS = "screen.longPress"; const val SWIPE = "screen.swipe"
        /** Handle kinds. Region-derived observations keep REGION in their lineage. */
        const val OBSERVATION = "screen.observation"; const val TARGET = "screen.target"; const val REGION = "screen.region"
        private const val MAX_STATES = 8; private const val MAX_TEMPLATES = 16; private const val MAX_OFFSET = 100_000
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun unit(value: JsonElement?): BigDecimal? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content
        ?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
    private fun threshold(inputs: JsonObject): BigDecimal? = unit(inputs["minimumConfidence"])
    private fun integer(value: JsonElement?): Int? = try {
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.intValueExact()
    } catch (_: ArithmeticException) { null }
    private fun duration(inputs: JsonObject): Long? = try {
        (inputs["durationMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact()
            ?.takeIf { it in 1..GestureDescription.getMaxGestureDuration() }
    } catch (_: ArithmeticException) { null }
    private val modelKeys = setOf("detector", "recognizer", "dictionary")
    private val observationHandle = mapOf("observation" to HandleRequirement(setOf(OBSERVATION)))
    private val regionHandle = mapOf("region" to HandleRequirement(setOf(REGION)))
    private val textTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER)
    private val geometryKeys = listOf("dx", "dy", "width", "height", "referenceWidth", "referenceHeight")
    private val swipeKeys = listOf("dx", "dy", "referenceWidth", "referenceHeight")
    private fun knownText(inputs: JsonObject): ContentIssue? = when {
        "text" in inputs && inputs.text("text").isNullOrBlank() -> ContentIssue("text", "核对文字不能为空")
        "minimumConfidence" in inputs && threshold(inputs) == null -> ContentIssue("minimumConfidence", "置信度需为零到一之间的数值")
        else -> null
    }
    private fun completeText(inputs: JsonObject): String? = if (inputs.keys != textTypes.keys || inputs.text("observation").isNullOrBlank() ||
        inputs.text("text").isNullOrBlank() || threshold(inputs) == null) "请指定观察、文字及零到一之间的置信度" else null
    private fun validStates(inputs: JsonObject): Boolean {
        val states = inputs["states"] as? JsonArray ?: return false
        if (states.isEmpty()) return false
        val definitions = states.map { it as? JsonObject ?: return false }
        return definitions.all { state -> state.keys == setOf("id", "text", "minimumConfidence") &&
            !state.text("id").isNullOrBlank() && !state.text("text").isNullOrBlank() && threshold(state) != null } &&
            definitions.map { it.text("id") }.distinct().size == definitions.size
    }
    /** Parsed classifyRegion states, or null when any part is not explicit. */
    private data class TextCondition(val text: String, val minimum: BigDecimal)
    private data class ImageCondition(val template: String, val minimum: BigDecimal)
    private data class RegionState(val id: String, val text: List<TextCondition>, val present: List<ImageCondition>, val absent: List<ImageCondition>)
    private fun regionStates(inputs: JsonObject): List<RegionState>? {
        val states = inputs["states"] as? JsonArray ?: return null
        if (states.isEmpty() || states.size > MAX_STATES) return null
        fun list(value: JsonElement?, parse: (JsonObject) -> Any?): List<Any>? =
            if (value == null) emptyList() else (value as? JsonArray)?.map { (it as? JsonObject)?.let(parse) ?: return null }
        val parsed = states.map { element ->
            val state = element as? JsonObject ?: return null
            if (!setOf("id", "text", "present", "absent").containsAll(state.keys)) return null
            val text = list(state["text"]) { c -> if (c.keys != setOf("text", "minimumConfidence") || c.text("text").isNullOrBlank()) null
                else threshold(c)?.let { TextCondition(c.text("text")!!, it) } } ?: return null
            fun image(key: String) = list(state[key]) { c -> if (c.keys != setOf("template", "minimumScore") || c.text("template").isNullOrBlank()) null
                else unit(c["minimumScore"])?.let { ImageCondition(c.text("template")!!, it) } }
            val present = image("present") ?: return null; val absent = image("absent") ?: return null
            val id = state.text("id")?.takeIf { it.isNotBlank() } ?: return null
            if (text.isEmpty() && present.isEmpty() && absent.isEmpty()) return null
            @Suppress("UNCHECKED_CAST") RegionState(id, text as List<TextCondition>, present as List<ImageCondition>, absent as List<ImageCondition>)
        }
        if (parsed.map { it.id }.distinct().size != parsed.size) return null
        if (parsed.flatMap { (it.present + it.absent).map(ImageCondition::template) }.distinct().size > MAX_TEMPLATES) return null
        return parsed
    }
    private fun profileIssue(bundle: TaskBundle, task: TaskDefinition, action: ActionDefinition): String? {
        if (action.bindings.isNotEmpty()) return "视觉配置必须由任务版本明确指定"
        val profile = bundle.visionProfiles.singleOrNull { it.id == action.inputs.text("profile") } ?: return "引用的视觉配置不存在"
        return when {
            profile.targetId != task.targetId -> "视觉配置不属于任务目标"
            profile.recognizer != MaaTextBackend.CONTRACT || profile.models.keys != modelKeys -> "视觉配置的识别合同或模型角色不受支持"
            else -> null
        }
    }
    override val contracts = listOf(
        CapabilityContract(READ, 2, inputTypes = mapOf("profile" to ActionValueType.STRING),
            outputTypes = mapOf("observation" to ActionValueType.STRING), role = ActionRole.OBSERVATION,
            outputHandles = mapOf("observation" to OBSERVATION),
            validateDefinition = { bundle, task, _, action -> profileIssue(bundle, task, action) }) { inputs ->
            if (inputs.keys != setOf("profile") || inputs.text("profile").isNullOrBlank()) "请指定视觉配置" else null
        },
        CapabilityContract(EXPECT, 1, inputTypes = textTypes, outputTypes = mapOf("matched" to ActionValueType.BOOLEAN),
            role = ActionRole.VERIFICATION, inputHandles = observationHandle, validateKnownInputs = ::knownText, validateInputs = ::completeText),
        CapabilityContract(LOCATE, 1, inputTypes = textTypes, outputTypes = mapOf("target" to ActionValueType.STRING),
            outputHandles = mapOf("target" to TARGET), inputHandles = observationHandle,
            validateKnownInputs = ::knownText, validateInputs = ::completeText),
        CapabilityContract(CLASSIFY, 1,
            inputTypes = mapOf("observation" to ActionValueType.STRING, "states" to ActionValueType.ARRAY),
            outputTypes = mapOf("state" to ActionValueType.STRING), inputHandles = observationHandle,
            validateKnownInputs = { inputs -> if ("states" in inputs && !validStates(inputs))
                ContentIssue("states", "请明确提供身份唯一的状态、文字及零到一之间的置信度") else null },
            validateDefinition = { _, _, _, action -> if ("states" in action.bindings) "状态证据必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("observation", "states") || inputs.text("observation").isNullOrBlank() || !validStates(inputs))
                "请指定观察和完整的状态证据" else null },
        CapabilityContract(TAP, 1, inputTypes = mapOf("target" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER),
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            // A click target is re-resolved on a new full frame; region lineage cannot be replayed there.
            inputHandles = mapOf("target" to HandleRequirement(setOf(TARGET), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> if ("durationMs" in inputs && duration(inputs) == null)
                ContentIssue("durationMs", "手势时长需为平台允许的正整数毫秒") else null }) { inputs ->
            if (inputs.keys != setOf("target", "durationMs") || inputs.text("target").isNullOrBlank() || duration(inputs) == null)
                "请明确指定定位目标及有效手势时长" else null
        },
        CapabilityContract(LONG_PRESS, 1, inputTypes = mapOf("target" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER),
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            inputHandles = mapOf("target" to HandleRequirement(setOf(TARGET), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> if ("durationMs" in inputs && duration(inputs) == null)
                ContentIssue("durationMs", "按住时长需为平台允许的正整数毫秒") else null }) { inputs ->
            if (inputs.keys != setOf("target", "durationMs") || inputs.text("target").isNullOrBlank() || duration(inputs) == null)
                "请明确指定定位目标及按住时长" else null
        },
        CapabilityContract(SWIPE, 1, inputTypes = mapOf("target" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER) +
            swipeKeys.associateWith { ActionValueType.NUMBER },
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            inputHandles = mapOf("target" to HandleRequirement(setOf(TARGET), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> when {
                "durationMs" in inputs && duration(inputs) == null -> ContentIssue("durationMs", "滑动时长需为平台允许的正整数毫秒")
                else -> swipeKeys.firstOrNull { key -> key in inputs && integer(inputs[key]).let { value ->
                    value == null || (if (key.startsWith("d")) value !in -MAX_OFFSET..MAX_OFFSET else value !in 1..MAX_OFFSET) } }
                    ?.let { ContentIssue(it, "滑动位移需为整数，参考尺寸需为正整数") }
                    ?: if (integer(inputs["dx"]) == 0 && integer(inputs["dy"]) == 0) ContentIssue("dx", "滑动位移不能为零") else null
            } },
            validateDefinition = { _, _, _, action -> if (swipeKeys.any { it in action.bindings }) "滑动位移必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("target", "durationMs") + swipeKeys || inputs.text("target").isNullOrBlank() || duration(inputs) == null ||
                swipeKeys.any { integer(inputs[it]) == null }) "请明确指定起点目标、位移、参考尺寸及滑动时长" else null
        },
        CapabilityContract(DERIVE, 1, inputTypes = mapOf("target" to ActionValueType.STRING) + geometryKeys.associateWith { ActionValueType.NUMBER },
            outputTypes = mapOf("region" to ActionValueType.STRING), outputHandles = mapOf("region" to REGION),
            inputHandles = mapOf("target" to HandleRequirement(setOf(TARGET))),
            validateKnownInputs = { inputs -> geometryKeys.firstOrNull { key -> key in inputs && integer(inputs[key]).let { value ->
                value == null || (if (key.startsWith("d")) value !in -MAX_OFFSET..MAX_OFFSET else value !in 1..MAX_OFFSET) } }
                ?.let { ContentIssue(it, "区域偏移需为整数，尺寸与参考尺寸需为正整数") } },
            validateDefinition = { _, _, _, action -> if (geometryKeys.any { it in action.bindings }) "区域几何必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("target") + geometryKeys || inputs.text("target").isNullOrBlank() || geometryKeys.any { integer(inputs[it]) == null })
                "请指定锚点目标、偏移、尺寸及参考截图尺寸" else null
        },
        CapabilityContract(READ_REGION, 1, inputTypes = mapOf("region" to ActionValueType.STRING),
            outputTypes = mapOf("observation" to ActionValueType.STRING), role = ActionRole.OBSERVATION,
            outputHandles = mapOf("observation" to OBSERVATION), inputHandles = regionHandle) { inputs ->
            if (inputs.keys != setOf("region") || inputs.text("region").isNullOrBlank()) "请指定区域" else null
        },
        CapabilityContract(MATCH, 1, inputTypes = mapOf("region" to ActionValueType.STRING, "template" to ActionValueType.STRING,
            "minimumScore" to ActionValueType.NUMBER), outputTypes = mapOf("matched" to ActionValueType.BOOLEAN, "score" to ActionValueType.NUMBER),
            inputHandles = regionHandle,
            validateKnownInputs = { inputs -> when {
                "template" in inputs && inputs.text("template").isNullOrBlank() -> ContentIssue("template", "模板资源不能为空")
                "minimumScore" in inputs && unit(inputs["minimumScore"]) == null -> ContentIssue("minimumScore", "匹配得分需为零到一之间的数值")
                else -> null } },
            validateDefinition = { bundle, _, _, action -> when {
                "template" in action.bindings -> "模板必须由任务版本明确指定"
                bundle.resources.none { it.id == action.inputs.text("template") } -> "图像匹配引用了未声明的模板资源"
                else -> null } }) { inputs ->
            if (inputs.keys != setOf("region", "template", "minimumScore") || inputs.text("template").isNullOrBlank() || unit(inputs["minimumScore"]) == null)
                "请指定区域、模板与零到一之间的得分" else null
        },
        CapabilityContract(CLASSIFY_REGION, 1, inputTypes = mapOf("region" to ActionValueType.STRING, "states" to ActionValueType.ARRAY),
            outputTypes = mapOf("state" to ActionValueType.STRING), inputHandles = regionHandle,
            validateKnownInputs = { inputs -> if ("states" in inputs && regionStates(inputs) == null)
                ContentIssue("states", "请为每个状态明确提供文字、需出现或需缺失的图像条件，身份唯一") else null },
            validateDefinition = { bundle, _, _, action -> when {
                "states" in action.bindings -> "状态证据必须由任务版本明确指定"
                regionStates(action.inputs)?.flatMap { it.present + it.absent }?.any { c -> bundle.resources.none { it.id == c.template } } == true ->
                    "区域状态引用了未声明的模板资源"
                else -> null } }) { inputs ->
            if (inputs.keys != setOf("region", "states") || inputs.text("region").isNullOrBlank() || regionStates(inputs) == null)
                "请指定区域和完整的状态证据" else null
        },
    )
    /** regionDerived marks candidates read inside a region; such observations are never re-resolved for input. */
    private data class Observation(val bridge: ObservationDevice, val result: TextReadResult.Observed, val models: OcrModelReferences,
        val regionDerived: Boolean = false)
    private data class Target(val observation: Observation, val text: String, val minimum: BigDecimal, val match: RecognizedText)
    private data class Region(val observation: Observation, val bounds: TextBounds)
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
    private fun inspectReason(value: InspectionResult.Unavailable) =
        readReason(TextReadResult.Unavailable(value.problem, value.detail, value.diagnostics))
    private fun JsonObjectBuilder.region(bounds: TextBounds) = put("region", buildJsonArray { add(bounds.left); add(bounds.top); add(bounds.width); add(bounds.height) })
    private fun RuntimeCommandResult.writeResult(): DeviceActionWriteResult? =
        ((this as? RuntimeCommandResult.Applied)?.reply as? ExecutorReply.DeviceActionRecorded)?.result

    override fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession = object : ActionSession {
        private val observations = mutableMapOf<String, Observation>()
        private val targets = mutableMapOf<String, Target>()
        private val regions = mutableMapOf<String, Region>()
        private fun token() = UUID.randomUUID().toString()
        /** Region requests on the pixels already observed; the frame must still be current. */
        private suspend fun inspect(region: Region, requests: List<InspectionRequest>): Any {
            val observation = region.observation
            observation.bridge.validateObservation(observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            val pixels = observation.result.pixels ?: return ActionResult.Unavailable("本次观察未保留可复查的画面")
            val result = observer().inspect(bundle.version, observation.models, observation.result.stamp, pixels, requests) { permitted(attempt.ticket) }
            currentCoroutineContext().ensureActive()
            if (!permitted(attempt.ticket) || result == InspectionResult.Interrupted) return ActionResult.Interrupted
            if (result is InspectionResult.Unavailable) return ActionResult.Unavailable(inspectReason(result))
            check(result is InspectionResult.Inspected)
            if (result.version != bundle.version || result.stamp !== observation.result.stamp) return ActionResult.Unavailable("区域检查期间目标画面已变化")
            observation.bridge.validateObservation(result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            return result
        }
        private fun digest(id: String) = bundle.resources.single { it.id == id }.sha256
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
                    val profile = bundle.visionProfiles.single { it.id == inputs.text("profile") }
                    val models = OcrModelReferences(profile.recognizer, profile.models)
                    when (val acquired = observe(attempt, bundle, bridge, profile.packageName, models)) {
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
                        val match = uniqueTarget(observation, expected, minimum) ?: return ActionResult.Unavailable("本次画面不能唯一定位目标文字")
                        val token = token(); targets[token] = Target(observation, expected, minimum, match)
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
                TAP, LONG_PRESS, SWIPE -> gesture(action, inputs)
                DERIVE -> derive(inputs)
                READ_REGION -> {
                    val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
                    when (val inspected = inspect(region, listOf(InspectionRequest.Text(region.bounds)))) {
                        is ActionResult -> inspected
                        else -> {
                            val values = (inspected as InspectionResult.Inspected).values.single() as InspectionValue.Text
                            val derived = region.observation.copy(regionDerived = true,
                                result = region.observation.result.copy(candidates = values.candidates, diagnostics = inspected.diagnostics))
                            val token = token(); observations[token] = derived
                            ActionResult.Produced(buildJsonObject { put("observation", token) },
                                buildJsonObject { put("kind", READ_REGION); region(region.bounds); put("candidates", values.candidates.size) })
                        }
                    }
                }
                MATCH -> {
                    val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
                    val template = checkNotNull(inputs.text("template")); val minimum = checkNotNull(unit(inputs["minimumScore"]))
                    when (val inspected = inspect(region, listOf(InspectionRequest.Image(region.bounds, template)))) {
                        is ActionResult -> inspected
                        else -> {
                            val score = ((inspected as InspectionResult.Inspected).values.single() as InspectionValue.Image).bestScore
                            ActionResult.Produced(buildJsonObject { put("matched", BigDecimal.valueOf(score) >= minimum); put("score", score) },
                                buildJsonObject { put("kind", MATCH); region(region.bounds); put("templateSha256", digest(template)); put("score", score); put("minimumScore", minimum) })
                        }
                    }
                }
                CLASSIFY_REGION -> classifyRegion(inputs)
                else -> error("未注册的屏幕动作")
            }
        }
        private suspend fun derive(inputs: JsonObject): ActionResult {
            val target = targets[inputs.text("target")] ?: return ActionResult.Unavailable("本步骤的锚点目标不可用")
            target.observation.bridge.validateObservation(target.observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            val value = { key: String -> checkNotNull(integer(inputs[key])) }
            val geometry = target.observation.result.stamp.geometry
            val sx = geometry.width.toDouble() / value("referenceWidth"); val sy = geometry.height.toDouble() / value("referenceHeight")
            // Offsets are authored on one layout. A different aspect ratio may reflow the UI, so do not stretch.
            if (kotlin.math.abs(sx / sy - 1.0) > 0.01) return ActionResult.Unavailable("当前画面比例与区域参考尺寸不同")
            val anchor = target.match.bounds
            val centre = anchor.left + anchor.width / 2.0; val bottom = (anchor.top + anchor.height).toDouble()
            val bounds = TextBounds(kotlin.math.round(centre + value("dx") * sx).toInt(), kotlin.math.round(bottom + value("dy") * sy).toInt(),
                kotlin.math.round(value("width") * sx).toInt(), kotlin.math.round(value("height") * sy).toInt())
            if (bounds.left < 0 || bounds.top < 0 || bounds.width <= 0 || bounds.height <= 0 ||
                bounds.left.toLong() + bounds.width > geometry.width || bounds.top.toLong() + bounds.height > geometry.height)
                return ActionResult.Unavailable("由锚点推出的区域超出画面")
            val token = token(); regions[token] = Region(target.observation, bounds)
            return ActionResult.Produced(buildJsonObject { put("region", token) }, buildJsonObject { put("kind", DERIVE); region(bounds) })
        }
        private suspend fun classifyRegion(inputs: JsonObject): ActionResult {
            val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
            val states = checkNotNull(regionStates(inputs))
            val templates = states.flatMap { (it.present + it.absent).map(ImageCondition::template) }.distinct()
            val needsText = states.any { it.text.isNotEmpty() }
            val requests = listOfNotNull(InspectionRequest.Text(region.bounds).takeIf { needsText }) +
                templates.map { InspectionRequest.Image(region.bounds, it) }
            val inspected = when (val value = inspect(region, requests)) { is ActionResult -> return value; else -> value as InspectionResult.Inspected }
            val candidates = if (needsText) (inspected.values.first() as InspectionValue.Text).candidates else emptyList()
            val scores = templates.zip(inspected.values.drop(if (needsText) 1 else 0).map { (it as InspectionValue.Image).bestScore }).toMap()
            fun holds(state: RegionState) = state.text.all { c -> candidates.any { it.text == c.text && it.confidence.isFinite() &&
                    BigDecimal.valueOf(it.confidence) >= c.minimum } } &&
                state.present.all { BigDecimal.valueOf(scores.getValue(it.template)) >= it.minimum } &&
                state.absent.all { BigDecimal.valueOf(scores.getValue(it.template)) < it.minimum }
            val matched = states.filter(::holds)
            val trace = buildJsonObject {
                put("kind", CLASSIFY_REGION); region(region.bounds); put("textCandidates", candidates.size)
                put("templates", buildJsonArray { scores.forEach { (id, score) -> add(buildJsonObject { put("sha256", digest(id)); put("score", score) }) } })
                put("matchedStates", buildJsonArray { matched.forEach { add(it.id) } })
            }
            if (matched.size != 1) return ActionResult.Unavailable((if (matched.isEmpty()) "本区域未能确认任何声明状态" else "本区域同时满足多个声明状态") +
                scores.values.joinToString("，", "（图像得分 ", "）") { "%.3f".format(it) }.takeIf { scores.isNotEmpty() }.orEmpty(), trace)
            return ActionResult.Produced(buildJsonObject { put("state", matched.single().id) }, trace)
        }
        override fun verifiedStepCompleted(evidence: String) {
            businessEvidence = StepOutcome.Completed(evidence)
        }
        /** tap / longPress press the target centre; swipe drags from it by a scaled offset in one stroke. */
        private suspend fun gesture(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val target = targets[inputs.text("target")] ?: return ActionResult.Unavailable("本步骤的定位目标不可用")
            if (target.observation.regionDerived) return ActionResult.Unavailable("区域内定位的目标不能直接用于输入")
            val bridge = target.observation.bridge as? InputDevicePort ?: return ActionResult.Unavailable("当前观察来源未提供输入所有权")
            if (journal == null) return ActionResult.Unavailable("未连接持久化执行器")
            // Resolve the semantic target again inside the input action; do not click old coordinates.
            val fresh = when (val acquired = observe(attempt, bundle, bridge, checkNotNull(target.observation.result.stamp.packageName), target.observation.models)) {
                is Acquisition.Problem -> return acquired.result
                is Acquisition.Ready -> acquired.value
            }
            val match = uniqueTarget(fresh, target.text, target.minimum) ?: return ActionResult.Unavailable("输入前不能唯一确认目标文字")
            val bounds = match.bounds
            val start = ScreenPoint(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
            val points = if (action.capability != SWIPE) listOf(start) else {
                val geometry = fresh.result.stamp.geometry
                val sx = geometry.width.toFloat() / checkNotNull(integer(inputs["referenceWidth"]))
                val sy = geometry.height.toFloat() / checkNotNull(integer(inputs["referenceHeight"]))
                if (kotlin.math.abs(sx / sy - 1f) > 0.01f) return ActionResult.Unavailable("当前画面比例与滑动参考尺寸不同")
                val end = ScreenPoint(start.x + checkNotNull(integer(inputs["dx"])) * sx, start.y + checkNotNull(integer(inputs["dy"])) * sy)
                if (end.x < 0 || end.y < 0 || end.x >= geometry.width || end.y >= geometry.height) return ActionResult.Unavailable("滑动终点超出画面")
                listOf(start, end)
            }
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
                observations.clear(); targets.clear(); regions.clear()
                val channel = bridge.openInput(attempt.ticket, permitted) ?: return if (!permitted(attempt.ticket)) ActionResult.Interrupted
                    else ActionResult.Unavailable("输入通道当前不可用")
                invocation.channel = channel
                val call = channel.gestureCall(fresh.result.stamp, points, checkNotNull(duration(inputs)))
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
