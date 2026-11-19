package io.github.yysassist.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real private files + generated Room DAO/SQLite, with injected transfers; no native recognition or game input. */
@RunWith(AndroidJUnit4::class)
class TaskResourceIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var root: File
    private lateinit var database: AssistDatabase
    private lateinit var files: TaskResourceFiles
    private lateinit var library: TaskVersionLibrary
    private val codec = BundleCodec(listOf(CapabilityContract("test.observe", 1) { null }))
    private val first = resource("det", "det.onnx", "model-one")
    private val second = resource("dictionary", "keys.txt", "字典")
    private fun resource(id: String, asset: String, content: String) = TaskResource(id, asset, content.toByteArray().size.toLong(), TaskVersionLibrary.sha256(content))
    private fun text(version: String, resources: List<TaskResource> = listOf(first)) = codec.write(TaskBundle(
        1, version, "2026-11-16T00:00:00Z", "资源测试", listOf(TaskDefinition("inspect", "utility", "观察", "tools", 10,
            steps = listOf(StepDefinition("look", "观察", listOf(ActionDefinition("screen", "test.observe", 1, JsonObject(emptyMap()))))))),
        listOf(TargetProfile("utility", "测试应用", listOf("test.utility"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))), resources))
    @Before fun open() {
        name = "resources-${UUID.randomUUID()}.db"
        root = File(context.noBackupFilesDir, name)
        reopen()
    }
    private fun reopen() {
        database = AssistDatabase.open(context, name)
        files = TaskResourceFiles(root)
        library = TaskVersionLibrary(database, codec, files)
    }
    @After fun close() { database.close(); context.deleteDatabase(name); assertTrue(root.deleteRecursively()) }
    private suspend fun install(version: String, resources: List<TaskResource> = listOf(first)): VersionChange {
        val value = text(version, resources)
        return library.install(value, version, TaskVersionLibrary.sha256(value)) { resource, sink ->
            sink.write((if (resource.sha256 == first.sha256) "model-one" else "字典").toByteArray())
        }
    }
    private fun rejected(code: VersionRejection, result: VersionChange) {
        assertTrue(result.toString(), result is VersionChange.Rejected)
        assertEquals(code, (result as VersionChange.Rejected).code)
    }

    @Test fun allResourcesAreRequiredBeforeOneReadyVersionIsCommitted() = runBlocking {
        assertEquals(VersionChange.Applied, install("old")); library.activate("old")
        val value = text("new", listOf(first, second))
        rejected(VersionRejection.INVALID_CONTENT, library.install(value, "new", TaskVersionLibrary.sha256(value)))
        rejected(VersionRejection.INVALID_CONTENT, library.install(value, "new", TaskVersionLibrary.sha256(value)) { resource, sink ->
            sink.write((if (resource.id == first.id) "model-one" else "bad").toByteArray())
        })
        assertNull(database.taskVersions().version("new"))
        assertEquals("old", library.activeCatalog()!!.version)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
        assertEquals(VersionChange.Applied, install("new", listOf(first, second)))
        assertEquals("old", library.snapshot().activeVersion)
        database.close(); reopen()
        library.acquireResources("new").use { lease ->
            assertEquals("model-one", lease.file(first.id).readText())
            assertEquals("字典", lease.file(second.id).readText())
            assertEquals(TaskVersionLibrary.sha256(value), lease.bundleDigest)
        }
    }

    @Test fun cancelledAndFailedTransfersDoNotPublishOrRetainPartialFiles() = runBlocking {
        val value = text("one")
        val writing = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            library.install(value, "one", TaskVersionLibrary.sha256(value)) { _, sink ->
                sink.write("mod".toByteArray()); writing.complete(Unit); awaitCancellation()
            }
        }
        withTimeout(5_000) { writing.await() }; job.cancelAndJoin()
        assertNull(database.taskVersions().version("one"))
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
        try {
            library.install(value, "one", TaskVersionLibrary.sha256(value)) { _, sink ->
                sink.write("mod".toByteArray()); throw IOException("fixture disk/transport failure")
            }
            fail("I/O failure became success")
        } catch (expected: IOException) { assertEquals("fixture disk/transport failure", expected.message) }
        assertNull(database.taskVersions().version("one"))
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun leasesOutliveQueueReferencesAndPreventEvictionUntilAllUsersClose() = runBlocking {
        install("old"); install("current", emptyList()); library.activate("current")
        library.retain("old", "run", VersionReferenceReason.RUNNING)
        val one = library.acquireResources("old")
        val two = library.acquireResources("old")
        try {
            library.release("run")
            rejected(VersionRejection.IN_USE, library.delete("old"))
            assertEquals(2, library.snapshot().versions.single { it.version == "old" }.referenceCount)
            rejected(VersionRejection.IN_USE, library.activate("old"))
            one.close(); one.close()
            assertEquals(1, library.snapshot().versions.single { it.version == "old" }.referenceCount)
            rejected(VersionRejection.IN_USE, library.delete("old"))
            assertEquals("model-one", two.file(first.id).readText())
            two.close()
            try { two.file(first.id); fail("closed lease exposed a path") } catch (_: IllegalStateException) { }
            assertEquals(VersionChange.Applied, library.delete("old"))
            assertFalse(File(root, "blobs/${first.sha256}").exists())
        } finally { one.close(); two.close() }
    }

    @Test fun sharedDigestsRemainUntilTheirLastCachedVersionIsDeleted() = runBlocking {
        install("one"); install("two"); install("current", emptyList()); library.activate("current")
        assertEquals(1, File(root, "blobs").listFiles()!!.size)
        assertEquals(VersionChange.Applied, library.delete("one"))
        val path = library.acquireResources("two").use { lease ->
            assertEquals("model-one", lease.file(first.id).readText()); lease.file(first.id)
        }
        assertTrue(path.exists())
        assertEquals(VersionChange.Applied, library.delete("two")); assertFalse(path.exists())
        // Tombstone still prevents replacing the deleted version's immutable JSON identity.
        val changed = text("one", listOf(second))
        rejected(VersionRejection.IMMUTABLE_CONFLICT, library.install(changed, "one", TaskVersionLibrary.sha256(changed)))
        assertEquals(VersionChange.Applied, install("one"))
    }

    @Test fun deletingAndAcquiringRaceWithoutExposingAnEvictedPath() = runBlocking {
        install("old"); install("current", emptyList()); library.activate("current")
        val lease = async(Dispatchers.IO) { runCatching { library.acquireResources("old") } }
        val deletion = async(Dispatchers.IO) { library.delete("old") }
        val acquired = lease.await()
        if (acquired.isSuccess) acquired.getOrThrow().use {
            rejected(VersionRejection.IN_USE, deletion.await())
            assertEquals("model-one", it.file(first.id).readText())
        } else {
            assertTrue(acquired.exceptionOrNull() is InvalidLocalTaskVersion)
            assertEquals(VersionChange.Applied, deletion.await())
        }
    }

    @Test fun byteCorruptionAndMissingFilesCannotActivatePinOrAcquireEvenAfterReopen() = runBlocking {
        install("one"); install("current", emptyList()); library.activate("current")
        val path = File(root, "blobs/${first.sha256}")
        assertTrue(path.setWritable(true)); path.writeText("model-two") // Same length, different digest.
        rejected(VersionRejection.INVALID_CONTENT, library.activate("one"))
        assertEquals(VersionRejection.INVALID_CONTENT, (library.retain("one", "run", VersionReferenceReason.QUEUED) as PinVersionResult.Rejected).reason.code)
        try { library.acquireResources("one"); fail("corrupt model leased") } catch (_: InvalidTaskResources) { }
        assertEquals(0, database.taskVersions().referenceCount())
        assertTrue(path.delete())
        database.close(); reopen()
        rejected(VersionRejection.INVALID_CONTENT, library.activate("one"))
        assertEquals("current", library.activeCatalog()!!.version)
        assertEquals("one", database.taskVersions().version("one")!!.version) // Original evidence retained.
    }

    @Test fun failedDatabaseCommitsLeaveOnlySafeOrphansAndFailedEvictionRetainsReferencedBytes() = runBlocking {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_install BEFORE INSERT ON task_versions BEGIN SELECT RAISE(ABORT, 'fixture commit rejection'); END")
        try { install("one"); fail("transaction unexpectedly committed") } catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(database.taskVersions().version("one"))
        assertTrue(File(root, "blobs/${first.sha256}").exists())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fixture_install")
        assertEquals(VersionChange.Applied, install("one"))
        install("current", emptyList()); library.activate("current")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_delete BEFORE UPDATE OF cached ON task_versions BEGIN SELECT RAISE(ABORT, 'fixture eviction rejection'); END")
        try { library.delete("one"); fail("eviction unexpectedly committed") } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(database.taskVersions().version("one")!!.cached)
        library.acquireResources("one").use { assertEquals("model-one", it.file(first.id).readText()) }
    }

    @Test fun executionAdmissionAndUnknownReferencesUseTheSameResourceVersionOwner() = runBlocking {
        install("one"); library.activate("one")
        val store = ExecutionStateStore(database, library)
        var loaded = store.load()
        val executor = loaded.decode()
        val identity = ExecutionContext("utility", Principal.Anonymous)
        val verified = executor.prepare(ExecutorCommand.VerifyContext(identity))
        store.persist(loaded.revision, verified); executor.commit(verified)
        val candidate = TaskAdmissionRepository(database, library).task(TaskAddress("utility", "inspect"))
        loaded = store.load()
        val enqueued = executor.prepare(ExecutorCommand.Enqueue("fixture", candidate.request(identity)))
        store.persist(loaded.revision, enqueued); executor.commit(enqueued)
        val ticket = (enqueued.effects.single() as ExecutorEffect.ExecuteStep).attempt.ticket
        val unknown = executor.prepare(ExecutorCommand.StepFinished(ticket, StepOutcome.Unknown("fixture unknown")))
        store.persist(store.load().revision, unknown); executor.commit(unknown)
        assertEquals("UNRESOLVED", database.taskVersions().references().single().reason)
        assertEquals(first, library.readVersion("one").resources.single())
        library.acquireResources("one").use { assertEquals("model-one", it.file(first.id).readText()) }
        install("two", emptyList())
        rejected(VersionRejection.IN_USE, library.activate("two"))
        assertEquals(1, database.taskVersions().referenceCount())
    }

    @Test fun catalogObservesLeaseReleaseWithoutRequiringAnUnrelatedDatabaseWrite() = runBlocking {
        install("one")
        val lease = library.acquireResources("one")
        assertEquals(1, withTimeout(5_000) { library.catalog.first { it.versions.single().referenceCount == 1 } }.versions.single().referenceCount)
        lease.close()
        assertEquals(0, withTimeout(5_000) { library.catalog.first { it.versions.single().referenceCount == 0 } }.versions.single().referenceCount)
    }

    @Test fun aliasesShareOneImmutableBlobAndCachedPullDoesNotDownloadAgain() = runBlocking {
        val alias = first.copy(id = "other-target.det", assetName = "alias.onnx")
        val resources = listOf(first, alias)
        assertEquals(VersionChange.Applied, install("one", resources))
        assertEquals(1, File(root, "blobs").listFiles()!!.size)
        library.acquireResources("one").use { assertEquals(it.file(first.id), it.file(alias.id)) }
        val value = text("one", resources)
        assertEquals(VersionChange.Unchanged, library.install(value, "one", TaskVersionLibrary.sha256(value)) { _, _ ->
            fail("A complete cached version downloaded its resources again")
        })
    }

    @Test fun modelSizedResourceStreamsToDiskAndSurvivesReopenWithoutEmbeddingItsBytesInSqlite() = runBlocking {
        val block = ByteArray(8192) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256")
        repeat(4096) { hash.update(block) }
        val model = TaskResource("model", "model.onnx", 32L * 1024 * 1024, hash.digest().joinToString("") { "%02x".format(it) })
        val value = text("large", listOf(model))
        assertEquals(VersionChange.Applied, library.install(value, "large", TaskVersionLibrary.sha256(value)) { _, sink ->
            repeat(4096) { sink.write(block) }
        })
        assertEquals(value, database.taskVersions().version("large")!!.json)
        assertTrue(value.toByteArray().size < 2048)
        database.close(); reopen()
        library.acquireResources("large").use { assertEquals(model.bytes, it.file(model.id).length()) }
        install("current", emptyList()); library.activate("current"); library.delete("large")
        assertTrue(File(root, "blobs").listFiles()!!.isEmpty())
    }

    @Test fun cancellationBeforeLeaseDeliveryReleasesTheUndeliveredFileUse() = runBlocking {
        install("one")
        val transactionEntered = CompletableDeferred<Unit>()
        val allowTransaction = CompletableDeferred<Unit>()
        val holding = launch(Dispatchers.IO) {
            database.withTransaction { transactionEntered.complete(Unit); allowTransaction.await() }
        }
        withTimeout(5_000) { transactionEntered.await() }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val callerStarted = CompletableDeferred<Unit>()
        val callerBlocked = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val caller = launch(dispatcher) {
            callerStarted.complete(Unit)
            library.acquireResources("one").use { delivered.set(true) }
        }
        try {
            withTimeout(5_000) { callerStarted.await() }
            executor.execute { callerBlocked.countDown(); check(releaseCaller.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.IO) { assertTrue(callerBlocked.await(5, TimeUnit.SECONDS)) }
            allowTransaction.complete(Unit); holding.join()
            withTimeout(5_000) { while (files.leaseCount("one") != 1) delay(10) }
            caller.cancel()
            assertFalse(delivered.get())
        } finally {
            allowTransaction.complete(Unit); holding.cancelAndJoin()
            releaseCaller.countDown(); caller.cancelAndJoin(); dispatcher.close()
        }
        assertFalse(delivered.get())
        assertEquals("Undelivered lease must not keep a version permanently in use", 0, files.leaseCount("one"))
        assertEquals(VersionChange.Applied, library.delete("one"))
    }
}
