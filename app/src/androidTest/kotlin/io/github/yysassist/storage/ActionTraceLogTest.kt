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
    @Test fun schemaFourMigratesAndKeepsExistingTables(): Unit = runBlocking {
        val schema = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.yysassist.storage.AssistDatabase/4.json").bufferedReader().use { it.readText() }).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject; val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO executor_state VALUES (1, 3, '{}', 'digest')")
            old.version = 4
        }
        val database = AssistDatabase.open(context, name)
        try {
            assertEquals(3L, database.executorState().get()!!.revision)
            database.actionTraces().insert(ActionTraceEntity(runId = 1, generation = 1, taskId = "t", stepId = "s", actionId = "a",
                capability = "screen.tap", contractVersion = 1, result = "PRODUCED", reason = null, traceJson = null, elapsedMillis = 3, recordedAt = 4))
            assertEquals(1, database.actionTraces().forRun(1).size)
        } finally { database.close() }
    }
}
