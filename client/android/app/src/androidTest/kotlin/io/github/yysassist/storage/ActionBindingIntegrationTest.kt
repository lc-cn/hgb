package io.github.yysassist.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.RuntimeCommandResult
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real save/admission/SQLite/runtime boundaries, using wait actions only. No main App DB or input. */
@RunWith(AndroidJUnit4::class)
class ActionBindingIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory = BuiltInStepWorkFactory()
    private val codec = BundleCodec(factory.contracts)
    private val address = TaskAddress("utility", "wait")
    private val identity = ExecutionContext(address.targetId, Principal.Anonymous)
    private lateinit var name: String
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private val settings = mutableMapOf<Pair<String, String>, JsonObject>()
    private fun admission() = TaskAdmissionRepository(database, library) { t, k -> settings[t to k] }
    private lateinit var store: ExecutionStateStore
    private var scope: CoroutineScope? = null
    private val task = TaskDefinition(address.taskId, address.targetId, "绑定等待", "tools", 1,
        fields = listOf(ConfigurationField("milliseconds", "等待时长", FieldWidget.NUMBER, true)),
        steps = listOf(StepDefinition("step", "等待阶段", listOf(
            ActionDefinition("first", "control.wait", 1, JsonObject(emptyMap()),
                mapOf("durationMs" to ActionInputBinding.Configuration("milliseconds"))),
            ActionDefinition("second", "control.wait", 1, JsonObject(emptyMap()),
                mapOf("durationMs" to ActionInputBinding.Output("first", "durationMs")))))))
    @Before fun open(): Unit = runBlocking {
        name = "action-bindings-${UUID.randomUUID()}.db"; database = AssistDatabase.open(context, name)
        library = TaskVersionLibrary(database, codec); store = ExecutionStateStore(database, library)
        val target = TargetProfile(address.targetId, "自有等待夹具", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val text = codec.write(TaskBundle(1, "bound-v1", "2026-11-16T00:00:00Z", "引用夹具", listOf(task), listOf(target)))
        assertEquals(VersionChange.Applied, library.install(text, "bound-v1", TaskVersionLibrary.sha256(text)))
        assertEquals(VersionChange.Applied, library.activate("bound-v1"))
    }
    @After fun close(): Unit = runBlocking {
        scope?.let { it.cancel(); it.coroutineContext[Job]!!.join() }
        database.close(); context.deleteDatabase(name)
    }
    private fun values(number: Int) = buildJsonObject { put("milliseconds", number) }
    private fun save(number: Int) { settings[address.targetId to address.taskId] = values(number) }

    @Test fun schemaValidButActionInvalidValuesAreRejectedWhileValidValuesAreAdmitted(): Unit = runBlocking {
        assertTrue(validateConfiguration(task, values(-1)).isEmpty())
        assertEquals("milliseconds", library.configurationIssues(task, values(-1)).single().location)
        save(12)
        val draft = admission().task(address)
        assertEquals(values(12), Json.parseToJsonElement(draft.tasks.single().configurationJson))
        val decimal = JsonObject(mapOf("milliseconds" to Json.parseToJsonElement("12.00")))
        settings[address.targetId to address.taskId] = decimal
        assertEquals(decimal, Json.parseToJsonElement(admission().task(address).tasks.single().configurationJson))
    }

    @Test fun corruptedSavedArgumentsCannotBypassAdmissionOrTheStateTransaction(): Unit = runBlocking {
        save(-1)
        try { admission().task(address); fail("Invalid action arguments admitted") }
        catch (_: ExecutionStateRejected) { }
        val engine = store.load().decode()
        val verified = engine.prepare(ExecutorCommand.VerifyContext(identity))
        store.persist(store.load().revision, verified); engine.commit(verified)
        val before = store.load()
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), identity, "bound-v1",
            listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, values(-1).toString())))
        val prepared = engine.prepare(ExecutorCommand.Enqueue("bypass-fixture", request))
        try { store.persist(before.revision, prepared); fail("Invalid action arguments committed") }
        catch (_: ExecutionStateRejected) { }
        assertEquals(before, store.load()); assertEquals(0, database.taskVersions().referenceCount())
    }

    @Test fun actualRuntimeUsesTheAdmittedConfigurationAfterTheUserSavesDifferentValues(): Unit = runBlocking {
        save(120)
        val entered = CompletableDeferred<StepAttempt>(); val release = CompletableDeferred<Unit>()
        val port = object : StepWorkFactory {
            override val contracts = factory.contracts
            override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle) = factory.restartHasNoDeviceInput(attempt, bundle)
            override fun canStartWithoutObservation(request: ExecutionRequest, bundle: TaskBundle) = factory.canStartWithoutObservation(request, bundle)
            override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
                val actual = factory.create(attempt, bundle)
                return object : StepWork {
                    override suspend fun execute(): StepOutcome { entered.complete(attempt); release.await(); return actual.execute() }
                    override suspend fun revoke() = actual.revoke()
                }
            }
        }
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val runtime = ExecutionRuntime(store, library, admission(), port, runtimeScope)
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
        try {
            assertTrue(runtime.enqueueTask(address).await() is RuntimeCommandResult.Applied)
            val attempt = withTimeout(5_000) { entered.await() }
            save(0)
            assertEquals(values(120), Json.parseToJsonElement(attempt.run.task.configurationJson))
            assertEquals(values(120), Json.parseToJsonElement(store.load().decode().state.value.current!!.task.configurationJson))
            release.complete(Unit)
            val finished = withTimeout(5_000) { runtime.state.first { state -> state.facts.any { it.type == "task-completed" } } }
            assertNull(finished.current); assertTrue(finished.unresolved.isEmpty())
            assertFalse(finished.facts.any { it.type == "task-failed" })
            assertEquals(0, library.snapshot().versions.single().referenceCount)
        } finally { release.complete(Unit) }
    }
}
