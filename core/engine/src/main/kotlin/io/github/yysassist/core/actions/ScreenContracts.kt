package io.github.yysassist.core.actions

import io.github.yysassist.core.content.*
import kotlinx.serialization.json.*
import java.math.BigDecimal

/*
 * The screen and app action contracts the Android App implements, kept here so every reader of a task bundle — the
 * App, the server's checks for the developer workbench — validates it by the same rules. Running the actions stays in
 * the App (ScreenActions); nothing here touches a device.
 */

/** Android refuses gestures longer than this (GestureDescription.getMaxGestureDuration()). */
const val MAX_GESTURE_DURATION_MS = 60_000L
/** The text recognizer the App ships (MaaTextBackend.CONTRACT). */
const val TEXT_RECOGNIZER = "maa.ppocr.v1"

    const val READ = "screen.readText"; const val EXPECT = "screen.expectText"
    const val LOCATE = "screen.locateText"; const val TAP = "screen.tap"
    const val CLASSIFY = "screen.classifyText"
    const val DERIVE = "screen.deriveRegion"; const val READ_REGION = "screen.readRegionText"
    const val MATCH = "screen.matchImage"; const val CLASSIFY_REGION = "screen.classifyRegion"
    const val CLASSIFY_SCREEN = "screen.classifyScreen"; const val EXPECT_SCREEN = "screen.expectScreen"
    const val AWAIT_SCREEN = "screen.awaitScreen"; const val LOCATE_IMAGE_IN = "screen.locateImageIn"; const val LOCATE_TEXT_IN = "screen.locateTextIn"
    const val MATCH_COLOR_IN = "screen.matchColorIn"
    const val LONG_PRESS = "screen.longPress"; const val SWIPE = "screen.swipe"; const val BACK = "screen.back"
    const val TAP_REGION = "screen.tapRegion"; const val DRAG_REGION = "screen.dragRegion"; const val LOCATE_IMAGE = "screen.locateImage"
    const val READ_NUMBER = "screen.readNumber"; const val READ_FRACTION = "screen.readFraction"
    const val SELECT_TEXT = "screen.selectText"; const val SELECT_IMAGE = "screen.selectImage"
    const val MATCH_COLOR = "screen.matchColor"; const val COMPARE = "screen.compareFrames"
    const val LAUNCH = "app.launch"; const val AWAIT_FOREGROUND = "app.awaitForeground"; const val NOTIFY = "notify.local"
    const val ACCOUNT_REPORT = "account.report"
    const val MAX_FOREGROUND_WAIT_MS = 120_000L; const val FOREGROUND_POLL_MS = 500L
    /** The platform refuses accessibility screenshots taken closer together than about a third of a second. */
    const val MIN_POLL_MS = 350
    /** Pixels of surroundings recognized with a blank-area check. */
    const val BLANK_CONTEXT = 80; const val DRAG_POINTS = 16
    val ORDERS = setOf("top", "bottom", "left", "right")
    /** Handle kinds. Region-derived observations keep REGION in their lineage. */
    const val OBSERVATION = "screen.observation"; const val TARGET = "screen.target"; const val REGION = "screen.region"
    const val MAX_STATES = 8; const val MAX_TEMPLATES = 16; const val MAX_OFFSET = 100_000
    const val RETAINED_FRAMES = 2
    const val TRIGGER_CONFIDENCE = 0.6

fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
fun unit(value: JsonElement?): BigDecimal? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content
    ?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
fun threshold(inputs: JsonObject): BigDecimal? = unit(inputs["minimumConfidence"])
fun integer(value: JsonElement?): Int? = try {
    (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.intValueExact()
} catch (_: ArithmeticException) { null }
fun duration(inputs: JsonObject): Long? = try {
    (inputs["durationMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact()
        ?.takeIf { it in 1..MAX_GESTURE_DURATION_MS }
} catch (_: ArithmeticException) { null }
val modelKeys = setOf("detector", "recognizer", "dictionary")
val observationHandle = mapOf("observation" to HandleRequirement(setOf(OBSERVATION)))
val regionHandle = mapOf("region" to HandleRequirement(setOf(REGION)))
val textTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING, "minimumConfidence" to ActionValueType.NUMBER)
val geometryKeys = listOf("dx", "dy", "width", "height", "referenceWidth", "referenceHeight")
val swipeKeys = listOf("dx", "dy", "referenceWidth", "referenceHeight")
/** Full-width digits and slash folded, spaces and thousands separators removed. No units such as 万. */
fun normalizedNumber(text: String) = buildString {
    text.forEach { c -> when (c) { in '０'..'９' -> append('0' + (c - '０')); '／' -> append('/'); ' ', '　', ',', '，' -> Unit; else -> append(c) } }
}
val integerPattern = Regex("\\d{1,9}"); val fractionPattern = Regex("(\\d{1,9})/(\\d{1,9})")
fun rangeIssue(inputs: JsonObject): ContentIssue? {
    val low = integer(inputs["minimum"]); val high = integer(inputs["maximum"])
    return when {
        "minimum" in inputs && (low == null || low < 0) -> ContentIssue("minimum", "下限需为非负整数")
        "maximum" in inputs && (high == null || high < 0) -> ContentIssue("maximum", "上限需为非负整数")
        low != null && high != null && low > high -> ContentIssue("maximum", "上限不能小于下限")
        else -> null
    }
}
fun rgb(value: JsonElement?): IntArray? = (value as? JsonArray)?.takeIf { it.size == 3 }?.map { integer(it)?.takeIf { v -> v in 0..255 } ?: return null }?.toIntArray()
/** Explicit, reproducible path variation. Every field is authored; only seed may be left to the App. */
data class Variation(val startJitter: Double, val curve: Double, val durationJitter: Double, val points: Int, val seed: Long?)
fun variation(value: JsonElement?): Variation? {
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
fun knownText(inputs: JsonObject): ContentIssue? = when {
    "text" in inputs && inputs.text("text").isNullOrBlank() -> ContentIssue("text", "核对文字不能为空")
    "minimumConfidence" in inputs && threshold(inputs) == null -> ContentIssue("minimumConfidence", "置信度需为零到一之间的数值")
    else -> null
}
fun completeText(inputs: JsonObject): String? = if (inputs.keys != textTypes.keys || inputs.text("observation").isNullOrBlank() ||
    inputs.text("text").isNullOrBlank() || threshold(inputs) == null) "请指定观察、文字及零到一之间的置信度" else null
fun validStates(inputs: JsonObject): Boolean {
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
data class TextCondition(val text: String, val minimum: BigDecimal, val within: List<Int>? = null)
data class ImageCondition(val template: String, val minimum: BigDecimal, val within: List<Int>? = null)
/** x, y, width, height on the task's reference frame. */
fun rectangle(value: JsonElement?): List<Int>? = (value as? JsonArray)?.takeIf { it.size == 4 }?.map { integer(it) ?: return null }
    ?.takeIf { (x, y, w, h) -> x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= MAX_OFFSET && y.toLong() + h <= MAX_OFFSET }
fun reference(inputs: JsonObject): Pair<Int, Int>? {
    val width = integer(inputs["referenceWidth"])?.takeIf { it in 1..MAX_OFFSET } ?: return null
    return (integer(inputs["referenceHeight"])?.takeIf { it in 1..MAX_OFFSET } ?: return null).let { width to it }
}
data class RegionState(val id: String, val text: List<TextCondition>, val present: List<ImageCondition>, val absent: List<ImageCondition>,
    val absentText: List<TextCondition>)
/** Whole-screen states may repeat an id: any entry proves it, as when one page has several looks. */
fun regionStates(inputs: JsonObject, repeatable: Boolean = false, windowed: Boolean = false): List<RegionState>? {
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
val awaitKeys = setOf("profile", "states", "timeoutMs", "intervalMs", "stableMs", "referenceWidth", "referenceHeight")
fun awaitTiming(inputs: JsonObject): Triple<Long, Long, Long>? {
    val timeout = foregroundWait(inputs) ?: return null
    val interval = integer(inputs["intervalMs"])?.takeIf { it in MIN_POLL_MS..2000 } ?: return null
    val stable = integer(inputs["stableMs"])?.takeIf { it in 0..5000 } ?: return null
    return Triple(timeout, interval.toLong(), stable.toLong())
}
fun locateInIssue(inputs: JsonObject, name: String, valid: Boolean): String? = when {
    inputs.text("observation").isNullOrBlank() || !valid || rectangle(inputs["within"]) == null || reference(inputs) == null -> "请指定观察、$name、查找区域及参考截图尺寸"
    "optional" in inputs && (inputs["optional"] as? JsonPrimitive)?.booleanOrNull == null -> "“没找到不算失败”需为是或否"
    else -> null
}
/**
 * Region finds may be `optional`: nothing found is then `found = false` instead of failing, and `target` is empty, so
 * only a branch on `found` should use it (a tap given an empty target fails). Several matches still fail.
 */
val locateInOutputs = mapOf("target" to ActionValueType.STRING, "found" to ActionValueType.BOOLEAN)
val wholeObservation = mapOf("observation" to HandleRequirement(setOf(OBSERVATION), forbiddenAncestors = setOf(REGION)))
fun screenStatesIssue(bundle: TaskBundle, action: ActionDefinition): String? = when {
    "states" in action.bindings || "otherwise" in action.bindings -> "状态证据必须由任务版本明确指定"
    regionStates(action.inputs, repeatable = true)?.flatMap { it.present + it.absent }?.any { c -> bundle.resources.none { it.id == c.template } } == true ->
        "页面状态引用了未声明的模板资源"
    else -> null
}
/** The state reported when nothing matches; it must not be mistaken for a declared state. */
fun otherwiseValid(inputs: JsonObject): Boolean = inputs.text("otherwise").let { value ->
    !value.isNullOrBlank() && regionStates(inputs, repeatable = true)?.none { it.id == value } != false }
fun foregroundWait(inputs: JsonObject): Long? = try {
    (inputs["timeoutMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it in 1..MAX_FOREGROUND_WAIT_MS }
} catch (_: ArithmeticException) { null }
fun selectIssue(inputs: JsonObject): ContentIssue? = when {
    "order" in inputs && inputs.text("order") !in ORDERS -> ContentIssue("order", "排序需为 top、bottom、left 或 right")
    "index" in inputs && integer(inputs["index"])?.takeIf { it in 0..49 } == null -> ContentIssue("index", "序号需为 0–49 的整数")
    else -> null
}
fun colorIssue(inputs: JsonObject): ContentIssue? {
    val lower = rgb(inputs["lower"]); val upper = rgb(inputs["upper"])
    return when {
        "lower" in inputs && lower == null -> ContentIssue("lower", "颜色下限需为三个 0–255 的整数")
        "upper" in inputs && upper == null -> ContentIssue("upper", "颜色上限需为三个 0–255 的整数")
        lower != null && upper != null && (0..2).any { lower[it] > upper[it] } -> ContentIssue("upper", "颜色上限不能小于下限")
        "minimumRatio" in inputs && unit(inputs["minimumRatio"]) == null -> ContentIssue("minimumRatio", "比例需为零到一之间的数值")
        else -> null
    }
}
fun profileIssue(bundle: TaskBundle, task: TaskDefinition, action: ActionDefinition): String? {
    if (action.bindings.isNotEmpty()) return "视觉配置必须由任务版本明确指定"
    val profile = bundle.visionProfiles.singleOrNull { it.id == action.inputs.text("profile") } ?: return "引用的视觉配置不存在"
    return when {
        profile.targetId != task.targetId -> "视觉配置不属于任务目标"
        profile.recognizer != TEXT_RECOGNIZER || profile.models.keys != modelKeys -> "视觉配置的识别合同或模型角色不受支持"
        else -> null
    }
}

/** Every action contract the App offers, in the order it registers them. */
val screenContracts: List<CapabilityContract> = listOf(
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
    // Drags from the centre of a derived region by a fixed offset, in a straight line: picking up a card that has no
    // stable text or icon of its own (a shikigami in a list) by its place relative to a label on the same page.
    CapabilityContract(DRAG_REGION, 1, inputTypes = mapOf("region" to ActionValueType.STRING, "durationMs" to ActionValueType.NUMBER) +
        swipeKeys.associateWith { ActionValueType.NUMBER }, outputTypes = mapOf("delivered" to ActionValueType.BOOLEAN), sendsDeviceInput = true,
        inputHandles = mapOf("region" to HandleRequirement(setOf(REGION), forbiddenAncestors = setOf(REGION))),
        validateKnownInputs = { inputs -> when {
            "durationMs" in inputs && duration(inputs) == null -> ContentIssue("durationMs", "拖动时长需为平台允许的正整数毫秒")
            else -> swipeKeys.firstOrNull { key -> key in inputs && integer(inputs[key]).let { value ->
                value == null || (if (key.startsWith("d")) value !in -MAX_OFFSET..MAX_OFFSET else value !in 1..MAX_OFFSET) } }
                ?.let { ContentIssue(it, "拖动位移需为整数，参考尺寸需为正整数") }
                ?: if (integer(inputs["dx"]) == 0 && integer(inputs["dy"]) == 0) ContentIssue("dx", "拖动位移不能为零") else null
        } },
        validateDefinition = { _, _, _, action -> if (swipeKeys.any { it in action.bindings }) "拖动位移必须由任务版本明确指定" else null }) { inputs ->
        if (inputs.keys != setOf("region", "durationMs") + swipeKeys || inputs.text("region").isNullOrBlank() || duration(inputs) == null ||
            swipeKeys.any { integer(inputs[it]) == null }) "请指定区域、位移、参考尺寸及拖动时长" else null
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
        "minimumScore" to ActionValueType.NUMBER, "within" to ActionValueType.ARRAY, "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER,
        "optional" to ActionValueType.BOOLEAN),
        outputTypes = locateInOutputs, outputHandles = mapOf("target" to TARGET), inputHandles = wholeObservation,
        validateDefinition = { bundle, _, _, action -> if (bundle.resources.none { it.id == action.inputs.text("template") }) "图像定位引用了未声明的模板资源" else null }) { inputs ->
        locateInIssue(inputs, "模板和得分", !inputs.text("template").isNullOrBlank() && unit(inputs["minimumScore"]) != null)
    },
    CapabilityContract(LOCATE_TEXT_IN, 1, inputTypes = mapOf("observation" to ActionValueType.STRING, "text" to ActionValueType.STRING,
        "minimumConfidence" to ActionValueType.NUMBER, "within" to ActionValueType.ARRAY, "referenceWidth" to ActionValueType.NUMBER, "referenceHeight" to ActionValueType.NUMBER,
        "optional" to ActionValueType.BOOLEAN),
        outputTypes = locateInOutputs, outputHandles = mapOf("target" to TARGET), inputHandles = wholeObservation) { inputs ->
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
