package io.github.yysassist.core.plan

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.*
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = ZonedDateTime.parse("${text}+08:00[Asia/Shanghai]")

    @Test fun `next is strictly after the moment so a saved plan never fires for the current minute`() {
        val daily = PlanSchedule(entries.toSet(), 6 * 60 + 30)
        assertEquals(at("2026-11-19T06:30:00"), daily.next(at("2026-11-18T06:30:00")))
        assertEquals(at("2026-11-18T06:30:00"), daily.next(at("2026-11-18T06:29:59")))
    }
    @Test fun `chosen weekdays skip the other days in both directions`() {
        val weekend = PlanSchedule(setOf(SATURDAY, SUNDAY), 20 * 60)
        assertEquals(at("2026-11-21T20:00:00"), weekend.next(at("2026-11-18T12:00:00")))
        assertEquals(at("2026-11-15T20:00:00"), weekend.previous(at("2026-11-18T12:00:00")))
        assertEquals(at("2026-11-22T20:00:00"), weekend.previous(at("2026-11-22T20:00:00")))
    }
    @Test fun `a time skipped by daylight saving still produces one run that day`() {
        val newYork = ZoneId.of("America/New_York")
        val plan = PlanSchedule(entries.toSet(), 2 * 60 + 30)
        val next = plan.next(ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork))
        assertEquals(8, next.dayOfMonth); assertEquals(3, next.hour)
    }
    @Test fun `days survive the stored mask`() {
        val days = setOf(MONDAY, WEDNESDAY, SUNDAY)
        assertEquals(days, PlanSchedule.daysOf(PlanSchedule(days, 0).daysMask))
    }
    @Test fun `saving reports every missing part and fills in nothing`() {
        assertEquals(4, planIssues(" ", emptySet(), null, emptyList()).size)
        assertTrue(planIssues("每日", setOf(MONDAY), 0, listOf("gift")).isEmpty())
        assertEquals(listOf("计划中的任务不能重复"), planIssues("每日", setOf(MONDAY), 0, listOf("a", "a")))
    }
}
