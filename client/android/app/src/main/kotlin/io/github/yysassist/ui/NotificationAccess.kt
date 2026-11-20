package io.github.yysassist.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Only requested on Android 13+, where this runtime permission exists; minSdk is 30. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

internal enum class NotificationAccess { ALLOWED, REQUESTABLE, SYSTEM_SETTINGS }

/** Android 13+ asks at runtime until the system stops prompting; older versions only have the app switch. */
internal fun notificationAccess(sdk: Int, permissionGranted: Boolean, notificationsEnabled: Boolean, promptBlocked: Boolean): NotificationAccess = when {
    sdk >= Build.VERSION_CODES.TIRAMISU && !permissionGranted -> if (promptBlocked) NotificationAccess.SYSTEM_SETTINGS else NotificationAccess.REQUESTABLE
    notificationsEnabled -> NotificationAccess.ALLOWED
    else -> NotificationAccess.SYSTEM_SETTINGS
}

@Composable
internal fun NotificationAccessContent(access: NotificationAccess, request: () -> Unit, openSettings: () -> Unit) {
    val palette = LocalAssistPalette.current
    SettingRow(AssistIcons.Bell, palette.orange, "通知权限", when (access) {
        NotificationAccess.ALLOWED -> null
        NotificationAccess.REQUESTABLE -> "用于任务提醒，不开也能运行"
        NotificationAccess.SYSTEM_SETTINGS -> "已在系统设置里关闭"
    }) {
        when (access) {
            NotificationAccess.ALLOWED -> SettingValue("已开启", palette.success)
            NotificationAccess.REQUESTABLE -> SmallAction("开启", onClick = request)
            NotificationAccess.SYSTEM_SETTINGS -> SmallAction("去设置", onClick = openSettings)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

internal class NotificationAccessHandle(val access: NotificationAccess, val request: () -> Unit, val openSettings: () -> Unit)

@Composable
internal fun rememberNotificationAccess(): NotificationAccessHandle {
    val context = LocalContext.current
    var promptBlocked by rememberSaveable { mutableStateOf(false) }
    fun read() = notificationAccess(Build.VERSION.SDK_INT,
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(), promptBlocked)
    var access by remember { mutableStateOf(read()) }
    // The user may change this in system settings while the page is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = read() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied with no rationale means the system will not show its dialog again.
        promptBlocked = !granted && context.activity()?.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS) == false
        access = read()
    }
    return NotificationAccessHandle(access, { launcher.launch(POST_NOTIFICATIONS) }, {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    })
}
