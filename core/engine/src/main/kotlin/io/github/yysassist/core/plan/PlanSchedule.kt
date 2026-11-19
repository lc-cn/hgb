package io.github.yysassist.core.plan

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/** When a plan runs: on the chosen weekdays at one local time. Every day is simply all seven days. */
data class PlanSchedule(val days: Set<DayOfWeek>, val minuteOfDay: Int) {
    init {
        require(days.isNotEmpty()) { "计划至少要选择一天" }
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "计划时间无效" }
    }
    val daily: Boolean get() = days.size == DayOfWeek.entries.size
    val daysMask: Int get() = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    /** The first run strictly after [moment]. */
    fun next(moment: ZonedDateTime): ZonedDateTime = (0..SEARCH_DAYS).asSequence().map { slot(moment, it.toLong()) }
        .filterNotNull().first { it.isAfter(moment) }

    /** The latest run at or before [moment]. */
    fun previous(moment: ZonedDateTime): ZonedDateTime = (0..SEARCH_DAYS).asSequence().map { slot(moment, -it.toLong()) }
        .filterNotNull().first { !it.isAfter(moment) }

    private fun slot(moment: ZonedDateTime, offsetDays: Long): ZonedDateTime? {
        val date = moment.toLocalDate().plusDays(offsetDays)
        // A time skipped by a clock change resolves to the first valid moment after it.
        return if (date.dayOfWeek in days) date.atTime(LocalTime.ofSecondOfDay(minuteOfDay * 60L)).atZone(moment.zone) else null
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        private const val SEARCH_DAYS = 8
        fun daysOf(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    }
}

/** What is missing before a plan can be saved. Nothing is filled in on the user's behalf. */
fun planIssues(name: String, days: Set<DayOfWeek>, minuteOfDay: Int?, taskIds: List<String>): List<String> = buildList {
    if (name.isBlank()) add("请填写计划名称")
    if (days.isEmpty()) add("请选择运行周期")
    if (minuteOfDay == null) add("请选择运行时间")
    if (taskIds.isEmpty()) add("请至少添加一个任务")
    if (taskIds.distinct().size != taskIds.size) add("计划中的任务不能重复")
}
