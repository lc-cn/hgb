package io.github.yysassist.core.plan

import io.github.yysassist.protocol.PlanView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextPlanTest {
    private fun plan(name: String, minute: Int, days: List<Int> = (1..7).toList(), devices: List<String> = emptyList(), enabled: Boolean = true) =
        PlanView("p-$name", "a", name, days, minute, emptyList(), devices, 30, enabled, 1, 0)
    // Thursday 2026-11-19 22:00 in Shanghai.
    private val now = ZonedDateTime.of(2026, 11, 19, 22, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun theSoonestEnabledPlanForThisPhoneIsNamedWithItsDay() {
        val plans = listOf(plan("每日", 6 * 60 + 30), plan("夜里", 23 * 60), plan("关着的", 22 * 60 + 10, enabled = false),
            plan("别的手机", 22 * 60 + 5, devices = listOf("other")))
        assertEquals("下次 23:00 夜里", nextPlanText(plans, "mine", "Asia/Shanghai", now))
        assertEquals("下次 明天 06:30 每日", nextPlanText(listOf(plans[0]), "mine", "Asia/Shanghai", now))
        // Only on Mondays: four days away, named by its weekday.
        assertEquals("下次 周一 06:30 周一", nextPlanText(listOf(plan("周一", 6 * 60 + 30, days = listOf(1))), "mine", "Asia/Shanghai", now))
    }

    @Test fun nothingToSayWithoutAPlanThatRunsHere() {
        assertNull(nextPlanText(emptyList(), "mine", "Asia/Shanghai", now))
        assertNull(nextPlanText(listOf(plan("别的手机", 600, devices = listOf("other"))), "mine", "Asia/Shanghai", now))
    }

    @Test fun thePlanTimeZoneDecidesTheDay() {
        // 22:00 in Shanghai is 14:00 in London: a 15:00 London plan is still today there.
        assertEquals("下次 15:00 伦敦", nextPlanText(listOf(plan("伦敦", 15 * 60)), null, "Europe/London", now))
    }
}
