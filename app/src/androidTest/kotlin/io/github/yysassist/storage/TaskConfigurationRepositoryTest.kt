package io.github.yysassist.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.TaskConfigurationEditor
import io.github.yysassist.TaskEditorState
import io.github.yysassist.core.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskConfigurationRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var repository: TaskConfigurationRepository
    private lateinit var name: String
    private val a = TaskAddress("app-a", "same-task")
    private val b = TaskAddress("app-b", "same-task")
    private val codec = BundleCodec(listOf(CapabilityContract("test.observe", 1) { null }))
    private val fields get() = listOf(ConfigurationField("amount", "数量", FieldWidget.NUMBER, true,
        minimum = JsonPrimitive(0), maximum = JsonPrimitive("9007199254741003".toBigDecimal()), step = JsonPrimitive(1)),
        ConfigurationField("note", "备注", FieldWidget.TEXT))
    private fun bundle(version: String, newFields: List<ConfigurationField> = fields, includeA: Boolean = true): TaskBundle {
        val targets = listOf("app-a", "app-b").map { TargetProfile(it, it, listOf("test.$it"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具"))) }
        val tasks = targets.filter { includeA || it.id != "app-a" }.map {
            TaskDefinition("same-task", it.id, "测试任务", "tools", 1, newFields,
                listOf(StepDefinition("inspect", "观察", listOf(ActionDefinition("screen", "test.observe", 1, JsonObject(emptyMap()))))))
        }
        return TaskBundle(1, version, "2026-11-16T00:00:00Z", "测试说明", tasks, targets)
    }
    private fun open() {
        database = AssistDatabase.open(context, name)
        library = TaskVersionLibrary(database, codec)
        repository = TaskConfigurationRepository(database, library)
    }
    @Before fun setup() { name = "configuration-${UUID.randomUUID()}.db"; open() }
    @After fun close() { database.close(); context.deleteDatabase(name) }
    private suspend fun install(bundle: TaskBundle) {
        val text = codec.write(bundle)
        assertEquals(VersionChange.Applied, library.install(text, bundle.version, TaskVersionLibrary.sha256(text)))
        assertEquals(VersionChange.Applied, library.activate(bundle.version))
    }
    private fun amount(value: String) = JsonObject(mapOf("amount" to JsonPrimitive(value)))

    @Test fun exactSavedValuesSurviveReopenAndAreIsolatedByTarget() = runBlocking {
        install(bundle("one"))
        val result = repository.save(repository.open(a), amount("9007199254740993.00")) as ConfigurationSaveResult.Saved
        assertEquals(1L, result.snapshot.revision)
        assertEquals("9007199254740993.00", result.snapshot.values["amount"]!!.jsonPrimitive.content)
        assertFalse(result.snapshot.values["amount"]!!.jsonPrimitive.isString)
        assertTrue(repository.open(b).values.isEmpty())
        database.close(); open()
        assertEquals(result.snapshot.values, repository.open(a).values)
        assertEquals(1L, repository.open(a).revision)
        assertEquals(0L, repository.open(b).revision)
    }

    @Test fun invalidDraftDoesNotReplaceSavedRulesOrInventAValue() = runBlocking {
        install(bundle("one"))
        val empty = repository.open(a)
        assertTrue(empty.values.isEmpty())
        assertTrue(repository.save(empty, JsonObject(emptyMap())) is ConfigurationSaveResult.Invalid)
        assertEquals(0L, repository.open(a).revision)
        repository.save(empty, amount("7"))
        val saved = repository.open(a)
        assertTrue(repository.save(saved, amount("-")) is ConfigurationSaveResult.Invalid)
        assertEquals(saved, repository.open(a))
    }

    @Test fun activeVersionAndRevisionConflictsCannotOverwriteAnotherSave() = runBlocking {
        install(bundle("one")); val original = repository.open(a)
        install(bundle("two"))
        assertEquals(ConfigurationSaveResult.VersionChanged, repository.save(original, amount("9")))
        val current = repository.open(a)
        repository.save(current, amount("3"))
        assertEquals(ConfigurationSaveResult.RevisionChanged, repository.save(current, amount("4")))
        assertEquals("3", repository.open(a).values["amount"]!!.jsonPrimitive.content)
    }

    @Test fun schemaChangesAndVersionDeletionNeverDiscardUserFields() = runBlocking {
        install(bundle("one"))
        val values = JsonObject(mapOf("amount" to JsonPrimitive("7"), "note" to JsonPrimitive("保留原值")))
        repository.save(repository.open(a), values)
        install(bundle("two", fields.filterNot { it.id == "note" }))
        assertEquals(VersionChange.Applied, library.delete("one"))
        val current = repository.open(a)
        assertEquals(JsonPrimitive("保留原值"), current.values["note"])
        val result = repository.save(current, current.values) as ConfigurationSaveResult.Invalid
        assertEquals(listOf("note"), result.issues.map { it.location })
        assertEquals(1L, repository.open(a).revision)
        assertEquals(JsonPrimitive("保留原值"), repository.open(a).values["note"])
    }

    @Test fun schemaOneMigrationPreservesVersionSelectionAndUnresolvedReference() = runBlocking {
        database.close(); context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.yysassist.storage.AssistDatabase/1.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema["entities"]!!.jsonArray.forEach { element ->
                val entity = element.jsonObject; val table = entity["tableName"]!!.jsonPrimitive.content
                old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            val text = codec.write(bundle("legacy"))
            old.execSQL("INSERT INTO task_versions VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>("legacy", TaskVersionLibrary.sha256(text), text, "2026-11-16T00:00:00Z", "迁移前", 1))
            old.execSQL("INSERT INTO task_version_selection VALUES (1, 'legacy', 'legacy')")
            old.execSQL("INSERT INTO task_version_references VALUES ('old-run', 'legacy', 'UNRESOLVED')")
            old.version = 1
        }
        open()
        assertEquals("legacy", library.snapshot().activeVersion)
        assertEquals(1, library.snapshot().versions.single().referenceCount)
        assertTrue(repository.open(a).values.isEmpty())
        assertEquals(0L, repository.open(a).revision)
    }

    @Test fun editorPreservesUnsavedInputWhenReloadCannotFindTheTask() = runBlocking {
        install(bundle("one"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val editor = TaskConfigurationEditor(repository, scope)
        try {
            editor.open(a)
            withTimeout(5_000) { editor.drafts.first { it[a] is TaskEditorState.Ready } }
            editor.change(a, "amount", JsonPrimitive("7."))
            editor.open(b)
            withTimeout(5_000) { editor.drafts.first { it[b] is TaskEditorState.Ready } }
            editor.open(a)
            assertEquals(JsonPrimitive("7."), (editor.drafts.value[a] as TaskEditorState.Ready).values["amount"])
            install(bundle("two", includeA = false))
            editor.reload(a)
            val state = withTimeout(5_000) { editor.drafts.first { (it[a] as? TaskEditorState.Ready)?.canReload == true } }[a] as TaskEditorState.Ready
            assertEquals(JsonPrimitive("7."), state.values["amount"])
            assertTrue(state.message!!.contains("原输入已保留"))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun slowToastConsumerCannotKeepASuccessfulSaveInSavingState() = runBlocking {
        install(bundle("one"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val editor = TaskConfigurationEditor(repository, scope)
        val blocked = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        scope.launch(Dispatchers.Unconfined) { editor.messages.onSubscription { subscribed.complete(Unit) }.collect { blocked.await() } }
        try {
            subscribed.await(); editor.open(a)
            withTimeout(5_000) { editor.drafts.first { it[a] is TaskEditorState.Ready } }
            repeat(4) { index ->
                editor.change(a, "amount", JsonPrimitive((index + 1).toString())); editor.save(a)
                withTimeout(5_000) { editor.drafts.first { (it[a] as? TaskEditorState.Ready)?.let { !it.saving && it.snapshot.revision == index + 1L } == true } }
            }
            assertEquals(4L, repository.open(a).revision)
            assertEquals("4", repository.open(a).values["amount"]!!.jsonPrimitive.content)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
}
