package io.github.yysassist.actions

import io.github.yysassist.core.content.*
import io.github.yysassist.vision.MaaTextBackend
import kotlinx.serialization.json.*
import java.security.MessageDigest

private const val game = "com.netease.onmyoji"
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Draft daily-gift step shared by offline replay and the gated live claim. Geometry from 2400x1080
 * captures; the 0.45 jade threshold is provisional, from one device and one day of samples.
 */
internal fun dailyGiftBundle(models: List<TaskResource>, jade: ByteArray, identity: IdentityPolicy, version: String): TaskBundle {
    fun out(action: String, field: String) = ActionInputBinding.Output(action, field)
    val read = ActionDefinition("read", ScreenActions.READ, 2, buildJsonObject { put("profile", "onmyoji") })
    val anchor = ActionDefinition("anchor", ScreenActions.LOCATE, 1, buildJsonObject { put("text", "每日礼包"); put("minimumConfidence", 0.8) },
        mapOf("observation" to out("read", "observation")))
    val button = ActionDefinition("button", ScreenActions.DERIVE, 1, buildJsonObject {
        put("dx", -120); put("dy", 195); put("width", 240); put("height", 55); put("referenceWidth", 2400); put("referenceHeight", 1080)
    }, mapOf("target" to out("anchor", "target")))
    val jadeCondition = buildJsonObject { put("template", "jade-icon"); put("minimumScore", 0.45) }
    val state = ActionDefinition("state", ScreenActions.CLASSIFY_REGION, 1, buildJsonObject { put("states", buildJsonArray {
        add(buildJsonObject { put("id", "claimable")
            put("text", buildJsonArray { add(buildJsonObject { put("text", "领取"); put("minimumConfidence", 0.8) }) })
            put("absent", buildJsonArray { add(jadeCondition) }) })
        add(buildJsonObject { put("id", "paid"); put("present", buildJsonArray { add(jadeCondition) }) })
    }) }, mapOf("region" to out("button", "region")))
    val claim = ActionDefinition("claim", ScreenActions.LOCATE, 1, buildJsonObject { put("text", "领取"); put("minimumConfidence", 0.8) },
        mapOf("observation" to out("read", "observation")))
    val tap = ActionDefinition("tap", ScreenActions.TAP, 1, buildJsonObject { put("durationMs", 80) }, mapOf("target" to out("claim", "target")))
    // The game shows a loading layer before the reward; observe after a fixed, authored pause.
    val settle = ActionDefinition("settle", "control.wait", 1, buildJsonObject { put("durationMs", 2500) })
    val after = read.copy(id = "after")
    val reward = ActionDefinition("reward", ScreenActions.EXPECT, 1, buildJsonObject { put("text", "获得奖励"); put("minimumConfidence", 0.8) },
        mapOf("observation" to out("after", "observation")))
    val listed = ActionDefinition("listed", ScreenActions.EXPECT, 1, buildJsonObject { put("text", "每日礼包"); put("minimumConfidence", 0.8) },
        mapOf("observation" to out("read", "observation")))
    val program = listOf(read, anchor, button, state).map(ProgramNode::Action) + ProgramNode.Branch(out("state", "state"), listOf(
        ProgramCase(JsonPrimitive("claimable"), listOf(claim, tap, settle, after, reward).map(ProgramNode::Action)),
        ProgramCase(JsonPrimitive("paid"), listOf(ProgramNode.Action(listed)))))
    val task = TaskDefinition("daily-gift", "onmyoji", "领取每日礼包", "daily", 10, steps = listOf(StepDefinition("claim", "领取免费每日礼包", program = program)))
    val target = TargetProfile("onmyoji", "阴阳师", listOf(game), identity, listOf(TaskCategory("daily", "日常")))
    val template = TaskResource("jade-icon", "jade-icon.png", jade.size.toLong(), sha(jade))
    return TaskBundle(1, version, "2026-11-18T00:00:00Z", "每日礼包草稿", listOf(task), listOf(target), models + template,
        listOf(VisionProfile("onmyoji", "onmyoji", game, MaaTextBackend.CONTRACT,
            mapOf("detector" to "det.onnx", "recognizer" to "rec.onnx", "dictionary" to "keys.txt"))))
}
