package io.github.yysassist.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.auth.GitHubSessionState
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.device.ScreenshotCheck
import io.github.yysassist.floating.FloatingPreferencesState
import kotlinx.coroutines.launch

private class SettingsSection(val name: String, val attention: Boolean = false, val content: @Composable ColumnScope.() -> Unit)

@Composable
internal fun SettingsPage(appearance: AppearanceState, mode: ThemeMode, setTheme: (ThemeMode) -> Unit, versions: VersionCatalogState,
    github: GitHubSessionState, floating: FloatingPreferencesState, setFloatingVisible: (Boolean) -> Unit, reloadFloating: () -> Unit,
    open: (String) -> Unit) {
    val p = LocalAssistPalette.current
    val service by GameAccessibilityService.connection.collectAsState()
    val sections = listOf(
        SettingsSection("权限与运行环境", service == null) {
            AccessibilityRow(service != null); RowDivider(); NotificationAccessSection(); RowDivider(); ScreenshotRow()
        },
        SettingsSection("悬浮球") { FloatingRows(floating, setFloatingVisible, reloadFloating) },
        SettingsSection("外观") {
            SettingRow(AssistIcons.Theme, p.rose, "主题") {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                    ThemeMode.entries.forEach { item ->
                        GlassChoice(when (item) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" },
                            item == mode, appearance is AppearanceState.Ready) { setTheme(item) }
                    }
                }
            }
        },
        SettingsSection("任务与账号") {
            val catalog = (versions as? VersionCatalogState.Ready)?.catalog
            SettingRow(AssistIcons.Cloud, p.blue, "任务更新", when (versions) {
                VersionCatalogState.Loading -> "读取中"
                is VersionCatalogState.Failure -> "本地版本读取失败"
                is VersionCatalogState.Ready -> versions.catalog.activeVersion?.let { "当前版本 $it" } ?: "暂无已启用任务版本"
            }, { open("任务更新") }) {
                if (catalog?.hasKnownUpdate == true) SettingValue("有更新", p.error)
                Chevron()
            }
            RowDivider()
            SettingRow(AssistIcons.User, p.teal, "GitHub", when (github) {
                GitHubSessionState.Loading -> "读取中"; GitHubSessionState.Saving -> "保存登录中"
                is GitHubSessionState.SigningIn -> "等待授权"; is GitHubSessionState.Failure -> "登录资料暂不可用"
                is GitHubSessionState.SignedOut -> "未登录"; is GitHubSessionState.Connected -> github.login?.let { "@$it" } ?: "已登录"
            }, { open("GitHub") }) { Chevron() }
        },
        SettingsSection("数据与诊断") {
            SettingRow(AssistIcons.History, p.orange, "运行记录", "查看每次运行的结果", { open("运行记录") }) { Chevron() }
        },
        SettingsSection("关于") {
            SettingRow(AssistIcons.Info, p.violet, "版本信息") { SettingValue(BuildConfig.VERSION_NAME) }
        },
    )
    var selectedName by rememberSaveable { mutableStateOf(sections.first().name) }
    Column(Modifier.fillMaxSize()) {
    if (appearance is AppearanceState.Failure) Text(appearance.message, Modifier.padding(horizontal = UiSpace.page, vertical = UiSpace.gap), color = p.error)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= UiSpace.wide) Row(Modifier.fillMaxSize()) {
            val selected = sections.firstOrNull { it.name == selectedName } ?: sections.first()
            Column(Modifier.width(208.dp).verticalScroll(rememberScrollState()).padding(UiSpace.gap),
                verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                sections.forEach { section ->
                    val active = section === selected
                    Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp))
                        .background(if (active) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                        .clickable { selectedName = section.name }.padding(horizontal = UiSpace.medium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        Text(section.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (active) FontWeight.SemiBold else null)
                        if (section.attention) Box(Modifier.size(8.dp).clip(CircleShape).background(p.warning).semantics { contentDescription = "需要处理" })
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(UiSpace.medium)) {
                AssistCard(content = selected.content)
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
            sections.forEach { section -> SectionLabel(section.name); AssistCard(content = section.content) }
        }
    }
    }
}

@Composable
private fun Chevron() = Icon(AssistIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun AccessibilityRow(connected: Boolean) {
    val context = LocalContext.current
    val p = LocalAssistPalette.current
    SettingRow(AssistIcons.Shield, p.blue, "无障碍服务", "用于读取画面和执行操作") {
        if (connected) SettingValue("已开启", p.success)
        else OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("去开启") }
    }
}

@Composable
private fun ScreenshotRow() {
    val p = LocalAssistPalette.current
    val service by GameAccessibilityService.connection.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScreenshotCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service) { checking = false; result = null; error = null }
    val failure = error ?: when (val value = result) {
        is ScreenshotCheck.PlatformFailure -> when (value.code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "截图授权不可用，请重新检查系统设置"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图请求过于频繁，请稍后再试"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "当前屏幕无法截图"
            else -> "截图失败，请检查授权与当前画面"
        }
        is ScreenshotCheck.DecodeFailure -> value.reason
        else -> null
    }
    val success = result as? ScreenshotCheck.Success
    SettingRow(AssistIcons.Phone, p.teal, "屏幕截图", failure ?: success?.let { "已取得可读取画面 · ${it.width} × ${it.height}" } ?: "检查能否读取当前画面") {
        OutlinedButton(enabled = service != null && !checking, onClick = {
            val owner = service ?: return@OutlinedButton
            checking = true; error = null; result = null
            try {
                owner.checkScreenshot { value ->
                    // A late platform callback cannot write into an obsolete screen composition.
                    scope.launch {
                        if (GameAccessibilityService.connection.value === owner) result = value
                        else error = "截图授权已变化，请重新检查"
                        checking = false
                    }
                }
            } catch (_: SecurityException) { checking = false; error = "截图授权不可用" }
        }) { Text(if (checking) "检查中" else "检查") }
    }
}

@Composable
private fun ColumnScope.FloatingRows(state: FloatingPreferencesState, setVisible: (Boolean) -> Unit, reload: () -> Unit) {
    val p = LocalAssistPalette.current
    val service by GameAccessibilityService.connection.collectAsState()
    val failures = remember(service) { service?.floating?.failure ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val failure by failures.collectAsState()
    when (state) {
        FloatingPreferencesState.Loading -> SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", "读取中")
        is FloatingPreferencesState.Failure -> SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", state.message) {
            OutlinedButton(reload) { Text("重新读取") }
        }
        is FloatingPreferencesState.Ready -> {
            SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", failure ?: if (service == null) "无障碍服务未连接" else "在游戏中快速控制运行") {
                if (failure != null) OutlinedButton({ service?.floating?.retry() }) { Text("重新显示") }
                else Switch(state.settings.visible, setVisible)
            }
            RowDivider()
            SettingRow(AssistIcons.Star, p.gold, "快捷与常用", "在任务列表点星标添加") { SettingValue("${state.settings.favorites.size} 个") }
        }
    }
}
