package io.github.yysassist.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.yysassist.AssistApplication
import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.storage.TaskVersionLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug builds only, for looking at the interface on an emulator. Installs a task list whose steps only
 * wait, so nothing touches a game. Protected by android.permission.DUMP: only the shell can send it.
 *
 * adb shell am broadcast -n <pkg>/.debug.DemoTaskSeeder -a io.github.yysassist.debug.SEED_DEMO_TASKS [--ei planInMinutes 2]
 */
class DemoTaskSeeder : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Application.getProcessName() != context.packageName) return
        val app = context.applicationContext as AssistApplication
        val pending = goAsync()
        scope.launch {
            try {
                val text = app.taskCodec.write(demoBundle())
                val installed = app.versionLibrary.install(text, VERSION, TaskVersionLibrary.sha256(text))
                Log.i(TAG, "install=$installed activate=${app.versionLibrary.activate(VERSION)}")
                // Local end-to-end checks only: signs in to a development server with a test account.
                intent.getStringExtra("cloudServer")?.let { server ->
                    runCatching { app.cloud.signIn(server, intent.getStringExtra("cloudEmail").orEmpty(), intent.getStringExtra("cloudPassword").orEmpty()) }
                        .onFailure { Log.w(TAG, "cloud sign-in failed: ${it.javaClass.simpleName}") }
                }
                val minutes = intent.getIntExtra("planInMinutes", -1)
                if (minutes >= 0) {
                    val at = java.time.LocalTime.now().plusMinutes(minutes.toLong())
                    app.plans.save(null, "演示计划", ExecutionContext(TARGET, Principal.Anonymous),
                        PlanSchedule(java.time.DayOfWeek.entries.toSet(), at.hour * 60 + at.minute), listOf("mail", "sign"))
                }
            } finally { pending.finish() }
        }
    }

    private fun number(id: String, label: String, section: String, min: Int, max: Int, step: Int, value: Int, note: String) =
        ConfigurationField(id, label, FieldWidget.NUMBER, true, null, JsonPrimitive(min), JsonPrimitive(max), JsonPrimitive(step),
            section = section, description = note, recommended = JsonPrimitive(value))
    private fun switch(id: String, label: String, section: String, value: Boolean, note: String) =
        ConfigurationField(id, label, FieldWidget.SWITCH, true, section = section, description = note, recommended = JsonPrimitive(value))
    private fun choice(id: String, label: String, section: String, value: String, vararg options: String) =
        ConfigurationField(id, label, FieldWidget.SINGLE_CHOICE, true, options = options.map { FieldOption(it, it) },
            section = section, recommended = JsonPrimitive(value))

    private fun task(id: String, category: String, name: String, note: String, fields: List<ConfigurationField> = emptyList()) =
        TaskDefinition(id, TARGET, name, category, 10, fields, listOf(StepDefinition("wait", "演示等待", listOf(
            ActionDefinition("wait", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(60_000))))))), note)

    private fun demoBundle() = TaskBundle(1, VERSION, "2026-11-18T00:00:00Z", "界面演示数据", listOf(
        task("launch", "base", "启动并进入庭院", "从桌面启动，停在庭院"), task("home", "base", "归位庭院", "从已知界面逐级返回"),
        task("gift", "daily", "每日礼包领取", "只领免费礼包"), task("yard", "daily", "庭院事务一键领取", "领取庭院每日奖励"), task("mail", "daily", "邮件领取", "领取全部附件"),
        task("sign", "daily", "每日签到", "完成当日签到"), task("pet", "daily", "宠物投喂", "完成当日投喂"),
        task("soul", "fight", "御魂副本单人", "第 11 层 · 30 次 · 体力上限 240", listOf(
            number("floor", "层数", "挑战目标", 1, 11, 1, 11, "八岐大蛇挑战层"), number("times", "挑战次数", "挑战目标", 5, 200, 5, 30, "按结算核对通过计次"),
            number("stamina", "体力上限", "消耗上限", 40, 2000, 40, 240, "本次最多消耗，到达后结束任务"),
            switch("boost", "使用御魂加成", "加成", false, "运行前开启，任务结束后关闭"),
            number("fail", "连续失败后结束", "失败处理", 1, 10, 1, 3, "避免阵容不足时持续消耗体力"))),
        task("invite", "daily", "协作邀请处理", "只接受勾选的协作类型", listOf(
            ConfigurationField("accept", "接受的协作", FieldWidget.MULTI_CHOICE, true, section = "协作类型", description = "默认都不选，不选就不处理邀请", options = listOf(
                FieldOption("jade", "勾玉协作"), FieldOption("cat", "猫粮协作"), FieldOption("dog", "狗粮协作"), FieldOption("event", "活动邀约"),
                FieldOption("soul-team", "御魂组队"), FieldOption("explore-team", "探索组队"))),
            ConfigurationField("soul-friends", "好友范围", FieldWidget.TEXT, true, section = "御魂组队", description = "只接受这些好友的邀请", visibleWhen = FieldCondition("accept", "soul-team")),
            number("soul-times", "次数上限", "御魂组队", 1, 200, 1, 10, "到达后离队").copy(visibleWhen = FieldCondition("accept", "soul-team")),
            number("soul-stamina", "体力上限", "御魂组队", 40, 2000, 40, 240, "本次最多消耗").copy(visibleWhen = FieldCondition("accept", "soul-team")),
            ConfigurationField("explore-friends", "好友范围", FieldWidget.TEXT, true, section = "探索组队", description = "只接受这些好友的邀请", visibleWhen = FieldCondition("accept", "explore-team")),
            number("explore-times", "次数上限", "探索组队", 1, 200, 1, 10, "到达后离队").copy(visibleWhen = FieldCondition("accept", "explore-team")),
            number("explore-stamina", "体力上限", "探索组队", 40, 2000, 40, 240, "本次最多消耗").copy(visibleWhen = FieldCondition("accept", "explore-team")),
        )).copy(priority = TRIGGER_MIN_PRIORITY, trigger = TaskTrigger(listOf("悬赏协作"), "accept")),
        task("realm", "kekkai", "个人结界突破", "用完突破券后结束"), task("guild", "kekkai", "寮突破", "按寮内开放时间运行"),
        task("souls", "soul", "御魂强化与处置", "强化到 +15 · 处置前先确认", listOf(
            choice("star", "星级", "强化对象", "六星", "五星", "六星").copy(tab = "强化"),
            number("level", "目标等级", "强化对象", 3, 15, 3, 15, "达到后停止强化该御魂").copy(tab = "强化"),
            number("gold", "金币上限", "花费上限", 100, 5000, 100, 500, "单位：万，到达后结束任务").copy(tab = "强化"),
            choice("max", "最高星级", "处置范围", "四星", "三星", "四星", "五星").copy(tab = "处置", description = "高于此星级的御魂一律不处置"),
            switch("keep", "保留已强化的御魂", "处置范围", true, "等级高于 +0 的不处置").copy(tab = "处置"),
            switch("locked", "保留已锁定的御魂", "处置范围", true, "已锁定的始终不处置").copy(tab = "处置", readOnly = true),
            switch("confirm", "处置前先确认", "执行方式", true, "先列出将处置的御魂，确认后再执行").copy(tab = "处置"))),
    ), listOf(TargetProfile(TARGET, "阴阳师", listOf("com.netease.onmyoji"), IdentityPolicy.NONE, listOf(
        TaskCategory("base", "运行基础"), TaskCategory("daily", "日常与领取"), TaskCategory("fight", "副本与挑战"),
        TaskCategory("kekkai", "结界与阴阳寮"), TaskCategory("soul", "御魂整理")))))

    private companion object {
        const val ACTION = "io.github.yysassist.debug.SEED_DEMO_TASKS"
        const val VERSION = "demo-4"
        const val TARGET = "onmyoji"
        const val TAG = "DemoTaskSeeder"
    }
}
