package io.github.yysassist.floating

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.ui.UiSpace

@Composable
internal fun FloatingSettingsPage(state: FloatingPreferencesState, setVisible: (Boolean) -> Unit, reload: () -> Unit) {
    val context = LocalContext.current
    val service by GameAccessibilityService.connection.collectAsState()
    val failures = remember(service) { service?.floating?.failure ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val failure by failures.collectAsState()
    Column(Modifier.padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        when (state) {
            FloatingPreferencesState.Loading -> CircularProgressIndicator()
            is FloatingPreferencesState.Failure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(reload) { Text("重新读取") }
            }
            is FloatingPreferencesState.Ready -> {
                ListItem(headlineContent = { Text("显示悬浮球") }, trailingContent = { Switch(state.settings.visible, setVisible) })
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error); OutlinedButton({ service?.floating?.retry() }) { Text("重新显示") } }
                if (service == null) {
                    Text("无障碍服务未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开系统设置") }
                }
            }
        }
    }
}
