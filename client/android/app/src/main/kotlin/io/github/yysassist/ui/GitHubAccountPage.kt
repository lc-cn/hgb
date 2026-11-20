package io.github.yysassist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import io.github.yysassist.auth.GitHubSessionState

@Composable
internal fun GitHubAccountPage(state: GitHubSessionState, wide: Boolean, signIn: () -> Unit,
    cancel: () -> Unit, signOut: () -> Unit, openUpdates: () -> Unit,
    openBrowser: ((String) -> Unit)? = null, copyCode: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    var browserError by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect((state as? GitHubSessionState.SigningIn)?.prompt?.userCode) { browserError = null }
    LaunchedEffect(state) { if (state !is GitHubSessionState.Connected && state !is GitHubSessionState.Failure) confirmSignOut = false }
    val information: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Icon(AssistIcons.Cloud, null, Modifier.size(UiSpace.tile), tint = LocalAssistPalette.current.blue.copy(alpha = .82f))
            Text("连接 GitHub", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("读取 lc-cn/hgb 的任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state is GitHubSessionState.Connected) Text(state.login?.let { "@$it" } ?: "已登录 GitHub",
                style = MaterialTheme.typography.titleMedium, color = LocalAssistPalette.current.teal)
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            when (state) {
                GitHubSessionState.Loading -> GitHubProgress("读取登录资料")
                GitHubSessionState.Saving -> GitHubProgress("保存登录中")
                is GitHubSessionState.SigningIn -> {
                    val prompt = state.prompt
                    if (prompt == null) GitHubProgress("正在申请设备码") else {
                        Text("在 GitHub 输入此设备码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(prompt.userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.semantics { contentDescription = "GitHub 设备码：${prompt.userCode}" })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                            OutlinedButton(onClick = {
                                if (copyCode != null) copyCode(prompt.userCode) else
                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("GitHub 设备码", prompt.userCode))
                            }, modifier = Modifier.weight(1f).heightIn(min = UiSpace.hit)) { Text("复制设备码") }
                            GitHubPrimary("打开 GitHub", Modifier.weight(1f)) {
                                try {
                                    browserError = null
                                    if (openBrowser != null) openBrowser(prompt.verificationUri.toString()) else
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUri.toString())))
                                } catch (_: android.content.ActivityNotFoundException) { browserError = "未找到浏览器，请在其他设备打开 github.com/login/device" }
                            }
                        }
                        Text("等待你完成授权", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = cancel, modifier = Modifier.heightIn(min = UiSpace.hit)) { Text("取消登录") }
                }
                is GitHubSessionState.Connected -> {
                    if (state.checking) GitHubProgress("正在检查账号")
                    state.message?.let { GitHubMessage(it) }
                    GitHubPrimary("任务更新", Modifier.fillMaxWidth(), openUpdates)
                    TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.heightIn(min = UiSpace.hit)) { Text("退出登录") }
                }
                is GitHubSessionState.SignedOut -> {
                    state.message?.let { GitHubMessage(it) }
                    GitHubPrimary("使用 GitHub 登录", Modifier.fillMaxWidth(), signIn)
                }
                is GitHubSessionState.Failure -> {
                    GitHubMessage(state.message)
                    GitHubPrimary("重新登录", Modifier.fillMaxWidth(), signIn)
                    TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.heightIn(min = UiSpace.hit)) { Text("清除本机登录") }
                }
            }
            browserError?.let { GitHubMessage(it) }
        }
    }
    if (wide) Row(Modifier.fillMaxSize().padding(UiSpace.page), horizontalArrangement = Arrangement.spacedBy(UiSpace.section)) {
        Column(Modifier.weight(.38f).fillMaxHeight().verticalScroll(rememberScrollState())) { information() }
        Column(Modifier.weight(.62f).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
    } else Column(Modifier.fillMaxSize().padding(UiSpace.page)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(UiSpace.section)) {
            information()
            if (state is GitHubSessionState.SigningIn) controls()
        }
        if (state !is GitHubSessionState.SigningIn) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { controls() }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("退出 GitHub？") },
        text = { Text("仅清除此设备的登录资料，已下载任务保留。") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; signOut() }) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("取消") } })
}
@Composable
private fun GitHubProgress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        CircularProgressIndicator(Modifier.size(UiSpace.icon).semantics { contentDescription = label })
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun GitHubMessage(message: String) {
    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
}
@Composable
private fun GitHubPrimary(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    TextButton(onClick = action, modifier = modifier.heightIn(min = UiSpace.hit)
        .background(glassBrush(true), RoundedCornerShape(UiSpace.medium)),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(label) }
}
