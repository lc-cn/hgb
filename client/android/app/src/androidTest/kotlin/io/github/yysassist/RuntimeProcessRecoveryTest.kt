package io.github.yysassist

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Two explicitly selected instrumentation runs separated by Android force-stop; dedicated QA DB only. */
@RunWith(AndroidJUnit4::class)
class RuntimeProcessRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "qa-process-recovery.db"
    private val factory = BuiltInStepWorkFactory()
    private val programMode get() = InstrumentationRegistry.getArguments().getString("runtimeProgram") == "true"
    private fun phase(expected: String) {
        Assume.assumeTrue("Explicit emulator process-QA only", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("runtimeProcessPhase") == expected)
    }
    private fun fixture(): TaskBundle {
        fun step(id: String, duration: Long): StepDefinition {
            val action = ActionDefinition(id, "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(duration))))
            if (!programMode) return StepDefinition(id, id, listOf(action))
            return StepDefinition(id, id, program = listOf(
                ProgramNode.Action(action.copy(id = "$id-select", inputs = buildJsonObject { put("durationMs", 0) })),
                ProgramNode.Branch(ActionInputBinding.Output("$id-select", "durationMs"), listOf(ProgramCase(JsonPrimitive(0),
                    listOf(ProgramNode.Repeat(JsonPrimitive(2), listOf(ProgramNode.Action(action))))))),
            ))
        }
        return TaskBundle(1, "qa-process-v1", "2026-11-16T00:00:00Z", "Process QA", listOf(
            TaskDefinition("one", "qa-process", "进程测试一", "tools", 1, steps = listOf(step("first", 1), step("second", 60_000))),
            TaskDefinition("two", "qa-process", "进程测试二", "tools", 1, steps = listOf(step("second", 60_000)))),
            listOf(TargetProfile("qa-process", "进程测试", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))))
    }
    private fun childScope(): CoroutineScope = CoroutineScope(SupervisorJob((context.applicationContext as AssistApplication).scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    @Test fun seedBeforeExternalProcessStop(): Unit = runBlocking {
        phase("seed")
        assertFalse("Do not replace an earlier or unrelated QA database", context.getDatabasePath(dbName).exists())
        val database = AssistDatabase.open(context, dbName)
        val library = TaskVersionLibrary(database, BundleCodec(factory.contracts))
        val content = BundleCodec(factory.contracts).write(fixture())
        library.install(content, "qa-process-v1", TaskVersionLibrary.sha256(content)); library.activate("qa-process-v1")
        val runtime = ExecutionRuntime(ExecutionStateStore(database, library), library, TaskAdmissionRepository(database, library) { _, _ -> null }, factory, childScope())
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
        runtime.enqueueTask(TaskAddress("qa-process", "one")).await()
        withTimeout(5_000) { runtime.state.first { it.current?.stepIndex == 1 && it.phase == ExecutorPhase.RUNNING } }
        runtime.enqueueTask(TaskAddress("qa-process", "two")).await()
        val state = withTimeout(5_000) { runtime.state.first { it.waiting.size == 1 } }
        context.filesDir.resolve("qa-process-seed.json").writeText(JsonObject(mapOf(
            "pid" to JsonPrimitive(Process.myPid()), "runId" to JsonPrimitive(state.current!!.runId),
            "programMode" to JsonPrimitive(programMode),
            "generation" to JsonPrimitive(state.activeTicket!!.generation))).toString())
        // Deliberately no graceful shutdown: the runner/ADB terminates this Android process next.
    }
    @Test fun verifyAfterExternalProcessStop(): Unit = runBlocking {
        phase("verify")
        val seed = Json.parseToJsonElement(context.filesDir.resolve("qa-process-seed.json").readText()).jsonObject
        assertEquals(programMode, seed.getValue("programMode").jsonPrimitive.boolean)
        assertNotEquals(seed.getValue("pid").jsonPrimitive.int, Process.myPid())
        val database = AssistDatabase.open(context, dbName)
        val library = TaskVersionLibrary(database, BundleCodec(factory.contracts))
        val store = ExecutionStateStore(database, library)
        val before = store.load().decode().state.value
        assertEquals(seed.getValue("generation").jsonPrimitive.long, before.activeTicket!!.generation)
        val scope = childScope()
        try {
            val runtime = ExecutionRuntime(store, library, TaskAdmissionRepository(database, library) { _, _ -> null }, factory, scope)
            val state = withTimeout(5_000) { runtime.state.first { it.activeTicket != null && it.activeTicket != before.activeTicket } }
            assertEquals(seed.getValue("runId").jsonPrimitive.long, state.current!!.runId)
            assertEquals(1, state.current!!.stepIndex)
            assertEquals(1, state.waiting.size)
            assertEquals(1, state.facts.count { it.type == "step-completed" && it.runId == state.current!!.runId })
            context.filesDir.resolve("qa-process-result.json").writeText(JsonObject(mapOf(
                "oldPid" to seed.getValue("pid"), "newPid" to JsonPrimitive(Process.myPid()),
                "oldGeneration" to seed.getValue("generation"), "newGeneration" to JsonPrimitive(state.activeTicket!!.generation),
                "completedStepPreserved" to JsonPrimitive(true), "queuePreserved" to JsonPrimitive(true),
                "programMode" to JsonPrimitive(programMode),
                "scope" to JsonPrimitive("Actual Android process stop; production runtime class and built-in no-input runner on dedicated fixture DB. No game or main application DB."))).toString())
            runtime.submit(ExecutorCommand.Pause).await()
            withTimeout(5_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED } }
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close()
        }
        context.deleteDatabase(dbName)
        context.filesDir.resolve("qa-process-seed.json").delete()
    }
}
