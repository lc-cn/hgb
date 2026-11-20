package io.github.yysassist.shared.console

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleDataTest {
    private fun device(id: String, name: String) =
        """{"id":"$id","name":"$name","platform":"android","online":true,"lastSeenAt":null,"phase":null,"currentTask":null,"queued":0,"tasks":[]}"""

    @Test fun aSnapshotReplacesEverythingAndLaterEventsChangeOnlyTheirPart() {
        val start = ConsoleData(logVersion = mapOf("old" to 3))
        val snapshot = start.apply("snapshot", """{"devices":[${device("b", "乙")},${device("a", "甲")}],"jobs":[],"timeZone":"Asia/Tokyo"}""")
        // By name as Chinese sorts (pinyin jia before yi), as the web console lists them.
        assertEquals(listOf("甲", "乙"), snapshot.devices.map { it.name })
        assertEquals("Asia/Tokyo", snapshot.timeZone)
        assertEquals(emptyMap(), snapshot.logVersion)
        assertEquals(1, snapshot.epoch)

        val renamed = snapshot.apply("device", device("a", "丙"))
        assertEquals(setOf("乙", "丙"), renamed.devices.map { it.name }.toSet())
        assertEquals(1, renamed.apply("device-removed", """{"id":"a"}""").devices.size)
        assertEquals(mapOf("j1" to 2), renamed.apply("job-log", """{"id":"j1"}""").apply("job-log", """{"id":"j1"}""").logVersion)
        assertEquals(renamed.noticeVersion + 1, renamed.apply("delivery", "{}").noticeVersion)
    }

    @Test fun anEventThatDoesNotParseOrIsUnknownChangesNothing() {
        val data = ConsoleData().apply("snapshot", """{"devices":[${device("a", "甲")}],"jobs":[]}""")
        assertEquals(data, data.apply("device", "{not json"))
        assertEquals(data, data.apply("something-new", "{}"))
    }
}
