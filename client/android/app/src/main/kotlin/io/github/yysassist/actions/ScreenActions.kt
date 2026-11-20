package io.github.yysassist.actions

import android.accessibilityservice.GestureDescription
import io.github.yysassist.RuntimeCommandResult
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
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
    /** Posts a text-only local notification; false when notifications are not allowed. */
    private val notifier: ((String, String) -> Boolean)? = null,
    /** Text of every completed observation, for passive tasks; must return at once and is never stored. */
    private val textObserved: ((ExecutionContext, List<String>) -> Unit)? = null,
    /** Which game account the screen showed: its number and nickname; must return at once. */
    private val accountObserved: ((ExecutionContext, String, String) -> Unit)? = null,
) : ActionProvider {
    companion object {
        const val READ = "screen.readText"; const val EXPECT = "screen.expectText"
        const val LOCATE = "screen.locateText"; const val TAP = "screen.tap"
        const val CLASSIFY = "screen.classifyText"
        const val DERIVE = "screen.deriveRegion"; const val READ_REGION = "screen.readRegionText"
        const val MATCH = "screen.matchImage"; const val CLASSIFY_REGION = "screen.classifyRegion"
        const val CLASSIFY_SCREEN = "screen.classifyScreen"; const val EXPECT_SCREEN = "screen.expectScreen"
        const val AWAIT_SCREEN = "screen.awaitScreen"; const val LOCATE_IMAGE_IN = "screen.locateImageIn"; const val LOCATE_TEXT_IN = "screen.locateTextIn"
        const val MATCH_COLOR_IN = "screen.matchColorIn"
        const val LONG_PRESS = "screen.longPress"; const val SWIPE = "screen.swipe"; const val BACK = "screen.back"
        const val TAP_REGION = "screen.tapRegion"; const val LOCATE_IMAGE = "screen.locateImage"
        const val READ_NUMBER = "screen.readNumber"; const val READ_FRACTION = "screen.readFraction"
        const val SELECT_TEXT = "screen.selectText"; const val SELECT_IMAGE = "screen.selectImage"
        const val MATCH_COLOR = "screen.matchColor"; const val COMPARE = "screen.compareFrames"
        const val LAUNCH = "app.launch"; const val AWAIT_FOREGROUND = "app.awaitForeground"; const val NOTIFY = "notify.local"
        const val ACCOUNT_REPORT = "account.report"
        private const val MAX_FOREGROUND_WAIT_MS = 120_000L; private const val FOREGROUND_POLL_MS = 500L
        /** The platform refuses accessibility screenshots taken closer together than about a third of a second. */
        private const val MIN_POLL_MS = 350
        /** Pixels of surroundings recognized with a blank-area check. */
        private const val BLANK_CONTEXT = 80
        private val ORDERS = setOf("top", "bottom", "left", "right")
        /** Handle kinds. Region-derived observations keep REGION in their lineage. */
        const val OBSERVATION = "screen.observation"; const val TARGET = "screen.target"; const val REGION = "screen.region"
        private const val MAX_STATES = 8; private const val MAX_TEMPLATES = 16; private const val MAX_OFFSET = 100_000
        private const val RETAINED_FRAMES = 2
        private const val TRIGGER_CONFIDENCE = 0.6
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
    /** Full-width digits and slash folded, spaces and thousands separators removed. No units such as 万. */
    private fun normalizedNumber(text: String) = buildString {
        text.forEach { c -> when (c) { in '０'..'９' -> append('0' + (c - '０')); '／' -> append('/'); ' ', '　', ',', '，' -> Unit; else -> append(c) } }
    }
    private val integerPattern = Regex("\\d{1,9}"); private val fractionPattern = Regex("(\\d{1,9})/(\\d{1,9})")
    private fun rangeIssue(inputs: JsonObject): ContentIssue? {
        val low = integer(inputs["minimum"]); val high = integer(inputs["maximum"])
        return when {
            "minimum" in inputs && (low == null || low < 0) -> ContentIssue("minimum", "下限需为非负整数")
            "maximum" in inputs && (high == null || high < 0) -> ContentIssue("maximum", "上限需为非负整数")
            low != null && high != null && low > high -> ContentIssue("maximum", "上限不能小于下限")
            else -> null
        }
    }
    private fun rgb(value: JsonElement?): IntArray? = (value as? JsonArray)?.takeIf { it.size == 3 }?.map { integer(it)?.takeIf { v -> v in 0..255 } ?: return null }?.toIntArray()
    /** Explicit, reproducible path variation. Every field is authored; only seed may be left to the App. */
    private data class Variation(val startJitter: Double, val curve: Double, val durationJitter: Double, val points: Int, val seed: Long?)
    private fun variation(value: JsonElement?): Variation? {
        val o = value as? JsonObject ?: return null
        if (!o.keys.containsAll(setOf("startJitter", "curve", "durationJitter", "points")) ||
            !setOf("startJitter", "curve", "durationJitter", "points", "seed").containsAll(o.keys)) return null
        fun fraction(key: String, max: Double) = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.valueOf(max) }?.toDouble()
        val seed = if ("seed" in o) try { (o["seed"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact() ?: return null }
            catch (_: ArithmeticException) { return null } else null
        return Variation(fraction("startJitter", 1.0) ?: return null, fraction("curve", 0.5) ?: return null,
            fraction("durationJitter", 0.5) ?: return null, integer(o["points"])?.takeIf { it in 2..32 } ?: return null, seed)
    }
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
        // Optional "absent": texts that must not be recognized (at their own confidence) for the state to hold.
        fun absent(state: JsonObject): Boolean = state["absent"]?.let { value -> (value as? JsonArray)?.let { list ->
            list.isNotEmpty() && list.all { (it as? JsonObject)?.let { c -> c.keys == setOf("text", "minimumConfidence") &&
                !c.text("text").isNullOrBlank() && threshold(c) != null } == true } } == true } ?: true
        return definitions.all { state -> (state.keys == setOf("id", "text", "minimumConfidence") || state.keys == setOf("id", "text", "minimumConfidence", "absent")) &&
            !state.text("id").isNullOrBlank() && !state.text("text").isNullOrBlank() && threshold(state) != null && absent(state) } &&
            definitions.map { it.text("id") }.distinct().size == definitions.size
    }
    /** Parsed classifyRegion states, or null when any part is not explicit. */
    private data class TextCondition(val text: String, val minimum: BigDecimal, val within: List<Int>? = null)
    private data class ImageCondition(val template: String, val minimum: BigDecimal, val within: List<Int>? = null)
    /** x, y, width, height on the task's reference frame. */
    private fun rectangle(value: JsonElement?): List<Int>? = (value as? JsonArray)?.takeIf { it.size == 4 }?.map { integer(it) ?: return null }
        ?.takeIf { (x, y, w, h) -> x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= MAX_OFFSET && y.toLong() + h <= MAX_OFFSET }
    private fun scaled(rect: List<Int>, geometry: ScreenGeometry, referenceWidth: Int, referenceHeight: Int): TextBounds? {
        val sx = geometry.width.toDouble() / referenceWidth; val sy = geometry.height.toDouble() / referenceHeight
        if (kotlin.math.abs(sx / sy - 1.0) > 0.01) return null
        val bounds = TextBounds(kotlin.math.round(rect[0] * sx).toInt(), kotlin.math.round(rect[1] * sy).toInt(),
            kotlin.math.round(rect[2] * sx).toInt(), kotlin.math.round(rect[3] * sy).toInt())
        return bounds.takeIf { it.width > 0 && it.height > 0 && it.left.toLong() + it.width <= geometry.width && it.top.toLong() + it.height <= geometry.height }
    }
    private fun reference(inputs: JsonObject): Pair<Int, Int>? {
        val width = integer(inputs["referenceWidth"])?.takeIf { it in 1..MAX_OFFSET } ?: return null
        return (integer(inputs["referenceHeight"])?.takeIf { it in 1..MAX_OFFSET } ?: return null).let { width to it }
    }
    private data class RegionState(val id: String, val text: List<TextCondition>, val present: List<ImageCondition>, val absent: List<ImageCondition>,
        val absentText: List<TextCondition>)
    /** Whole-screen states may repeat an id: any entry proves it, as when one page has several looks. */
    private fun regionStates(inputs: JsonObject, repeatable: Boolean = false, windowed: Boolean = false): List<RegionState>? {
        val states = inputs["states"] as? JsonArray ?: return null
        if (states.isEmpty() || states.size > MAX_STATES) return null
        fun list(value: JsonElement?, parse: (JsonObject) -> Any?): List<Any>? =
            if (value == null) emptyList() else (value as? JsonArray)?.map { (it as? JsonObject)?.let(parse) ?: return null }
        val parsed = states.map { element ->
            val state = element as? JsonObject ?: return null
            if (!setOf("id", "text", "present", "absent", "absentText").containsAll(state.keys)) return null
            // Polling must stay cheap, so a windowed state names where every condition is looked for.
            fun within(c: JsonObject): List<Int>? = rectangle(c["within"])
            fun words(key: String) = list(state[key]) { c -> if (c.keys != setOf("text", "minimumConfidence") + (if (windowed) setOf("within") else emptySet()) ||
                c.text("text").isNullOrBlank() || windowed && within(c) == null) null
                else threshold(c)?.let { TextCondition(c.text("text")!!, it, within(c)) } }
            val text = words("text") ?: return null; val absentText = words("absentText") ?: return null
            fun image(key: String) = list(state[key]) { c -> if (c.keys != setOf("template", "minimumScore") + (if (windowed) setOf("within") else emptySet()) ||
                c.text("template").isNullOrBlank() || windowed && within(c) == null) null
                else unit(c["minimumScore"])?.let { ImageCondition(c.text("template")!!, it, within(c)) } }
            val present = image("present") ?: return null; val absent = image("absent") ?: return null
            val id = state.text("id")?.takeIf { it.isNotBlank() } ?: return null
            if (text.isEmpty() && present.isEmpty() && absent.isEmpty() && absentText.isEmpty()) return null
            @Suppress("UNCHECKED_CAST") RegionState(id, text as List<TextCondition>, present as List<ImageCondition>, absent as List<ImageCondition>, absentText as List<TextCondition>)
        }
        if (!repeatable && parsed.map { it.id }.distinct().size != parsed.size) return null
        if (parsed.flatMap { (it.present + it.absent).map(ImageCondition::template) }.distinct().size > MAX_TEMPLATES) return null
        return parsed
    }
    private val awaitKeys = setOf("profile", "states", "timeoutMs", "intervalMs", "stableMs", "referenceWidth", "referenceHeight")
    private fun awaitTiming(inputs: JsonObject): Triple<Long, Long, Long>? {
        val timeout = foregroundWait(inputs) ?: return null
        val interval = integer(inputs["intervalMs"])?.takeIf { it in MIN_POLL_MS..2000 } ?: return null
        val stable = integer(inputs["stableMs"])?.takeIf { it in 0..5000 } ?: return null
        return Triple(timeout, interval.toLong(), stable.toLong())
    }
    private fun locateInIssue(inputs: JsonObject, name: String, valid: Boolean): String? =
        if (inputs.text("observation").isNullOrBlank() || !valid || rectangle(inputs["within"]) == null || reference(inputs) == null) "请指定观察、$name、查找区域及参考截图尺寸" else null
    private val wholeObservation = mapOf("observation" to HandleRequirement(setOf(OBSERVATION), forbiddenAncestors = setOf(REGION)))
    private fun screenStatesIssue(bundle: TaskBundle, action: ActionDefinition): String? = when {
        "states" in action.bindings || "otherwise" in action.bindings -> "状态证据必须由任务版本明确指定"
        regionStates(action.inputs, repeatable = true)?.flatMap { it.present + it.absent }?.any { c -> bundle.resources.none { it.id == c.template } } == true ->
            "页面状态引用了未声明的模板资源"
        else -> null
    }
    /** The state reported when nothing matches; it must not be mistaken for a declared state. */
    private fun otherwiseValid(inputs: JsonObject): Boolean = inputs.text("otherwise").let { value ->
        !value.isNullOrBlank() && regionStates(inputs, repeatable = true)?.none { it.id == value } != false }
    private fun foregroundWait(inputs: JsonObject): Long? = try {
        (inputs["timeoutMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it in 1..MAX_FOREGROUND_WAIT_MS }
    } catch (_: ArithmeticException) { null }
    private fun selectIssue(inputs: JsonObject): ContentIssue? = when {
        "order" in inputs && inputs.text("order") !in ORDERS -> ContentIssue("order", "排序需为 top、bottom、left 或 right")
        "index" in inputs && integer(inputs["index"])?.takeIf { it in 0..49 } == null -> ContentIssue("index", "序号需为 0–49 的整数")
        else -> null
    }
    private fun colorIssue(inputs: JsonObject): ContentIssue? {
        val lower = rgb(inputs["lower"]); val upper = rgb(inputs["upper"])
        return when {
            "lower" in inputs && lower == null -> ContentIssue("lower", "颜色下限需为三个 0–255 的整数")
            "upper" in inputs && upper == null -> ContentIssue("upper", "颜色上限需为三个 0–255 的整数")
            lower != null && upper != null && (0..2).any { lower[it] > upper[it] } -> ContentIssue("upper", "颜色上限不能小于下限")
            "minimumRatio" in inputs && unit(inputs["minimumRatio"]) == null -> ContentIssue("minimumRatio", "比例需为零到一之间的数值")
            else -> null
        }
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
        CapabilityContract(SWIPE, 1, inputTypes = mapOf("target" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER,
            "variation" to ActionValueType.OBJECT) + swipeKeys.associateWith { ActionValueType.NUMBER },
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            inputHandles = mapOf("target" to HandleRequirement(setOf(TARGET), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> when {
                "durationMs" in inputs && duration(inputs) == null -> ContentIssue("durationMs", "滑动时长需为平台允许的正整数毫秒")
                "variation" in inputs && variation(inputs["variation"]) == null -> ContentIssue("variation",
                    "变化参数需明确给出 startJitter(0–1)、curve(0–0.5)、durationJitter(0–0.5)、points(2–32)，seed 可选且为整数")
                else -> swipeKeys.firstOrNull { key -> key in inputs && integer(inputs[key]).let { value ->
                    value == null || (if (key.startsWith("d")) value !in -MAX_OFFSET..MAX_OFFSET else value !in 1..MAX_OFFSET) } }
                    ?.let { ContentIssue(it, "滑动位移需为整数，参考尺寸需为正整数") }
                    ?: if (integer(inputs["dx"]) == 0 && integer(inputs["dy"]) == 0) ContentIssue("dx", "滑动位移不能为零") else null
            } },
            validateDefinition = { _, _, _, action -> if ((swipeKeys + "variation").any { it in action.bindings }) "滑动位移与变化参数必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("target", "durationMs", "variation") + swipeKeys || inputs.text("target").isNullOrBlank() || duration(inputs) == null ||
                swipeKeys.any { integer(inputs[it]) == null } || variation(inputs["variation"]) == null) "请明确指定起点目标、位移、参考尺寸、滑动时长及变化参数" else null
        },
        // The observation proves which app the key is for; the key is sent only after a fresh capture
        // confirms that app is still in front. Back carries no target and no coordinates.
        CapabilityContract(BACK, 1, inputTypes = mapOf("observation" to ActionValueType.STRING),
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true, inputHandles = observationHandle) { inputs ->
            if (inputs.keys != setOf("observation") || inputs.text("observation").isNullOrBlank()) "请指定观察" else null
        },
        // Taps the centre of an anchor-derived region (e.g. blank space outside a dialog). The anchor
        // and offsets are replayed on a fresh frame; requireNoText refuses a region that shows any text.
        CapabilityContract(TAP_REGION, 1, inputTypes = mapOf("region" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER,
            "requireNoText" to ActionValueType.BOOLEAN), outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
            inputHandles = mapOf("region" to HandleRequirement(setOf(REGION), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> if ("durationMs" in inputs && duration(inputs) == null)
                ContentIssue("durationMs", "手势时长需为平台允许的正整数毫秒") else null },
            validateDefinition = { _, _, _, action -> if ("requireNoText" in action.bindings) "空白检查必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("region", "durationMs", "requireNoText") || inputs.text("region").isNullOrBlank() || duration(inputs) == null ||
                (inputs["requireNoText"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) "请指定区域、手势时长及是否要求区域无文字" else null
        },
        // A clickable target found by a unique template match in the whole frame (icons without text).
        // Inputs re-run the same match on a fresh frame; a region-derived observation is not accepted.
        CapabilityContract(LOCATE_IMAGE, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "template" to ActionValueType.STRING,
            "minimumScore" to ActionValueType.NUMBER), outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to TARGET),
            inputHandles = mapOf("observation" to HandleRequirement(setOf(OBSERVATION), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> when {
                "template" in inputs && inputs.text("template").isNullOrBlank() -> ContentIssue("template", "模板资源不能为空")
                "minimumScore" in inputs && unit(inputs["minimumScore"]) == null -> ContentIssue("minimumScore", "匹配得分需为零到一之间的数值")
                else -> null } },
            validateDefinition = { bundle, _, _, action -> when {
                "template" in action.bindings -> "模板必须由任务版本明确指定"
                bundle.resources.none { it.id == action.inputs.text("template") } -> "图像定位引用了未声明的模板资源"
                else -> null } }) { inputs ->
            if (inputs.keys != setOf("observation", "template", "minimumScore") || inputs.text("template").isNullOrBlank() || unit(inputs["minimumScore"]) == null)
                "请指定观察、模板与零到一之间的得分" else null
        },
        // Numbers are read from one observation (usually a region read). Several different numbers are
        // ambiguous, values outside the authored range are refused; nothing is guessed or clamped.
        CapabilityContract(READ_NUMBER, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER,
            "minimum" to ActionValueType.NUMBER, "maximum" to ActionValueType.NUMBER), outputTypes = mapOf("value" to ActionValueType.NUMBER),
            inputHandles = observationHandle,
            validateKnownInputs = { inputs -> if ("minimumConfidence" in inputs && threshold(inputs) == null) ContentIssue("minimumConfidence", "置信度需为零到一之间的数值") else rangeIssue(inputs) }) { inputs ->
            if (inputs.keys != setOf("observation", "minimumConfidence", "minimum", "maximum") || threshold(inputs) == null ||
                integer(inputs["minimum"]) == null || integer(inputs["maximum"]) == null) "请指定观察、置信度和数值范围" else null
        },
        CapabilityContract(READ_FRACTION, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER,
            "minimum" to ActionValueType.NUMBER, "maximum" to ActionValueType.NUMBER),
            outputTypes = mapOf("value" to ActionValueType.NUMBER, "total" to ActionValueType.NUMBER), inputHandles = observationHandle,
            validateKnownInputs = { inputs -> if ("minimumConfidence" in inputs && threshold(inputs) == null) ContentIssue("minimumConfidence", "置信度需为零到一之间的数值") else rangeIssue(inputs) }) { inputs ->
            if (inputs.keys != setOf("observation", "minimumConfidence", "minimum", "maximum") || threshold(inputs) == null ||
                integer(inputs["minimum"]) == null || integer(inputs["maximum"]) == null) "请指定观察、置信度和数值范围" else null
        },
        // One of several matches by an explicit order and 0-based index (e.g. the second "进攻" from the top).
        CapabilityContract(SELECT_TEXT, 1, inputTypes = textTypes + mapOf("order" to ActionValueType.STRING, "index" to ActionValueType.NUMBER),
            outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to TARGET), inputHandles = observationHandle,
            validateKnownInputs = { inputs -> knownText(inputs) ?: selectIssue(inputs) }) { inputs ->
            if (inputs.keys != textTypes.keys + setOf("order", "index") || inputs.text("text").isNullOrBlank() || threshold(inputs) == null || selectIssue(inputs) != null ||
                "order" !in inputs || "index" !in inputs) "请指定观察、文字、置信度、排序与序号" else null
        },
        CapabilityContract(SELECT_IMAGE, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "template" to ActionValueType.STRING,
            "minimumScore" to ActionValueType.NUMBER, "order" to ActionValueType.STRING, "index" to ActionValueType.NUMBER),
            outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to TARGET),
            inputHandles = mapOf("observation" to HandleRequirement(setOf(OBSERVATION), forbiddenAncestors = setOf(REGION))),
            validateKnownInputs = { inputs -> when {
                "minimumScore" in inputs && unit(inputs["minimumScore"]) == null -> ContentIssue("minimumScore", "匹配得分需为零到一之间的数值")
                else -> selectIssue(inputs) } },
            validateDefinition = { bundle, _, _, action -> when {
                "template" in action.bindings -> "模板必须由任务版本明确指定"
                bundle.resources.none { it.id == action.inputs.text("template") } -> "图像选择引用了未声明的模板资源"
                else -> null } }) { inputs ->
            if (inputs.keys != setOf("observation", "template", "minimumScore", "order", "index") || unit(inputs["minimumScore"]) == null ||
                selectIssue(inputs) != null || "order" !in inputs || "index" !in inputs) "请指定观察、模板、得分、排序与序号" else null
        },
        // Share of region pixels inside an inclusive RGB box, from the observed pixels (no recognizer).
        // Whether a fixed spot shows a colour, such as a notification dot. Host-side pixel maths, so it costs almost nothing.
        CapabilityContract(MATCH_COLOR_IN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "within" to ActionValueType.ARRAY, "lower" to ActionValueType.ARRAY,
            "upper" to ActionValueType.ARRAY, "minimumRatio" to ActionValueType.NUMBER, "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER),
            outputTypes = mapOf("matched" to ActionValueType.BOOLEAN, "ratio" to ActionValueType.NUMBER), inputHandles = wholeObservation,
            validateKnownInputs = { inputs -> colorIssue(inputs) },
            validateDefinition = { _, _, _, action -> if (setOf("lower", "upper", "within").any { it in action.bindings }) "颜色范围和查找区域必须由任务版本明确指定" else null }) { inputs ->
            locateInIssue(inputs, "颜色上下限和比例", colorIssue(inputs) == null && rgb(inputs["lower"]) != null && rgb(inputs["upper"]) != null && unit(inputs["minimumRatio"]) != null)
        },
        CapabilityContract(MATCH_COLOR, 1, inputTypes = mapOf("region" to ActionValueType.STRING, "lower" to ActionValueType.ARRAY,
            "upper" to ActionValueType.ARRAY, "minimumRatio" to ActionValueType.NUMBER),
            outputTypes = mapOf("matched" to ActionValueType.BOOLEAN, "ratio" to ActionValueType.NUMBER), inputHandles = regionHandle,
            validateKnownInputs = { inputs -> colorIssue(inputs) },
            validateDefinition = { _, _, _, action -> if (setOf("lower", "upper").any { it in action.bindings }) "颜色范围必须由任务版本明确指定" else null }) { inputs ->
            if (inputs.keys != setOf("region", "lower", "upper", "minimumRatio") || colorIssue(inputs) != null || rgb(inputs["lower"]) == null ||
                rgb(inputs["upper"]) == null || unit(inputs["minimumRatio"]) == null) "请指定区域、颜色上下限和比例" else null
        },
        // Mean absolute difference inside a region between an earlier observation (allowed from before an
        // input) and the region's own observation. Read-only: it never feeds an input.
        CapabilityContract(COMPARE, 1, inputTypes = mapOf("before" to ActionValueType.STRING, "region" to ActionValueType.STRING,
            "minimumDifference" to ActionValueType.NUMBER), outputTypes = mapOf("changed" to ActionValueType.BOOLEAN, "difference" to ActionValueType.NUMBER),
            inputHandles = mapOf("before" to HandleRequirement(setOf(OBSERVATION), allowStale = true), "region" to HandleRequirement(setOf(REGION))),
            validateKnownInputs = { inputs -> if ("minimumDifference" in inputs && unit(inputs["minimumDifference"]) == null)
                ContentIssue("minimumDifference", "差异阈值需为零到一之间的数值") else null }) { inputs ->
            if (inputs.keys != setOf("before", "region", "minimumDifference") || unit(inputs["minimumDifference"]) == null) "请指定之前的观察、区域和差异阈值" else null
        },
        // Requests the game named by the version's vision profile. A request is not arrival: the platform can
        // silently block background starts, so a later observation has to prove what is on screen.
        CapabilityContract(LAUNCH, 1, inputTypes = mapOf("profile" to ActionValueType.STRING), outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN),
            sendsDeviceInput = true, startsTarget = true,
            validateDefinition = { bundle, task, _, action -> profileIssue(bundle, task, action) }) { inputs ->
            if (inputs.keys != setOf("profile") || inputs.text("profile").isNullOrBlank()) "请指定视觉配置" else null
        },
        CapabilityContract(AWAIT_FOREGROUND, 1, inputTypes = mapOf("profile" to ActionValueType.STRING, "timeoutMs" to ActionValueType.NUMBER),
            outputTypes = mapOf("foreground" to ActionValueType.BOOLEAN),
            validateKnownInputs = { inputs -> if ("timeoutMs" in inputs && foregroundWait(inputs) == null)
                ContentIssue("timeoutMs", "等待时长需为 1–120000 的整数毫秒") else null },
            validateDefinition = { bundle, task, _, action -> if ("profile" in action.bindings) "视觉配置必须由任务版本明确指定" else
                profileIssue(bundle, task, action.copy(bindings = emptyMap())) }) { inputs ->
            if (inputs.keys != setOf("profile", "timeoutMs") || inputs.text("profile").isNullOrBlank() || foregroundWait(inputs) == null) "请指定视觉配置和等待时长" else null
        },
        // The game account on screen: the number read from the screen and the nickname region's text. The App tells
        // the server, which then knows which saved account this app is signed in to. Nothing else is done with them.
        CapabilityContract(ACCOUNT_REPORT, 1, inputTypes = mapOf("uid" to ActionValueType.NUMBER, "observation" to ActionValueType.STRING),
            outputTypes = mapOf("uid" to ActionValueType.STRING, "name" to ActionValueType.STRING), inputHandles = observationHandle,
            validateDefinition = { _, _, _, action -> if ("uid" !in action.bindings) "编号必须来自本步骤读到的数值" else null }) { inputs ->
            if (inputs.keys != setOf("uid", "observation") || integer(inputs["uid"]) == null || inputs.text("observation").isNullOrBlank()) "请指定读到的编号和昵称区域" else null
        },
        // Text only, never a screenshot. A disallowed notification is reported, not a task failure.
        CapabilityContract(NOTIFY, 1, inputTypes = mapOf("title" to ActionValueType.STRING, "text" to ActionValueType.STRING),
            outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN),
            validateKnownInputs = { inputs -> when {
                "title" in inputs && inputs.text("title").let { it.isNullOrBlank() || it.length > 64 } -> ContentIssue("title", "标题需为 1–64 个字符")
                "text" in inputs && inputs.text("text").let { it.isNullOrBlank() || it.length > 240 } -> ContentIssue("text", "正文需为 1–240 个字符")
                else -> null } }) { inputs ->
            if (inputs.keys != setOf("title", "text") || inputs.text("title").isNullOrBlank() || inputs.text("text").isNullOrBlank()) "请指定通知标题和正文" else null
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
        // Polls fresh frames until one declared page holds, looking only inside each condition's window. Replaces a
        // fixed pause plus a whole-frame read; the frame that proved the page is the output observation.
        CapabilityContract(AWAIT_SCREEN, 1, inputTypes = mapOf("profile" to ActionValueType.STRING, "states" to ActionValueType.ARRAY,
            "timeoutMs" to ActionValueType.NUMBER, "intervalMs" to ActionValueType.NUMBER, "stableMs" to ActionValueType.NUMBER,
            "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER),
            outputTypes = mapOf("state" to ActionValueType.STRING, "observation" to ActionValueType.STRING), role = ActionRole.VERIFICATION,
            outputHandles = mapOf("observation" to OBSERVATION),
            validateDefinition = { bundle, task, _, action -> profileIssue(bundle, task, action) ?:
                regionStates(action.inputs, repeatable = true, windowed = true)?.flatMap { it.present + it.absent }
                    ?.takeIf { used -> used.any { c -> bundle.resources.none { it.id == c.template } } }?.let { "页面状态引用了未声明的模板资源" } }) { inputs ->
            if (inputs.keys != awaitKeys || inputs.text("profile").isNullOrBlank() || regionStates(inputs, repeatable = true, windowed = true) == null ||
                awaitTiming(inputs) == null || reference(inputs) == null) "请指定视觉配置、带查找区域的页面状态、等待时长及参考截图尺寸" else null
        },
        CapabilityContract(LOCATE_IMAGE_IN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "template" to ActionValueType.STRING,
            "minimumScore" to ActionValueType.NUMBER, "within" to ActionValueType.ARRAY, "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER),
            outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to TARGET), inputHandles = wholeObservation,
            validateDefinition = { bundle, _, _, action -> if (bundle.resources.none { it.id == action.inputs.text("template") }) "图像定位引用了未声明的模板资源" else null }) { inputs ->
            locateInIssue(inputs, "模板和得分", !inputs.text("template").isNullOrBlank() && unit(inputs["minimumScore"]) != null)
        },
        CapabilityContract(LOCATE_TEXT_IN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING,
            "minimumConfidence" to ActionValueType.NUMBER, "within" to ActionValueType.ARRAY, "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER),
            outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to TARGET), inputHandles = wholeObservation) { inputs ->
            locateInIssue(inputs, "文字和置信度", !inputs.text("text").isNullOrBlank() && threshold(inputs) != null)
        },
        // Which page is on screen, from recognized text and whole-frame templates. An unrecognized page reports
        // "otherwise": a branch can wait on it (loading screens), and a branch with no such case stops as unknown.
        CapabilityContract(CLASSIFY_SCREEN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "states" to ActionValueType.ARRAY,
            "otherwise" to ActionValueType.STRING), outputTypes = mapOf("state" to ActionValueType.STRING), inputHandles = wholeObservation,
            validateKnownInputs = { inputs -> when {
                "states" in inputs && regionStates(inputs, repeatable = true) == null -> ContentIssue("states", "请为每个状态明确提供文字、需出现或需缺失的图像条件")
                "otherwise" in inputs && !otherwiseValid(inputs) -> ContentIssue("otherwise", "兜底状态不能为空，也不能与声明状态相同")
                else -> null } },
            validateDefinition = { bundle, _, _, action -> screenStatesIssue(bundle, action) }) { inputs ->
            if (inputs.keys != setOf("observation", "states", "otherwise") || inputs.text("observation").isNullOrBlank() ||
                regionStates(inputs, repeatable = true) == null || !otherwiseValid(inputs)) "请指定观察和完整的页面状态" else null
        },
        // Proof that the frame shows one of the listed pages, for steps that end on a page without stable text.
        CapabilityContract(EXPECT_SCREEN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "states" to ActionValueType.ARRAY),
            outputTypes = mapOf("state" to ActionValueType.STRING), role = ActionRole.VERIFICATION, inputHandles = wholeObservation,
            validateKnownInputs = { inputs -> if ("states" in inputs && regionStates(inputs, repeatable = true) == null)
                ContentIssue("states", "请为每个状态明确提供文字、需出现或需缺失的图像条件") else null },
            validateDefinition = { bundle, _, _, action -> screenStatesIssue(bundle, action) }) { inputs ->
            if (inputs.keys != setOf("observation", "states") || inputs.text("observation").isNullOrBlank() ||
                regionStates(inputs, repeatable = true) == null) "请指定观察和完整的页面状态" else null
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
    /** A text target, or an image target when [template] is set (then [text] is empty and [minimum] is a score). */
    private data class Target(val observation: Observation, val text: String, val minimum: BigDecimal, val match: RecognizedText,
        val template: String? = null,
        /** Selected among several matches: re-resolve to the nearest one that has barely moved, not a unique one. */
        val nearest: Boolean = false)
    /** Offsets from an anchor, kept so an input can re-derive the region on a fresh frame. */
    private data class Derivation(val anchor: Target, val dx: Int, val dy: Int, val width: Int, val height: Int, val referenceWidth: Int, val referenceHeight: Int)
    private data class Region(val observation: Observation, val bounds: TextBounds, val derivation: Derivation)
    private data class Quad(val matches: List<RecognizedText>, val minimum: BigDecimal, val template: String?, val text: String)
    /** Same geometry for derive and for input-time re-derivation. Null when outside or reflowed. */
    private fun regionBounds(match: RecognizedText, geometry: ScreenGeometry, d: Derivation): TextBounds? {
        val sx = geometry.width.toDouble() / d.referenceWidth; val sy = geometry.height.toDouble() / d.referenceHeight
        if (kotlin.math.abs(sx / sy - 1.0) > 0.01) return null
        val anchor = match.bounds
        val centre = anchor.left + anchor.width / 2.0; val bottom = (anchor.top + anchor.height).toDouble()
        val bounds = TextBounds(kotlin.math.round(centre + d.dx * sx).toInt(), kotlin.math.round(bottom + d.dy * sy).toInt(),
            kotlin.math.round(d.width * sx).toInt(), kotlin.math.round(d.height * sy).toInt())
        return bounds.takeIf { it.left >= 0 && it.top >= 0 && it.width > 0 && it.height > 0 &&
            it.left.toLong() + it.width <= geometry.width && it.top.toLong() + it.height <= geometry.height }
    }
    private class Invocation(val intent: DeviceActionIntent) {
        var admission: DeviceActionWriteResult? = null
        var channel: DeviceInputChannel? = null
        var call: InputGestureCall? = null
        var settlement: InputStopObservation? = null
        /** Persisted with the receipt so a varied gesture can be replayed exactly (e.g. the swipe seed). */
        var detail: String? = null
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
    /**
     * A fresh frame for an input, without whole-frame recognition: the target is then re-resolved only in
     * a window around its previous position. Same admission, foreground and frame-validity checks.
     */
    private suspend fun snapshot(attempt: StepAttempt, bundle: TaskBundle, bridge: ObservationDevice, packageName: String, models: OcrModelReferences): Acquisition {
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
            val pixels = try { observer().pixelsOf(frame) } catch (_: Exception) { return Acquisition.Problem(ActionResult.Unavailable("截图编码失败")) }
            bridge.validateObservation(frame.stamp)?.let { return Acquisition.Problem(ActionResult.Unavailable(deviceReason(it))) }
            return Acquisition.Ready(Observation(bridge, TextReadResult.Observed(frame.stamp, bundle.version, "", emptyList(), pixels = pixels), models))
        }
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
            textObserved?.let { listener -> runCatching { listener(attempt.run.request.context,
                result.candidates.filter { it.confidence.isFinite() && it.confidence >= TRIGGER_CONFIDENCE }.map { it.text }) } }
            return Acquisition.Ready(Observation(bridge, result, models))
        }
    }
    /** Window around a previous target where an input re-resolves it on a fresh frame. */
    private fun window(bounds: TextBounds, geometry: ScreenGeometry): TextBounds {
        val margin = maxOf(bounds.width, bounds.height) + 160
        val left = (bounds.left - margin).coerceAtLeast(0); val top = (bounds.top - margin).coerceAtLeast(0)
        val right = (bounds.left + bounds.width + margin).coerceAtMost(geometry.width)
        val bottom = (bounds.top + bounds.height + margin).coerceAtMost(geometry.height)
        return TextBounds(left, top, right - left, bottom - top)
    }
    private fun uniqueTarget(observation: Observation, text: String, minimum: BigDecimal): RecognizedText? {
        val matches = observation.result.candidates.filter { it.text == text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }
        val match = matches.singleOrNull() ?: return null
        val bounds = match.bounds; val geometry = observation.result.stamp.geometry
        return match.takeIf { bounds.left >= 0 && bounds.top >= 0 && bounds.width > 0 && bounds.height > 0 &&
            bounds.left.toLong() + bounds.width <= geometry.width && bounds.top.toLong() + bounds.height <= geometry.height }
    }
    /**
     * Start jitters inside the target box, the end keeps the authored offset from that start, and the
     * path bends along a quadratic curve whose side and depth come from the seed. Same seed, same path.
     */
    private fun swipePath(centre: ScreenPoint, target: TextBounds, dx: Float, dy: Float, shape: Variation, seed: Long): List<ScreenPoint> {
        val random = java.util.Random(seed)
        fun spread() = (random.nextDouble() * 2 - 1).toFloat()
        val start = ScreenPoint(centre.x + spread() * shape.startJitter.toFloat() * target.width / 2f,
            centre.y + spread() * shape.startJitter.toFloat() * target.height / 2f)
        val end = ScreenPoint(start.x + dx, start.y + dy)
        val length = kotlin.math.hypot(dx, dy)
        val bend = spread() * shape.curve.toFloat() * length
        val control = ScreenPoint((start.x + end.x) / 2f - dy / length * bend, (start.y + end.y) / 2f + dx / length * bend)
        return (0 until shape.points).map { index ->
            val t = index.toFloat() / (shape.points - 1); val u = 1 - t
            ScreenPoint(u * u * start.x + 2 * u * t * control.x + t * t * end.x, u * u * start.y + 2 * u * t * control.y + t * t * end.y)
        }
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
        /**
         * Pixels of stored observations, by frame, kept for at most [RETAINED_FRAMES] frames (about 10MB each
         * as raw RGBA). Observations themselves keep only recognized text, so targets and regions do not pin
         * pixels; an evicted frame makes later region requests on it unavailable instead of using a newer one.
         */
        private val frames = LinkedHashMap<FrameStamp, EncodedPixels>()
        /** Frames from just before the latest input, kept only for read-only before/after comparison. */
        private val previousFrames = LinkedHashMap<FrameStamp, EncodedPixels>()
        private val beforeObservations = mutableMapOf<String, Observation>()
        private fun pixelsFor(observation: Observation) = frames[observation.result.stamp] ?: previousFrames[observation.result.stamp] ?: observation.result.pixels
        /** Raw RGBA for host-side pixel maths; PNG pixels (test backends) are decoded once. */
        private fun rgba(pixels: EncodedPixels): EncodedPixels = if (pixels.raw) pixels else
            android.graphics.BitmapFactory.decodeByteArray(pixels.bytes, 0, pixels.bytes.size).let { bitmap ->
                try { EncodedPixels.raw(bitmap) } finally { bitmap.recycle() } }
        private fun store(observation: Observation): Observation {
            observation.result.pixels?.let { pixels ->
                frames.remove(observation.result.stamp); frames[observation.result.stamp] = pixels
                while (frames.size > RETAINED_FRAMES) frames.remove(frames.keys.first())
            }
            return observation.copy(result = observation.result.copy(pixels = null))
        }
        private fun token() = UUID.randomUUID().toString()
        /** Region requests on the pixels already observed; the frame must still be current. */
        private suspend fun inspect(region: Region, requests: List<InspectionRequest>): Any = inspectOn(region.observation, requests)
        /** Requests on an observation's own pixels; the frame must still be current. */
        private suspend fun inspectOn(observation: Observation, requests: List<InspectionRequest>): Any {
            observation.bridge.validateObservation(observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            val pixels = frames[observation.result.stamp] ?: observation.result.pixels
                ?: return ActionResult.Unavailable("本次观察的画面像素已释放，需重新观察")
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
        private fun whole(observation: Observation) = observation.result.stamp.geometry.let { TextBounds(0, 0, it.width, it.height) }
        /** Exactly one template match at or above [minimum] in the whole frame, else the reason. */
        private suspend fun uniqueImage(observation: Observation, template: String, minimum: BigDecimal): Any {
            val inspected = when (val value = inspectOn(observation, listOf(InspectionRequest.Image(whole(observation), template)))) {
                is ActionResult -> return value
                else -> value as InspectionResult.Inspected
            }
            val accepted = (inspected.values.single() as InspectionValue.Image).matches.filter { BigDecimal.valueOf(it.score) >= minimum }
            return accepted.singleOrNull()?.let { RecognizedText("", it.score, it.bounds) }
                ?: ActionResult.Unavailable(if (accepted.isEmpty()) "本次画面中没有达到得分的图像目标" else "本次画面中有 ${accepted.size} 处图像达到得分，不能唯一定位")
        }
        /**
         * Re-resolves a target on a fresh snapshot inside a window around its previous position: text by
         * exact text, image by template. Exactly one match in the window is required; whole-frame
         * uniqueness was established when the target was located in this input phase.
         */
        private suspend fun resolve(target: Target, fresh: Observation): Any {
            val area = window(target.match.bounds, fresh.result.stamp.geometry)
            val request = if (target.template == null) InspectionRequest.Text(area) else InspectionRequest.Image(area, target.template)
            val inspected = when (val value = inspectOn(fresh, listOf(request))) { is ActionResult -> return value; else -> value as InspectionResult.Inspected }
            val accepted = when (val result = inspected.values.single()) {
                is InspectionValue.Text -> result.candidates.filter { it.text == target.text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= target.minimum }
                is InspectionValue.Image -> result.matches.filter { BigDecimal.valueOf(it.score) >= target.minimum }.map { RecognizedText("", it.score, it.bounds) }
            }
            if (target.nearest) {
                // A selected target re-resolves to the closest match that has barely moved.
                val old = target.match.bounds
                fun centre(b: TextBounds) = (b.left + b.width / 2.0) to (b.top + b.height / 2.0)
                val (ox, oy) = centre(old)
                val closest = accepted.minByOrNull { val (x, y) = centre(it.bounds); kotlin.math.hypot(x - ox, y - oy) }
                    ?: return ActionResult.Unavailable("输入前在原位置附近未能确认目标")
                val (cx, cy) = centre(closest.bounds)
                return closest.takeIf { kotlin.math.hypot(cx - ox, cy - oy) <= maxOf(old.width, old.height) }
                    ?: ActionResult.Unavailable("输入前选中的目标已明显移动")
            }
            return accepted.singleOrNull() ?: ActionResult.Unavailable(if (accepted.isEmpty()) "输入前在原位置附近未能确认目标" else "输入前在原位置附近有多处目标，不能唯一确认")
        }
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
                } + invocation.detail?.let { "（$it）" }.orEmpty()
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
                            val token = UUID.randomUUID().toString(); observations[token] = store(acquired.value)
                            val geometry = acquired.value.result.stamp.geometry
                            ActionResult.Produced(buildJsonObject { put("observation", token) }, buildJsonObject {
                                put("kind", READ); put("candidates", acquired.value.result.candidates.size)
                                put("frame", buildJsonArray { add(geometry.width); add(geometry.height) })
                                put("timings", buildJsonObject { acquired.value.result.timings.forEach { (k, v) -> put(k, v) } }) })
                        }
                    }
                }
                EXPECT, LOCATE, CLASSIFY -> {
                    val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
                    observation.bridge.validateObservation(observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
                    if (!permitted(attempt.ticket)) return ActionResult.Interrupted
                    if (action.capability == CLASSIFY) {
                        fun seen(condition: JsonObject) = observation.result.candidates.any { it.text == condition.text("text") &&
                            it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= checkNotNull(threshold(condition)) }
                        val matches = inputs.getValue("states").jsonArray.map { it.jsonObject }.filter { state ->
                            seen(state) && (state["absent"] as? JsonArray).orEmpty().none { seen(it.jsonObject) }
                        }
                        val trace = buildJsonObject { put("kind", CLASSIFY); put("matchedStates", buildJsonArray { matches.forEach { add(checkNotNull(it.text("id"))) } }) }
                        if (matches.size != 1) return ActionResult.Unavailable(if (matches.isEmpty())
                            "本次画面未能确认任何声明状态" else "本次画面同时命中多个声明状态", trace)
                        return ActionResult.Produced(buildJsonObject { put("state", checkNotNull(matches.single().text("id"))) }, trace)
                    }
                    val expected = checkNotNull(inputs.text("text")); val minimum = checkNotNull(threshold(inputs))
                    if (action.capability == LOCATE) {
                        val match = uniqueTarget(observation, expected, minimum) ?: return ActionResult.Unavailable("本次画面不能唯一定位目标文字",
                            buildJsonObject { put("kind", LOCATE); put("sameText", observation.result.candidates.count { it.text == expected })
                                put("aboveMinimum", observation.result.candidates.count { it.text == expected && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }) })
                        val token = token(); targets[token] = Target(observation, expected, minimum, match)
                        ActionResult.Produced(buildJsonObject { put("target", token) }, buildJsonObject {
                            put("kind", LOCATE); region(match.bounds); put("confidence", match.confidence) })
                    } else {
                        val best = observation.result.candidates.filter { it.text == expected && it.confidence.isFinite() }.maxOfOrNull { it.confidence }
                        val trace = buildJsonObject { put("kind", EXPECT); put("bestConfidence", best ?: 0.0); put("minimumConfidence", minimum) }
                        if (best == null || BigDecimal.valueOf(best) < minimum)
                            return ActionResult.Unavailable("本次画面中未能确认目标文字", trace)
                        val evidence = "已在本次画面中核对目标文字" + observation.result.diagnostics.takeIf { it.isNotEmpty() }
                            ?.joinToString("；", "（识别清理诊断：", "）").orEmpty()
                        if (terminalAction == action.id) businessEvidence = StepOutcome.Completed(evidence)
                        ActionResult.Verified(buildJsonObject { put("matched", true) }, evidence, trace)
                    }
                }
                TAP, LONG_PRESS, SWIPE -> gesture(action, inputs)
                BACK -> back(action, inputs)
                TAP_REGION -> tapRegion(action, inputs)
                DERIVE -> derive(inputs)
                READ_REGION -> {
                    val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
                    when (val inspected = inspect(region, listOf(InspectionRequest.Text(region.bounds)))) {
                        is ActionResult -> inspected
                        else -> {
                            val values = (inspected as InspectionResult.Inspected).values.single() as InspectionValue.Text
                            val derived = region.observation.copy(regionDerived = true,
                                result = region.observation.result.copy(candidates = values.candidates, diagnostics = inspected.diagnostics))
                            val token = token(); observations[token] = store(derived)
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
                CLASSIFY_SCREEN, EXPECT_SCREEN -> screenState(action, inputs)
                AWAIT_SCREEN -> awaitScreen(action, inputs)
                LOCATE_IMAGE_IN, LOCATE_TEXT_IN -> locateIn(action, inputs)
                READ_NUMBER, READ_FRACTION -> readNumber(action, inputs)
                SELECT_TEXT, SELECT_IMAGE -> select(action, inputs)
                LAUNCH -> launch(action, inputs)
                AWAIT_FOREGROUND -> awaitForeground(inputs)
                ACCOUNT_REPORT -> {
                    val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
                    // The nickname box holds one line: its pieces left to right, as read with any confidence the read accepted.
                    val name = observation.result.candidates.filter { it.confidence.isFinite() }.sortedBy { it.bounds.left }
                        .joinToString("") { it.text.trim() }.take(40)
                    if (name.isBlank()) return ActionResult.Unavailable("昵称区域没有读到文字")
                    val uid = checkNotNull(integer(inputs["uid"])).toString()
                    runCatching { accountObserved?.invoke(attempt.run.request.context, uid, name) }
                    ActionResult.Produced(buildJsonObject { put("uid", uid); put("name", name) }, buildJsonObject { put("kind", ACCOUNT_REPORT); put("uid", uid); put("name", name) })
                }
                NOTIFY -> {
                    val delivered = notifier?.invoke(checkNotNull(inputs.text("title")), checkNotNull(inputs.text("text"))) == true
                    ActionResult.Produced(buildJsonObject { put("delivered", delivered) }, buildJsonObject { put("kind", NOTIFY); put("delivered", delivered) })
                }
                MATCH_COLOR -> matchColor(inputs)
                MATCH_COLOR_IN -> matchColorIn(inputs)
                COMPARE -> compare(inputs)
                LOCATE_IMAGE -> {
                    val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
                    if (observation.regionDerived) return ActionResult.Unavailable("图像定位需要整图观察")
                    val template = checkNotNull(inputs.text("template")); val minimum = checkNotNull(unit(inputs["minimumScore"]))
                    when (val value = uniqueImage(observation, template, minimum)) {
                        is ActionResult -> value
                        else -> {
                            val match = value as RecognizedText
                            val token = token(); targets[token] = Target(observation, "", minimum, match, template)
                            ActionResult.Produced(buildJsonObject { put("target", token) }, buildJsonObject {
                                put("kind", LOCATE_IMAGE); region(match.bounds); put("score", match.confidence); put("templateSha256", digest(template)) })
                        }
                    }
                }
                else -> error("未注册的屏幕动作")
            }
        }
        private fun readNumber(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            val minimum = checkNotNull(threshold(inputs)); val low = checkNotNull(integer(inputs["minimum"])); val high = checkNotNull(integer(inputs["maximum"]))
            val pattern = if (action.capability == READ_NUMBER) integerPattern else fractionPattern
            val values = observation.result.candidates.filter { it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }
                .mapNotNull { pattern.matchEntire(normalizedNumber(it.text)) }.map { match ->
                    if (action.capability == READ_NUMBER) listOf(match.value.toLong()) else listOf(match.groupValues[1].toLong(), match.groupValues[2].toLong())
                }.distinct()
            val trace = buildJsonObject { put("kind", action.capability); put("distinctValues", values.size) }
            val value = values.singleOrNull() ?: return ActionResult.Unavailable(if (values.isEmpty()) "本次画面中没有可读的数值" else "本次画面中有多个不同数值，不能确定", trace)
            if (value.any { it < low || it > high }) return ActionResult.Unavailable("读到的数值 ${value.joinToString("/")} 超出 $low–$high", trace)
            return ActionResult.Produced(buildJsonObject {
                put("value", value[0]); if (action.capability == READ_FRACTION) put("total", value[1])
            }, buildJsonObject { put("kind", action.capability); put("value", value[0]); if (value.size > 1) put("total", value[1]) })
        }
        private suspend fun select(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            val order = checkNotNull(inputs.text("order")); val index = checkNotNull(integer(inputs["index"]))
            val (matches, minimum, template, text) = if (action.capability == SELECT_TEXT) {
                val minimum = checkNotNull(threshold(inputs)); val text = checkNotNull(inputs.text("text"))
                Quad(observation.result.candidates.filter { it.text == text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }, minimum, null, text)
            } else {
                if (observation.regionDerived) return ActionResult.Unavailable("图像选择需要整图观察")
                val template = checkNotNull(inputs.text("template")); val minimum = checkNotNull(unit(inputs["minimumScore"]))
                val inspected = when (val value = inspectOn(observation, listOf(InspectionRequest.Image(whole(observation), template)))) {
                    is ActionResult -> return value; else -> value as InspectionResult.Inspected }
                Quad((inspected.values.single() as InspectionValue.Image).matches.filter { BigDecimal.valueOf(it.score) >= minimum }
                    .map { RecognizedText("", it.score, it.bounds) }, minimum, template, "")
            }
            val sorted = when (order) {
                "top" -> matches.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                "bottom" -> matches.sortedWith(compareBy({ -it.bounds.top }, { it.bounds.left }))
                "left" -> matches.sortedWith(compareBy({ it.bounds.left }, { it.bounds.top }))
                else -> matches.sortedWith(compareBy({ -it.bounds.left }, { it.bounds.top }))
            }
            val trace = buildJsonObject { put("kind", action.capability); put("matches", matches.size); put("order", order); put("index", index) }
            val match = sorted.getOrNull(index) ?: return ActionResult.Unavailable("只找到 ${matches.size} 处，没有第 ${index + 1} 处", trace)
            val token = token(); targets[token] = Target(observation, text, minimum, match, template, nearest = true)
            return ActionResult.Produced(buildJsonObject { put("target", token) }, buildJsonObject {
                put("kind", action.capability); put("matches", matches.size); region(match.bounds) })
        }
        private fun matchColorIn(inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            val (referenceWidth, referenceHeight) = checkNotNull(reference(inputs))
            val bounds = scaled(checkNotNull(rectangle(inputs["within"])), observation.result.stamp.geometry, referenceWidth, referenceHeight)
                ?: return ActionResult.Unavailable("查找区域超出画面，或画面比例与参考尺寸不同")
            return colorResult(observation, bounds, inputs, MATCH_COLOR_IN)
        }
        private fun matchColor(inputs: JsonObject): ActionResult {
            val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
            return colorResult(region.observation, region.bounds, inputs, MATCH_COLOR)
        }
        private fun colorResult(observation: Observation, b: TextBounds, inputs: JsonObject, kind: String): ActionResult {
            val pixels = pixelsFor(observation)?.let(::rgba) ?: return ActionResult.Unavailable("本次观察的画面像素已释放，需重新观察")
            val lower = checkNotNull(rgb(inputs["lower"])); val upper = checkNotNull(rgb(inputs["upper"])); val minimum = checkNotNull(unit(inputs["minimumRatio"]))
            var inside = 0; var total = 0
            for (y in b.top until b.top + b.height step 2) for (x in b.left until b.left + b.width step 2) {
                val i = (y * pixels.width + x) * 4
                val r = pixels.bytes[i].toInt() and 255; val g = pixels.bytes[i + 1].toInt() and 255; val bl = pixels.bytes[i + 2].toInt() and 255
                total++; if (r in lower[0]..upper[0] && g in lower[1]..upper[1] && bl in lower[2]..upper[2]) inside++
            }
            val ratio = if (total == 0) 0.0 else inside.toDouble() / total
            return ActionResult.Produced(buildJsonObject { put("matched", BigDecimal.valueOf(ratio) >= minimum); put("ratio", ratio) },
                buildJsonObject { put("kind", kind); region(b); put("ratio", ratio); put("sampled", total) })
        }
        private fun compare(inputs: JsonObject): ActionResult {
            val before = observations[inputs.text("before")] ?: beforeObservations[inputs.text("before")] ?: return ActionResult.Unavailable("之前的观察不可用")
            val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
            val old = pixelsFor(before)?.let(::rgba) ?: return ActionResult.Unavailable("之前观察的画面像素已释放")
            val new = pixelsFor(region.observation)?.let(::rgba) ?: return ActionResult.Unavailable("本次观察的画面像素已释放，需重新观察")
            if (old.width != new.width || old.height != new.height) return ActionResult.Unavailable("前后画面尺寸不同，不能比较")
            val b = region.bounds; var sum = 0L; var count = 0
            for (y in b.top until b.top + b.height step 2) for (x in b.left until b.left + b.width step 2) {
                val i = (y * new.width + x) * 4
                for (c in 0..2) sum += kotlin.math.abs((old.bytes[i + c].toInt() and 255) - (new.bytes[i + c].toInt() and 255))
                count += 3
            }
            val difference = if (count == 0) 0.0 else sum.toDouble() / (count * 255.0)
            val minimum = checkNotNull(unit(inputs["minimumDifference"]))
            return ActionResult.Produced(buildJsonObject { put("changed", BigDecimal.valueOf(difference) >= minimum); put("difference", difference) },
                buildJsonObject { put("kind", COMPARE); region(b); put("difference", difference) })
        }
        private suspend fun derive(inputs: JsonObject): ActionResult {
            val target = targets[inputs.text("target")] ?: return ActionResult.Unavailable("本步骤的锚点目标不可用")
            target.observation.bridge.validateObservation(target.observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            val value = { key: String -> checkNotNull(integer(inputs[key])) }
            val derivation = Derivation(target, value("dx"), value("dy"), value("width"), value("height"), value("referenceWidth"), value("referenceHeight"))
            // Offsets are authored on one layout. A different aspect ratio may reflow the UI, so do not stretch.
            val bounds = regionBounds(target.match, target.observation.result.stamp.geometry, derivation)
                ?: return ActionResult.Unavailable("由锚点推出的区域超出画面，或画面比例与参考尺寸不同")
            val token = token(); regions[token] = Region(target.observation, bounds, derivation)
            return ActionResult.Produced(buildJsonObject { put("region", token) }, buildJsonObject { put("kind", DERIVE); region(bounds) })
        }
        private suspend fun classifyRegion(inputs: JsonObject): ActionResult {
            val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
            val states = checkNotNull(regionStates(inputs))
            val templates = states.flatMap { (it.present + it.absent).map(ImageCondition::template) }.distinct()
            val needsText = states.any { it.text.isNotEmpty() || it.absentText.isNotEmpty() }
            val requests = listOfNotNull(InspectionRequest.Text(region.bounds).takeIf { needsText }) +
                templates.map { InspectionRequest.Image(region.bounds, it) }
            val inspected = when (val value = inspect(region, requests)) { is ActionResult -> return value; else -> value as InspectionResult.Inspected }
            val candidates = if (needsText) (inspected.values.first() as InspectionValue.Text).candidates else emptyList()
            val scores = templates.zip(inspected.values.drop(if (needsText) 1 else 0).map { (it as InspectionValue.Image).bestScore }).toMap()
            fun seen(c: TextCondition) = candidates.any { it.text == c.text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= c.minimum }
            fun holds(state: RegionState) = state.text.all(::seen) && state.absentText.none(::seen) &&
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
        private suspend fun awaitScreen(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val bridge = device() ?: return ActionResult.Unavailable("未连接无障碍服务，未取得画面")
            val profile = bundle.visionProfiles.single { it.id == inputs.text("profile") }
            val models = OcrModelReferences(profile.recognizer, profile.models)
            val states = checkNotNull(regionStates(inputs, repeatable = true, windowed = true))
            val (timeout, interval, stable) = checkNotNull(awaitTiming(inputs)); val (referenceWidth, referenceHeight) = checkNotNull(reference(inputs))
            val windows = states.flatMap { it.text + it.absentText }.map { checkNotNull(it.within) }.distinct()
            val images = states.flatMap { it.present + it.absent }.map { it.template to checkNotNull(it.within) }.distinct()
            val started = android.os.SystemClock.elapsedRealtime()
            var held: String? = null; var heldSince = 0L; var polls = 0; var lastScores = emptyList<Double>()
            fun trace(matched: String?) = buildJsonObject { put("kind", AWAIT_SCREEN); put("polls", polls); put("waitedMs", android.os.SystemClock.elapsedRealtime() - started)
                matched?.let { put("state", it) }; put("scores", buildJsonArray { lastScores.forEach { add(it) } }) }
            while (true) {
                if (!permitted(attempt.ticket)) return ActionResult.Interrupted
                val observation = when (val acquired = snapshot(attempt, bundle, bridge, profile.packageName, models)) {
                    is Acquisition.Problem -> return acquired.result
                    is Acquisition.Ready -> acquired.value
                }
                polls++
                val geometry = observation.result.stamp.geometry
                fun bounds(rect: List<Int>) = scaled(rect, geometry, referenceWidth, referenceHeight)
                if ((windows + images.map { it.second }).any { bounds(it) == null }) return ActionResult.Unavailable("查找区域超出画面，或画面比例与参考尺寸不同")
                // Templates are cheap, so they rule states out first; text is then read only where a state still depends on it.
                val scores = if (images.isEmpty()) emptyMap() else {
                    val values = when (val value = inspectOn(observation, images.map { (template, rect) -> InspectionRequest.Image(checkNotNull(bounds(rect)), template) })) {
                        is ActionResult -> return value
                        else -> (value as InspectionResult.Inspected).values
                    }
                    images.indices.associate { images[it] to (values[it] as InspectionValue.Image).bestScore }
                }
                lastScores = scores.values.toList()
                fun reached(c: ImageCondition) = BigDecimal.valueOf(scores.getValue(c.template to checkNotNull(c.within))) >= c.minimum
                var possible = states.filter { state -> state.present.all(::reached) && state.absent.none(::reached) }
                val texts = mutableMapOf<List<Int>, List<RecognizedText>>()
                fun seen(c: TextCondition) = texts.getValue(checkNotNull(c.within)).any { it.text == c.text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= c.minimum }
                while (true) {
                    val needed = possible.mapNotNull { state -> (state.text + state.absentText).firstOrNull { it.within !in texts }?.within }.distinct()
                    if (needed.isEmpty()) break
                    val values = when (val value = inspectOn(observation, needed.map { InspectionRequest.Text(checkNotNull(bounds(it))) })) {
                        is ActionResult -> return value
                        else -> (value as InspectionResult.Inspected).values
                    }
                    needed.forEachIndexed { index, rect -> texts[rect] = (values[index] as InspectionValue.Text).candidates }
                    possible = possible.filter { state -> state.text.none { it.within in texts && !seen(it) } && state.absentText.none { it.within in texts && seen(it) } }
                }
                val matched = possible.map { it.id }.distinct()
                val now = android.os.SystemClock.elapsedRealtime()
                val current = matched.singleOrNull()
                if (current != held) { held = current; heldSince = now }
                if (current != null && now - heldSince >= stable) {
                    val token = token(); observations[token] = store(observation)
                    val evidence = "已在本次画面中核对页面状态"
                    if (terminalAction == action.id) businessEvidence = StepOutcome.Completed(evidence)
                    return ActionResult.Verified(buildJsonObject { put("state", current); put("observation", token) }, evidence, trace(current))
                }
                if (now - started >= timeout) return ActionResult.Unavailable("等待 ${timeout}ms 后仍未确认任何声明的页面状态", trace(null))
                delay(interval)
            }
        }
        private suspend fun locateIn(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            if (observation.regionDerived) return ActionResult.Unavailable("区域定位需要整图观察")
            val (referenceWidth, referenceHeight) = checkNotNull(reference(inputs))
            val bounds = scaled(checkNotNull(rectangle(inputs["within"])), observation.result.stamp.geometry, referenceWidth, referenceHeight)
                ?: return ActionResult.Unavailable("查找区域超出画面，或画面比例与参考尺寸不同")
            val template = inputs.text("template").takeIf { action.capability == LOCATE_IMAGE_IN }; val text = inputs.text("text").orEmpty()
            val minimum = checkNotNull(if (template != null) unit(inputs["minimumScore"]) else threshold(inputs))
            val request = if (template != null) InspectionRequest.Image(bounds, template) else InspectionRequest.Text(bounds)
            val inspected = when (val value = inspectOn(observation, listOf(request))) { is ActionResult -> return value; else -> value as InspectionResult.Inspected }
            val accepted = when (val value = inspected.values.single()) {
                is InspectionValue.Text -> value.candidates.filter { it.text == text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= minimum }
                is InspectionValue.Image -> value.matches.filter { BigDecimal.valueOf(it.score) >= minimum }.map { RecognizedText("", it.score, it.bounds) }
            }
            val match = accepted.singleOrNull() ?: return ActionResult.Unavailable(if (accepted.isEmpty()) "查找区域内没有达到要求的目标" else "查找区域内有 ${accepted.size} 处目标，不能唯一定位")
            val token = token(); targets[token] = Target(observation, text, minimum, match, template)
            return ActionResult.Produced(buildJsonObject { put("target", token) }, buildJsonObject { put("kind", action.capability); region(match.bounds); put("confidence", match.confidence) })
        }
        private suspend fun screenState(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            if (observation.regionDerived) return ActionResult.Unavailable("页面判断需要整图观察")
            observation.bridge.validateObservation(observation.result.stamp)?.let { return ActionResult.Unavailable(deviceReason(it)) }
            val states = checkNotNull(regionStates(inputs, repeatable = true))
            val templates = states.flatMap { (it.present + it.absent).map(ImageCondition::template) }.distinct()
            val scores = if (templates.isEmpty()) emptyMap() else {
                val requests = templates.map { InspectionRequest.Image(whole(observation), it) }
                val inspected = when (val value = inspectOn(observation, requests)) { is ActionResult -> return value; else -> value as InspectionResult.Inspected }
                templates.zip(inspected.values.map { (it as InspectionValue.Image).bestScore }).toMap()
            }
            val candidates = observation.result.candidates
            fun seen(c: TextCondition) = candidates.any { it.text == c.text && it.confidence.isFinite() && BigDecimal.valueOf(it.confidence) >= c.minimum }
            val matched = states.filter { state -> state.text.all(::seen) && state.absentText.none(::seen) &&
                state.present.all { BigDecimal.valueOf(scores.getValue(it.template)) >= it.minimum } &&
                state.absent.all { BigDecimal.valueOf(scores.getValue(it.template)) < it.minimum } }.map { it.id }.distinct()
            val trace = buildJsonObject {
                put("kind", action.capability); put("textCandidates", candidates.size)
                put("templates", buildJsonArray { scores.forEach { (id, score) -> add(buildJsonObject { put("sha256", digest(id)); put("score", score) }) } })
                put("matchedStates", buildJsonArray { matched.forEach { add(it) } })
            }
            val scoreText = scores.values.joinToString("，", "（图像得分 ", "）") { "%.3f".format(it) }.takeIf { scores.isNotEmpty() }.orEmpty()
            if (matched.size > 1) return ActionResult.Unavailable("本次画面同时满足多个页面状态$scoreText", trace)
            val state = matched.singleOrNull() ?: inputs.text("otherwise").takeIf { action.capability == CLASSIFY_SCREEN }
                ?: return ActionResult.Unavailable("本次画面未能确认任何声明的页面状态$scoreText", trace)
            val outputs = buildJsonObject { put("state", state) }
            if (action.capability == CLASSIFY_SCREEN) return ActionResult.Produced(outputs, trace)
            val evidence = "已在本次画面中核对页面状态"
            if (terminalAction == action.id) businessEvidence = StepOutcome.Completed(evidence)
            return ActionResult.Verified(outputs, evidence, trace)
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
            val fresh = when (val acquired = snapshot(attempt, bundle, bridge, checkNotNull(target.observation.result.stamp.packageName), target.observation.models)) {
                is Acquisition.Problem -> return acquired.result
                is Acquisition.Ready -> acquired.value
            }
            val match = when (val value = resolve(target, fresh)) { is ActionResult -> return value; else -> value as RecognizedText }
            val bounds = match.bounds
            val centre = ScreenPoint(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
            var gestureMillis = checkNotNull(duration(inputs))
            var trace: JsonObject? = null
            if (action.capability != SWIPE) trace = buildJsonObject { put("kind", action.capability); region(bounds)
                put("point", buildJsonArray { add(centre.x); add(centre.y) }); put("durationMs", gestureMillis); put("imageTarget", target.template != null) }
            val points = if (action.capability != SWIPE) listOf(centre) else {
                val geometry = fresh.result.stamp.geometry
                val sx = geometry.width.toFloat() / checkNotNull(integer(inputs["referenceWidth"]))
                val sy = geometry.height.toFloat() / checkNotNull(integer(inputs["referenceHeight"]))
                if (kotlin.math.abs(sx / sy - 1f) > 0.01f) return ActionResult.Unavailable("当前画面比例与滑动参考尺寸不同")
                val shape = checkNotNull(variation(inputs["variation"]))
                val seed = shape.seed ?: java.security.SecureRandom().nextLong()
                val path = swipePath(centre, bounds, checkNotNull(integer(inputs["dx"])) * sx, checkNotNull(integer(inputs["dy"])) * sy, shape, seed)
                if (path.any { it.x < 0 || it.y < 0 || it.x >= geometry.width || it.y >= geometry.height }) return ActionResult.Unavailable("滑动路径超出画面")
                gestureMillis = java.util.Random(seed xor 0x5DEECE66DL).let { random ->
                    (gestureMillis * (1 + (random.nextDouble() * 2 - 1) * shape.durationJitter)).toLong().coerceIn(1, GestureDescription.getMaxGestureDuration())
                }
                trace = buildJsonObject { put("kind", SWIPE); put("seed", seed); put("points", path.size); put("durationMs", gestureMillis)
                    put("start", buildJsonArray { add(path.first().x); add(path.first().y) }); put("end", buildJsonArray { add(path.last().x); add(path.last().y) }) }
                path
            }
            return deliver(action, bridge, trace, trace?.takeIf { action.capability == SWIPE }?.let { "滑动种子 ${it["seed"]}，时长 ${it["durationMs"]}ms，采样点 ${it["points"]}" }) {
                it.gestureCall(fresh.result.stamp, points, gestureMillis)
            }
        }
        private suspend fun tapRegion(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val region = regions[inputs.text("region")] ?: return ActionResult.Unavailable("本步骤的区域不可用")
            val anchor = region.derivation.anchor
            if (anchor.observation.regionDerived) return ActionResult.Unavailable("区域内定位的锚点不能用于输入")
            val bridge = anchor.observation.bridge as? InputDevicePort ?: return ActionResult.Unavailable("当前观察来源未提供输入所有权")
            if (journal == null) return ActionResult.Unavailable("未连接持久化执行器")
            val fresh = when (val acquired = snapshot(attempt, bundle, bridge, checkNotNull(anchor.observation.result.stamp.packageName), anchor.observation.models)) {
                is Acquisition.Problem -> return acquired.result
                is Acquisition.Ready -> acquired.value
            }
            val match = when (val value = resolve(anchor, fresh)) { is ActionResult -> return value; else -> value as RecognizedText }
            val bounds = regionBounds(match, fresh.result.stamp.geometry, region.derivation) ?: return ActionResult.Unavailable("输入前推出的区域超出画面")
            var trace = buildJsonObject { put("kind", TAP_REGION); region(bounds) }
            if ((inputs["requireNoText"] as JsonPrimitive).boolean) {
                // Recognize with surrounding context: a tight crop of plain background makes the model invent characters.
                val geometry = fresh.result.stamp.geometry; val left = (bounds.left - BLANK_CONTEXT).coerceAtLeast(0); val top = (bounds.top - BLANK_CONTEXT).coerceAtLeast(0)
                val context = TextBounds(left, top, (bounds.left + bounds.width + BLANK_CONTEXT).coerceAtMost(geometry.width) - left,
                    (bounds.top + bounds.height + BLANK_CONTEXT).coerceAtMost(geometry.height) - top)
                val inspected = when (val value = inspectOn(fresh, listOf(InspectionRequest.Text(context)))) {
                    is ActionResult -> return value
                    else -> value as InspectionResult.Inspected
                }
                val candidates = (inspected.values.single() as InspectionValue.Text).candidates.filter { c ->
                    c.bounds.left < bounds.left + bounds.width && bounds.left < c.bounds.left + c.bounds.width &&
                        c.bounds.top < bounds.top + bounds.height && bounds.top < c.bounds.top + c.bounds.height }
                // A detector box with nothing recognized is not text; any recognized character, at any
                // confidence, still refuses the press.
                val found = candidates.filter { it.text.isNotBlank() }
                trace = buildJsonObject { put("kind", TAP_REGION); region(bounds); put("emptyDetections", candidates.size - found.size) }
                if (found.isNotEmpty()) return ActionResult.Unavailable(
                    "目标区域（${bounds.left},${bounds.top},${bounds.width}×${bounds.height}）内识别到 ${found.size} 处文字，不点击：" +
                        found.joinToString("、") { "「${it.text}」%.2f".format(it.confidence) }, trace)
            }
            val point = ScreenPoint(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
            return deliver(action, bridge, trace, null) { it.gestureCall(fresh.result.stamp, listOf(point), checkNotNull(duration(inputs))) }
        }
        private suspend fun launch(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val bridge = device() as? InputDevicePort ?: return ActionResult.Unavailable("未连接无障碍服务，不能启动目标应用")
            if (journal == null) return ActionResult.Unavailable("未连接持久化执行器")
            val profile = bundle.visionProfiles.single { it.id == inputs.text("profile") }
            return deliver(action, bridge, buildJsonObject { put("kind", LAUNCH); put("package", profile.packageName) }, null) { it.launchCall(profile.packageName) }
        }
        private suspend fun awaitForeground(inputs: JsonObject): ActionResult {
            val bridge = device() ?: return ActionResult.Unavailable("未连接无障碍服务，不能确认前台应用")
            val profile = bundle.visionProfiles.single { it.id == inputs.text("profile") }
            val timeout = checkNotNull(foregroundWait(inputs)); val started = android.os.SystemClock.elapsedRealtime()
            while (true) {
                if (!permitted(attempt.ticket)) return ActionResult.Interrupted
                val state = bridge.availability(setOf(profile.packageName))
                val waited = android.os.SystemClock.elapsedRealtime() - started
                val trace = buildJsonObject { put("kind", AWAIT_FOREGROUND); put("waitedMs", waited); put("state", state.name) }
                if (state == DeviceAvailability.READY) return ActionResult.Produced(buildJsonObject { put("foreground", true) }, trace)
                if (waited >= timeout) return ActionResult.Unavailable("等待 ${timeout}ms 后目标应用仍未到前台（${state.name}）", trace)
                delay(minOf(FOREGROUND_POLL_MS, timeout - waited))
            }
        }
        private suspend fun back(action: ActionDefinition, inputs: JsonObject): ActionResult {
            val observation = observations[inputs.text("observation")] ?: return ActionResult.Unavailable("本步骤的画面观察不可用")
            val bridge = observation.bridge as? InputDevicePort ?: return ActionResult.Unavailable("当前观察来源未提供输入所有权")
            if (journal == null) return ActionResult.Unavailable("未连接持久化执行器")
            val fresh = when (val acquired = snapshot(attempt, bundle, bridge, checkNotNull(observation.result.stamp.packageName), observation.models)) {
                is Acquisition.Problem -> return acquired.result
                is Acquisition.Ready -> acquired.value
            }
            return deliver(action, bridge, buildJsonObject { put("kind", BACK) }, null) { it.backCall(fresh.result.stamp) }
        }
        /** Journals the intent, takes the input channel, runs one call and always settles a receipt. */
        private suspend fun deliver(action: ActionDefinition, bridge: InputDevicePort, trace: JsonObject?, detail: String?,
            makeCall: (DeviceInputChannel) -> InputGestureCall): ActionResult {
            val journal = checkNotNull(journal)
            val invocation = Invocation(DeviceActionIntent(UUID.randomUUID().toString(), attempt.ticket, action.id,
                action.capability, action.contractVersion, UUID.randomUUID().toString()))
            invocation.detail = detail
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
                beforeObservations.clear(); beforeObservations.putAll(observations)
                observations.clear(); targets.clear(); regions.clear()
                previousFrames.clear(); previousFrames.putAll(frames); frames.clear()
                val channel = bridge.openInput(attempt.ticket, permitted) ?: return if (!permitted(attempt.ticket)) ActionResult.Interrupted
                    else ActionResult.Unavailable("输入通道当前不可用")
                invocation.channel = channel
                val call = makeCall(channel)
                invocation.call = call
                return when (val result = call.execute()) {
                    GestureResult.Completed -> ActionResult.Produced(buildJsonObject { put("delivered", true) }, trace)
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
