package io.github.yysassist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import io.github.yysassist.storage.TaskAddress
import io.github.yysassist.ui.AssistApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class AppNavigation(val id: String, val task: TaskAddress)
class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST = "floating_request"
        private const val TARGET = "floating_target"
        private const val TASK = "floating_task"
        fun taskIntent(context: Context, address: TaskAddress) = Intent(context, MainActivity::class.java)
            .putExtra(REQUEST, UUID.randomUUID().toString()).putExtra(TARGET, address.targetId).putExtra(TASK, address.taskId)
    }
    private val navigation = MutableStateFlow<AppNavigation?>(null)
    private var handledRequest: String? = null
    override fun onStart() { super.onStart(); (application as AssistApplication).mainStarted(this) }
    override fun onStop() { (application as AssistApplication).mainStopped(this); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledRequest = savedInstanceState?.getString(REQUEST)
        readNavigation(intent)
        enableEdgeToEdge()
        val app = application as AssistApplication
        setContent {
            val request = navigation.collectAsState().value
            AssistApp(app, request) { handledRequest = request?.id; navigation.value = null }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString(REQUEST, handledRequest); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readNavigation(intent) }
    private fun readNavigation(intent: Intent) {
        val id = intent.getStringExtra(REQUEST)?.takeIf { it != handledRequest } ?: return
        val target = intent.getStringExtra(TARGET)?.takeIf { it.isNotBlank() } ?: return
        val task = intent.getStringExtra(TASK)?.takeIf { it.isNotBlank() } ?: return
        // An external intent can open a configuration page, never submit execution or grant access.
        navigation.value = AppNavigation(id, TaskAddress(target, task))
    }
}
