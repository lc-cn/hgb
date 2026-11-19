package io.github.yysassist.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Actual generated DAO + on-disk Android SQLite, not a fake storage implementation. */
@RunWith(AndroidJUnit4::class)
class TaskVersionLibraryTest {
    private lateinit var context: Context
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var name: String
    private val codec = BundleCodec(listOf(CapabilityContract("test.observe", 1) { null }))
    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "version-test-${UUID.randomUUID()}.db"
        reopen()
    }
    private fun reopen() {
        database = Room.databaseBuilder(context, AssistDatabase::class.java, name).build()
        library = TaskVersionLibrary(database, codec)
    }
    @After fun close() { database.close(); context.deleteDatabase(name) }
    private fun text(version: String, changelog: String = "更新说明") = codec.write(TaskBundle(
        1, version, "2026-11-16T00:00:00Z", changelog,
        listOf(TaskDefinition("same-id", "utility", "测试任务", "tools", 10,
            steps = listOf(StepDefinition("first", "观察", listOf(ActionDefinition("screen", "test.observe", 1, JsonObject(emptyMap()))))))),
        listOf(TargetProfile("utility", "测试应用", listOf("test.utility"), IdentityPolicy.NONE,
            listOf(TaskCategory("tools", "工具")))),
    ))
    private suspend fun install(version: String): String {
        val value = text(version)
        assertEquals(VersionChange.Applied, library.install(value, version, TaskVersionLibrary.sha256(value)))
        return value
    }
    private fun rejected(code: VersionRejection, result: VersionChange) {
        assertTrue(result.toString(), result is VersionChange.Rejected)
        assertEquals(code, (result as VersionChange.Rejected).code)
    }

    @Test fun pullDoesNotActivateAndSelectionSurvivesDatabaseReopen() = runBlocking {
        install("z-old"); library.activate("z-old")
        install("a-new"); library.observeLatest("a-new")
        assertEquals("z-old", library.snapshot().activeVersion)
        assertTrue(library.snapshot().hasKnownUpdate) // latest is explicit, not sorted by version text.
        database.close(); reopen()
        assertEquals("z-old", library.snapshot().activeVersion)
        assertEquals("a-new", library.snapshot().latestVersion)
        assertEquals(2, library.snapshot().versions.size)
        assertEquals(VersionChange.Applied, library.activate("a-new"))
        assertFalse(library.snapshot().hasKnownUpdate)
        assertEquals(VersionChange.Applied, library.activate("z-old"))
        assertEquals("z-old", library.activeCatalog()!!.version)
    }

    @Test fun partialInvalidAndConflictingDownloadsPreserveTheUsableVersion() = runBlocking {
        val original = install("one"); library.activate("one")
        assertEquals(VersionChange.Unchanged, library.install(original, "one", TaskVersionLibrary.sha256(original)))
        val changed = text("one", "同名内容被替换")
        rejected(VersionRejection.IMMUTABLE_CONFLICT, library.install(changed, "one", TaskVersionLibrary.sha256(changed)))
        val damaged = text("two").dropLast(1)
        rejected(VersionRejection.INVALID_CONTENT, library.install(damaged, "two", TaskVersionLibrary.sha256(damaged)))
        rejected(VersionRejection.INVALID_CONTENT, library.install(text("two"), "two", "0".repeat(64)))
        assertEquals(listOf("one"), library.snapshot().versions.map { it.version })
        assertEquals("one", library.activeCatalog()!!.version)
    }

    @Test fun referencesSurviveReopenAndProtectPausedAndUnknownContent() = runBlocking {
        install("one"); install("two"); library.activate("one")
        val pin = library.pinActive("run-1") as PinVersionResult.Pinned
        assertEquals("one", pin.content.bundle.version)
        library.retain("one", "run-1", VersionReferenceReason.UNRESOLVED)
        database.close(); reopen()
        rejected(VersionRejection.IN_USE, library.activate("two"))
        rejected(VersionRejection.ACTIVE, library.delete("one"))
        assertEquals(1, library.snapshot().versions.single { it.version == "one" }.referenceCount)
        assertEquals("one", (library.pinActive("run-1") as PinVersionResult.Pinned).content.bundle.version)
        val conflict = library.retain("two", "run-1", VersionReferenceReason.PAUSED) as PinVersionResult.Rejected
        assertEquals(VersionRejection.OWNER_CONFLICT, conflict.reason.code)
        library.release("run-1"); library.activate("two")
        assertEquals(VersionChange.Applied, library.delete("one"))
        assertEquals("two", library.snapshot().activeVersion)
    }

    @Test fun historicalVersionDeletionRacesWithRetentionWithoutDanglingReference() = runBlocking {
        install("old"); install("current"); library.activate("current")
        val operations = awaitAll(
            async(Dispatchers.Default) { library.delete("old") },
            async(Dispatchers.Default) { library.retain("old", "recovery", VersionReferenceReason.UNRESOLVED) },
        )
        val reference = database.taskVersions().reference("recovery")
        if (reference != null) {
            assertNotNull(database.taskVersions().version(reference.version))
            rejected(VersionRejection.IN_USE, operations[0] as VersionChange)
        } else {
            assertEquals(VersionChange.Applied, operations[0])
            assertEquals(VersionRejection.MISSING, (operations[1] as PinVersionResult.Rejected).reason.code)
        }
    }

    @Test fun damagedStoredContentCannotBeReadOrPinned() = runBlocking {
        install("one"); library.activate("one")
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("UPDATE task_versions SET json = '{}' WHERE version = 'one'")
        }
        val pin = library.pinActive("run") as PinVersionResult.Rejected
        assertEquals(VersionRejection.INVALID_CONTENT, pin.reason.code)
        assertEquals(0, database.taskVersions().referenceCount())
        try { library.activeCatalog(); fail("Corruption cannot look like an empty catalog") }
        catch (_: InvalidLocalTaskVersion) { }
    }

    @Test fun deletionClearsContentButPreservesItsImmutableIdentityForReacquisition() = runBlocking {
        val original = install("old"); install("current"); library.activate("current")
        assertEquals(VersionChange.Applied, library.delete("old"))
        val metadata = database.taskVersions().version("old")!!
        assertFalse(metadata.cached)
        assertEquals("", metadata.json)
        assertEquals(TaskVersionLibrary.sha256(original), metadata.digest)
        assertFalse(library.snapshot().versions.any { it.version == "old" })
        val changed = text("old", "被替换的内容")
        rejected(VersionRejection.IMMUTABLE_CONFLICT, library.install(changed, "old", TaskVersionLibrary.sha256(changed)))
        assertEquals(VersionChange.Applied, library.install(original, "old", TaskVersionLibrary.sha256(original)))
        assertTrue(database.taskVersions().version("old")!!.cached)
        assertEquals(original, database.taskVersions().version("old")!!.json)
    }

    @Test fun unresolvedIdentityKeepsTheDatabaseForeignKeyProtectionAfterContentEviction() = runBlocking {
        install("old"); install("current"); library.activate("current"); library.delete("old")
        assertEquals(VersionChange.Applied, library.retainUnresolved("old", "late-result"))
        try { database.taskVersions().deleteVersion("old"); fail("foreign key protection removed") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertNotNull(database.taskVersions().version("old"))
        assertEquals("UNRESOLVED", database.taskVersions().reference("late-result")!!.reason)
        assertFalse(library.snapshot().versions.any { it.version == "old" })
        assertEquals(VersionRejection.MISSING, (library.retain("old", "worker", VersionReferenceReason.RUNNING) as PinVersionResult.Rejected).reason.code)
    }

    @Test fun transactionFailureDoesNotRegisterHalfAVersion() = runBlocking {
        try {
            database.withTransaction {
                database.taskVersions().insertVersion(TaskVersionEntity("half", "digest", "{}", "date", "note", 1))
                error("simulated interruption before transaction commit")
            }
        } catch (_: IllegalStateException) { }
        database.close(); reopen()
        assertTrue(library.snapshot().versions.isEmpty())
        assertNull(library.snapshot().activeVersion)
    }

    @Test fun observedCatalogReadsSelectionAndRowsFromOneTransaction() = runBlocking {
        val snapshots = mutableListOf<TaskVersionCatalog>()
        val ready = CompletableDeferred<Unit>()
        val final = CompletableDeferred<Unit>()
        val observer = launch(Dispatchers.Default) {
            library.catalog.collect {
                snapshots += it
                ready.complete(Unit)
                if (it.activeVersion == "batch-9") final.complete(Unit)
            }
        }
        try {
            withTimeout(10_000) {
                ready.await()
                repeat(10) { index ->
                    val version = "batch-$index"
                    val value = text(version)
                    database.withTransaction {
                        database.taskVersions().insertVersion(TaskVersionEntity(version, TaskVersionLibrary.sha256(value), value,
                            "2026-11-16T00:00:00Z", "事务内更新", index.toLong()))
                        database.taskVersions().saveSelection(TaskVersionSelection(activeVersion = version))
                    }
                }
                final.await()
            }
        } finally { observer.cancelAndJoin() }
        assertTrue(snapshots.size >= 2)
        snapshots.forEach { snapshot ->
            assertTrue("Mixed catalog: $snapshot", snapshot.activeVersion == null || snapshot.versions.any { it.version == snapshot.activeVersion })
        }
    }
}
