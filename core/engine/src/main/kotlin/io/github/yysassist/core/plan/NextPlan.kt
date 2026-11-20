package io.github.yysassist.core.plan

import io.github.yysassist.protocol.PlanView
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What the screen saver says comes next: the soonest run of the enabled plans that may run on [deviceId] (a plan with
 * no devices runs on any), as “下次 06:30 每日” — today without a day, “明天”, or the weekday. Null when none.
 */
fun nextPlanText(plans: List<PlanView>, deviceId: String?, zone: String, now: ZonedDateTime): String? {
    val here = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    val moment = now.withZoneSameInstant(here)
    val next = plans.asSequence()
        .filter { it.enabled && it.days.isNotEmpty() && (it.deviceIds.isEmpty() || deviceId in it.deviceIds) }
        .mapNotNull { plan ->
            val days = plan.days.mapNotNull { day -> runCatching { DayOfWeek.of(day) }.getOrNull() }.toSet()
            runCatching { PlanSchedule(days, plan.minuteOfDay).next(moment) }.getOrNull()?.let { it to plan }
        }
        .minByOrNull { it.first } ?: return null
    val (at, plan) = next
    val day = when (at.toLocalDate()) {
        moment.toLocalDate() -> ""
        moment.toLocalDate().plusDays(1) -> "明天 "
        else -> WEEKDAYS[at.dayOfWeek.value - 1] + " "
    }
    return "下次 $day${at.format(TIME)} ${plan.name}"
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
