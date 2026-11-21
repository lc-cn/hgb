package io.github.yysassist.server

import kotlinx.serialization.json.*
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A draft whose base was never recorded gets one only when its tasks and snippets are still as imported. */
class DraftBasesTest {
    private val file = File.createTempFile("bases", ".db")
    private val db = Database(file.path)
    private val drafts = Drafts(db, { 1L })
    @After fun close() { db.close(); file.delete() }

    private val body = Json.parseToJsonElement("""{"tasks":[{"id":"t","name":"T","steps":[{"id":"s","name":"S","program":[]}]}],
        "snippets":[{"id":"sn","program":[]}],"resources":[{"id":"r","assetName":"r.png","bytes":1,"sha256":"a"}]}""").jsonObject

    private fun forgetBases() = db.tx { update("DELETE FROM dev_draft_bases") }
    private fun start() {
        db.tx { update("INSERT INTO users (id, email, created_at) VALUES ('u', 'u@example.test', 0)") }
        drafts.import("u", "game", DraftImport("v1", body))
    }

    @Test fun onlyResourcesChangedKeepsTheImportAsBase() {
        start()
        val r = body.getValue("resources").jsonArray[0]
        drafts.patch("u", "game", PatchBatch(listOf(DraftPatch("put", "resources:r", JsonObject(r.jsonObject + ("bytes" to JsonPrimitive(2))), expect = nodeDigest(r)))))
        forgetBases()
        val bases = drafts.get("u", "game").bases
        assertEquals(nodeDigest(body.getValue("tasks").jsonArray[0]), bases["tasks/t"])
        assertEquals(nodeDigest(body.getValue("snippets").jsonArray[0]), bases["snippets/sn"])
    }

    @Test fun aChangedTaskLeavesTheBaseUnknown() {
        start()
        val task = body.getValue("tasks").jsonArray[0].jsonObject
        drafts.patch("u", "game", PatchBatch(listOf(DraftPatch("set", "t", JsonObject(mapOf("name" to JsonPrimitive("改"))), expect = shallowDigest(task)))))
        forgetBases()
        assertTrue(drafts.get("u", "game").bases.isEmpty())
    }
}
