package io.github.yysassist.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.auth.GitHubSessionState
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.device.ScreenshotCheck
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.yysassist.floating.FloatingPreferencesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class SettingsSection(val name: String, val attention: Boolean = false, val content: @Composable ColumnScope.() -> Unit)

@Composable
internal fun SettingsPage(appearance: AppearanceState, mode: ThemeMode, setTheme: (ThemeMode) -> Unit, versions: VersionCatalogState,
    github: GitHubSessionState, taskSource: String, floating: FloatingPreferencesState, setFloatingVisible: (Boolean) -> Unit, setAutoDock: (Boolean) -> Unit, reloadFloating: () -> Unit,
    open: (String) -> Unit) {
    val p = LocalAssistPalette.current
    val service by GameAccessibilityService.connection.collectAsState()
    val notifications = rememberNotificationAccess()
    val sections = listOf(
        SettingsSection("权限与运行环境", service == null) {
            AccessibilityRow(service != null); RowDivider()
            NotificationAccessContent(notifications.access, notifications.request, notifications.openSettings); RowDivider(); ScreenshotRow()
        },
        SettingsSection("悬浮球") { FloatingRows(floating, setFloatingVisible, setAutoDock, reloadFloating, { open("常用") }) },
        SettingsSection("外观") {
            SettingRow(AssistIcons.Theme, p.rose, "主题", "跟随系统或固定明暗") {
                Row(Modifier.clip(RoundedCornerShape(UiSpace.medium)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(UiSpace.hairline, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(UiSpace.medium)).padding(3.dp).selectableGroup()) {
                    ThemeMode.entries.forEach { item ->
                        val active = item == mode
                        Box(Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .selectable(active, appearance is AppearanceState.Ready, Role.RadioButton) { setTheme(item) }, Alignment.Center) {
                            Text(when (item) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" },
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        SettingsSection("任务与账号") {
            val catalog = (versions as? VersionCatalogState.Ready)?.catalog
            SettingRow(AssistIcons.Cloud, p.violet, "云端控制", "用 Web 控制台管理这台设备", { open("云端控制") }) { Chevron() }
            RowDivider()
            SettingRow(AssistIcons.Cloud, p.blue, "任务更新", "拉取和切换任务版本", { open("任务更新") }) {
                SettingValue(when (versions) {
                    VersionCatalogState.Loading -> "读取中"
                    is VersionCatalogState.Failure -> "读取失败"
                    is VersionCatalogState.Ready -> if (catalog?.hasKnownUpdate == true) "有新版本" else versions.catalog.activeVersion ?: "未启用"
                }, if (catalog?.hasKnownUpdate == true) p.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Chevron()
            }
            RowDivider()
            SettingRow(AssistIcons.Cloud, p.violet, "任务来源", null, { open("任务来源") }) { SettingValue(taskSource); Chevron() }
            RowDivider()
            SettingRow(AssistIcons.User, p.teal, "GitHub", "用于下载任务版本", { open("GitHub") }) {
                SettingValue(when (github) {
                    GitHubSessionState.Loading -> "读取中"; GitHubSessionState.Saving -> "保存中"
                    is GitHubSessionState.SigningIn -> "等待授权"; is GitHubSessionState.Failure -> "不可用"
                    is GitHubSessionState.SignedOut -> "未登录"; is GitHubSessionState.Connected -> github.login?.let { "@$it" } ?: "已登录"
                })
                Chevron()
            }
        },
        SettingsSection("数据与诊断") {
            SettingRow(AssistIcons.History, p.orange, "运行记录", "查看每次运行的结果和步骤", { open("运行记录") }) { Chevron() }
            RowDivider()
            SettingRow(AssistIcons.Data, p.teal, "运行与数据", "存储占用和诊断信息", { open("运行与数据") }) { Chevron() }
            RowDivider()
            SettingRow(AssistIcons.Cloud, p.blue, "连接日志", "和服务器的连接、收到和处理作业的经过", { open("连接日志") }) { Chevron() }
        },
        SettingsSection("关于") {
            SettingRow(AssistIcons.Info, p.violet, "版本信息", "当前安装的版本") { SettingValue(BuildConfig.VERSION_NAME) }
        },
    )
    var selectedName by rememberSaveable { mutableStateOf(sections.first().name) }
    Column(Modifier.fillMaxSize()) {
    if (appearance is AppearanceState.Failure) Text(appearance.message, Modifier.padding(horizontal = UiSpace.page, vertical = UiSpace.gap), color = p.error)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= UiSpace.wide) Row(Modifier.fillMaxSize()) {
            val selected = sections.firstOrNull { it.name == selectedName } ?: sections.first()
            Column(Modifier.width(220.dp).verticalScroll(rememberScrollState()).padding(UiSpace.gap),
                verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                Text("设置", Modifier.padding(horizontal = 10.dp, vertical = UiSpace.gap), style = MaterialTheme.typography.titleLarge)
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
                Text(selected.name, Modifier.padding(horizontal = UiSpace.tiny, vertical = UiSpace.gap), style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalSettingTiles provides false) { AssistCard(content = selected.content) }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
            ReadinessBanner(service != null, notifications.access)
            sections.forEach { section -> SectionLabel(section.name); AssistCard(content = section.content) }
        }
    }
    }
}

@Composable
private fun ReadinessBanner(accessibility: Boolean, notification: NotificationAccess) {
    val p = LocalAssistPalette.current
    val (text, tint) = when {
        !accessibility -> "需要开启无障碍服务才能运行任务" to p.warning
        notification != NotificationAccess.ALLOWED -> "可以运行，开启通知后能及时收到提醒" to p.warning
        else -> "运行条件已全部就绪" to p.success
    }
    Text(text, Modifier.fillMaxWidth().padding(bottom = UiSpace.tiny).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = .14f))
        .padding(horizontal = 14.dp, vertical = UiSpace.medium), color = tint, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Chevron() = Icon(AssistIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun AccessibilityRow(connected: Boolean) {
    val context = LocalContext.current
    val p = LocalAssistPalette.current
    SettingRow(AssistIcons.Shield, p.blue, "无障碍服务", if (connected) null else "运行任务需要它") {
        if (connected) SettingValue("已开启", p.success)
        else SmallAction("开启") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
    SettingRow(AssistIcons.Phone, p.teal, "屏幕截图", failure ?: success?.let { "能读取画面 · ${it.width} × ${it.height}" }) {
        if (service == null) SettingValue("未就绪")
        else SmallAction(if (checking) "检查中" else "检查", !checking) {
            val owner = service ?: return@SmallAction
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
        }
    }
}

@Composable
private fun ColumnScope.FloatingRows(state: FloatingPreferencesState, setVisible: (Boolean) -> Unit, setAutoDock: (Boolean) -> Unit, reload: () -> Unit, openFavorites: () -> Unit) {
    val p = LocalAssistPalette.current
    val service by GameAccessibilityService.connection.collectAsState()
    val failures = remember(service) { service?.floating?.failure ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val failure by failures.collectAsState()
    when (state) {
        FloatingPreferencesState.Loading -> SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", "读取中")
        is FloatingPreferencesState.Failure -> SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", state.message) {
            SmallAction("重新读取", onClick = reload)
        }
        is FloatingPreferencesState.Ready -> {
            SettingRow(AssistIcons.Circle, p.violet, "显示悬浮球", failure ?: if (service == null) "无障碍服务未连接" else null) {
                if (failure != null) SmallAction("重新显示") { service?.floating?.retry() }
                else AssistSwitch(state.settings.visible, label = "显示悬浮球", change = setVisible)
            }
            RowDivider()
            SettingRow(AssistIcons.Phone, p.blue, "悬浮球自动吸边") {
                AssistSwitch(state.settings.autoDock, label = "自动吸边", change = setAutoDock)
            }
            RowDivider()
            SettingRow(AssistIcons.Star, p.gold, "悬浮球里的常用任务", null, openFavorites) { SettingValue("${state.settings.favorites.size} 个"); Chevron() }
        }
    }
}

/** Read-only figures measured from this app's own files. */
@Composable
internal fun DataUsagePage(state: io.github.yysassist.core.execution.ExecutorSnapshot) {
    val context = LocalContext.current
    val p = LocalAssistPalette.current
    var sizes by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(Unit) {
        sizes = withContext(Dispatchers.IO) {
            fun size(file: File) = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            listOf(context.databaseList().sumOf { context.getDatabasePath(it).length() },
                size(File(context.noBackupFilesDir, "task-resources")), size(context.cacheDir))
        }
    }
    fun text(index: Int) = sizes?.get(index)?.let { Formatter.formatShortFileSize(context, it) } ?: "计算中"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
        SectionLabel("存储占用")
        AssistCard {
            SettingRow(AssistIcons.Data, p.teal, "运行数据库", "任务配置、运行状态和记录") { SettingValue(text(0)) }
            RowDivider()
            SettingRow(AssistIcons.Cloud, p.blue, "任务资源", "识别模型和图片素材") { SettingValue(text(1)) }
            RowDivider()
            SettingRow(AssistIcons.Phone, p.orange, "缓存", "可由系统自动清理") { SettingValue(text(2)) }
        }
        SectionLabel("诊断信息")
        AssistCard {
            SettingRow(AssistIcons.History, p.violet, "近期运行事实", "保留在运行状态中的最近记录") { SettingValue("${state.facts.size} 条") }
            RowDivider()
            SettingRow(AssistIcons.Info, p.rose, "应用版本") { SettingValue(BuildConfig.VERSION_NAME) }
        }
    }
}

/** This device's own switches, in 我的 → 本机: what it needs to run games, and the floating ball. */
@Composable
internal fun LocalDeviceSettings(floating: FloatingPreferencesState, setVisible: (Boolean) -> Unit, setAutoDock: (Boolean) -> Unit, reload: () -> Unit, openFavorites: () -> Unit,
    updates: io.github.yysassist.update.AppUpdater) {
    val service by GameAccessibilityService.connection.collectAsState()
    val notifications = rememberNotificationAccess()
    Column {
        VersionRow(updates); RowDivider()
        AccessibilityRow(service != null); RowDivider()
        NotificationAccessContent(notifications.access, notifications.request, notifications.openSettings); RowDivider()
        ScreenshotRow(); RowDivider()
        FloatingRows(floating, setVisible, setAutoDock, reload, openFavorites)
    }
}

/** This App's version, and updating it from the code repository's releases without leaving the App. */
@Composable
private fun VersionRow(updates: io.github.yysassist.update.AppUpdater) {
    val p = LocalAssistPalette.current
    val state by updates.state.collectAsState()
    val current = io.github.yysassist.BuildConfig.VERSION_NAME
    fun mb(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)
    val description = when (val s = state) {
        is io.github.yysassist.update.AppUpdateState.Available -> "新版本 ${s.release.version}，约 ${mb(s.release.bytes)} MB"
        is io.github.yysassist.update.AppUpdateState.Downloading -> "正在下载 ${s.release.version}：${mb(s.bytes)} / ${mb(s.release.bytes)} MB"
        is io.github.yysassist.update.AppUpdateState.Ready -> "${s.release.version} 已下载并校验，安装时系统会再确认一次"
        is io.github.yysassist.update.AppUpdateState.Installing -> "正在交给系统安装 ${s.release.version}…"
        is io.github.yysassist.update.AppUpdateState.Failed -> s.message
        io.github.yysassist.update.AppUpdateState.UpToDate -> "已是最新"
        else -> null
    }
    SettingRow(AssistIcons.Info, p.blue, "版本 $current", description) {
        when (val s = state) {
            io.github.yysassist.update.AppUpdateState.Checking -> SettingValue("正在检查…")
            io.github.yysassist.update.AppUpdateState.UpToDate -> SmallAction("检查更新", onClick = updates::check)
            is io.github.yysassist.update.AppUpdateState.Available -> SmallAction("下载", onClick = updates::download)
            is io.github.yysassist.update.AppUpdateState.Downloading -> SettingValue("${(s.bytes * 100 / s.release.bytes.coerceAtLeast(1))}%")
            is io.github.yysassist.update.AppUpdateState.Ready -> SmallAction("安装", onClick = updates::install)
            is io.github.yysassist.update.AppUpdateState.Installing -> SettingValue("请稍候")
            is io.github.yysassist.update.AppUpdateState.Failed -> if (s.retry) SmallAction("重试") { if (s.release != null) updates.download() else updates.check() }
            io.github.yysassist.update.AppUpdateState.Idle -> SmallAction("检查更新", onClick = updates::check)
        }
    }
}

/** Whether notices the server pushes to this phone pop up here; which events come is set on the web (a 本机通知 channel). */
@Composable
internal fun LocalNoticeSwitch(notices: io.github.yysassist.cloud.LocalNotices) {
    val shown by notices.enabled.collectAsState()
    val p = LocalAssistPalette.current
    AssistCard {
        SettingRow(AssistIcons.Bell, p.violet, "在这台手机上弹出通知", "要在网页添加“本机通知”渠道") {
            AssistSwitch(shown, label = "在这台手机上弹出通知", change = notices::setEnabled)
        }
    }
}

/** The connection log, newest first; 复制 puts it on the clipboard to send with a problem report. */
@Composable
internal fun ConnectionLogPage(log: io.github.yysassist.cloud.ConnectionLog) {
    val lines by log.lines.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalAlignment = Alignment.CenterVertically) {
            Text("${lines.size} 条", Modifier.weight(1f), color = io.github.yysassist.shared.theme.LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall)
            io.github.yysassist.shared.ui.HgbButton("复制", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(lines.joinToString("\n"))) }, small = true, enabled = lines.isNotEmpty())
            io.github.yysassist.shared.ui.HgbButton("清空", log::clear, small = true, enabled = lines.isNotEmpty())
        }
        if (lines.isEmpty()) Text("还没有记录。", color = io.github.yysassist.shared.theme.LocalTokens.current.muted, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(lines.asReversed().size) { index ->
                Text(lines.asReversed()[index], style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/** Where task versions are pulled from: this build's repository, or another "owner/name" the user enters. */
@Composable
internal fun TaskSourcePage(setting: io.github.yysassist.TaskSourceSetting) {
    val current by setting.current.collectAsState()
    var text by remember(current) { mutableStateOf(current.fullName) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        Text("任务版本从这个 GitHub 仓库的正式发布下载。用自己 fork 的任务仓库时改成它，下次拉取生效；已下载的版本保留。",
            color = io.github.yysassist.shared.theme.LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall)
        io.github.yysassist.shared.ui.TextInput(text, { text = it.trim(); problem = null }, setting.default.fullName, Modifier.fillMaxWidth())
        problem?.let { io.github.yysassist.shared.ui.Message(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            io.github.yysassist.shared.ui.HgbButton("保存", { if (!setting.set(text)) problem = "要写成 owner/name，比如 ${setting.default.fullName}" },
                io.github.yysassist.shared.ui.ButtonKind.PRIMARY, enabled = text != current.fullName)
            if (current != setting.default) io.github.yysassist.shared.ui.HgbButton("恢复默认（${setting.default.fullName}）", { setting.reset() })
        }
    }
}
