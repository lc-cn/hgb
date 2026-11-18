package io.github.yysassist.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.execution.ActionTraceRecord
import io.github.yysassist.core.execution.AttemptTicket
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActionTraceLogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "trace-${UUID.randomUUID()}.db"
    @After fun clean() { context.deleteDatabase(name) }
    private fun record(i: Int, run: Long = 7) = ActionTraceRecord(AttemptTicket(run, 1), "task", "step", "a$i", "screen.tap", 1,
        "PRODUCED", null, buildJsonObject { put("seed", i) }, i.toLong())

    @Test fun tracesArePersistedInOrderAndPrunedToTheNewest(): Unit = runBlocking {
        val database = AssistDatabase.open(context, name)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val log = ActionTraceLog(database, scope, keep = 50) { 1_000L }
            repeat(450) { log.record(record(it)) }
            withTimeout(10_000) { while (database.actionTraces().recent(1).firstOrNull()?.actionId != "a449") delay(20) }
            val kept = database.actionTraces().forRun(7)
            assertEquals("a449", kept.last().actionId)
            assertEquals(kept.map { it.id }.sorted(), kept.map { it.id })
            assertEquals(JsonPrimitive(449), Json.parseToJsonElement(kept.last().traceJson!!).jsonObject["seed"])
            // Pruning runs every 200 writes; after 400 writes at most 50 + 49 older rows remain.
            assertTrue(kept.size.toString(), kept.size <= 50 + 49 + 1 && kept.size < 450)
        } finally { scope.cancel(); database.close() }
    }
}
