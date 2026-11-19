package io.github.yysassist.floating

import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FloatingPreferencesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope
    private lateinit var file: java.io.File
    @Before fun open() { file = context.preferencesDataStoreFile("floating-preferences-${UUID.randomUUID()}"); newScope() }
    private fun newScope() { scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private fun store() = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    @After fun close() { runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }; file.delete() }
    private suspend fun ready(preferences: FloatingPreferences, predicate: (FloatingSettings) -> Boolean = { true }): FloatingSettings =
        (withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Ready && predicate(it.settings) } } as FloatingPreferencesState.Ready).settings

    @Test fun firstUseIsVisibleAndUserChoiceFavoritesAndOrientationAnchorsSurviveReopen(): Unit = runBlocking {
        val preferences = FloatingPreferences(context, scope, store())
        assertTrue(ready(preferences).visible)
        // JSON pairs must keep target/task identities unambiguous even with punctuation in IDs.
        val one = TaskAddress("target:x", "same/task")
        val two = TaskAddress("target", "x:same/task")
        preferences.toggle(one); preferences.toggle(two); preferences.setVisible(false)
        preferences.move(false, FloatingAnchor(.2f, .8f)); preferences.move(true, FloatingAnchor(.7f, .1f))
        val saved = ready(preferences) { !it.visible && it.favorites.size == 2 && it.portrait != null && it.landscape != null }
        scope.cancel(); scope.coroutineContext[Job]!!.join(); newScope()
        val reopened = FloatingPreferences(context, scope, store())
        assertEquals(saved, ready(reopened))
        reopened.toggle(one)
        assertEquals(setOf(two), ready(reopened) { it.favorites.size == 1 }.favorites)
        assertFalse(ready(reopened).visible)
    }
    @Test fun malformedPreferencesStayAReadFailureAndCannotBeOverwrittenByAnUnrelatedToggle(): Unit = runBlocking {
        val data = store()
        data.edit { it[stringSetPreferencesKey("favorite_tasks")] = setOf("bad-json"); it[booleanPreferencesKey("visible")] = false }
        val preferences = FloatingPreferences(context, scope, data)
        withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Failure } }
        val message = async(start = CoroutineStart.UNDISPATCHED) { preferences.messages.first() }
        preferences.setVisible(true)
        withTimeout(5_000) { message.await() }
        assertEquals(false, data.data.first()[booleanPreferencesKey("visible")])
        assertEquals(setOf("bad-json"), data.data.first()[stringSetPreferencesKey("favorite_tasks")])
        preferences.reload()
        assertTrue(preferences.state.value is FloatingPreferencesState.Failure)
    }
}
