package io.github.yysassist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.yysassist.BuildConfig
import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.cloud.CloudState
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Plain HTTP is only accepted for a local server while developing. */
internal fun cloudServerIssue(server: String, debug: Boolean = BuildConfig.DEBUG): String? {
    val url = server.trim().toHttpUrlOrNull() ?: return "请输入完整的服务器地址"
    return if (url.isHttps || (debug && url.host in setOf("10.0.2.2", "127.0.0.1"))) null else "服务器地址必须使用 https"
}

@Composable
internal fun CloudPage(agent: CloudAgent) {
    val state by agent.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
        when (val current = state) {
            CloudState.Loading -> CircularProgressIndicator(Modifier.padding(UiSpace.page))
            is CloudState.SignedOut -> CloudSignIn(agent, current.message)
            is CloudState.SignedIn -> CloudAccount(agent, current)
        }
    }
}

@Composable
private fun CloudAccount(agent: CloudAgent, state: CloudState.SignedIn) {
    val scope = rememberCoroutineScope()
    val p = LocalAssistPalette.current
    var busy by remember { mutableStateOf(false) }
    SectionLabel("当前账号")
    AssistCard {
        SettingRow(AssistIcons.User, p.teal, state.email ?: "已登录", state.serverUrl)
        RowDivider()
        SettingRow(AssistIcons.Cloud, p.blue, "连接状态", state.message ?: if (state.online) "可以在 Web 控制台下发任务" else "正在连接") {
            SettingValue(if (state.online) "在线" else "离线", if (state.online) p.success else p.warning)
        }
    }
    Spacer(Modifier.height(UiSpace.medium))
    OutlinedButton({ busy = true; scope.launch { agent.signOut(); busy = false } }, Modifier.fillMaxWidth().heightIn(min = UiSpace.hit), !busy,
        RoundedCornerShape(UiSpace.medium)) { Text("退出登录") }
}

@Composable
private fun CloudSignIn(agent: CloudAgent, notice: String?) {
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var useCode by rememberSaveable { mutableStateOf(false) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(notice) }
    val shape = RoundedCornerShape(UiSpace.medium)
    fun attempt(action: suspend () -> Unit) {
        val issue = cloudServerIssue(server)
        if (issue != null) { error = issue; return }
        busy = true; error = null
        scope.launch {
            try { action() } catch (failure: IOException) { error = failure.message ?: "无法连接服务器" }
            busy = false
        }
    }
    SectionLabel("登录云端账号")
    AssistCard {
        Column(Modifier.padding(UiSpace.medium), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                GlassChoice("密码登录", !useCode, !busy) { useCode = false; secret = "" }
                GlassChoice("验证码登录", useCode, !busy) { useCode = true; secret = "" }
            }
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, shape = shape, label = { Text("服务器地址") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, shape = shape, label = { Text("邮箱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (!useCode || codeSent) OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), singleLine = true, shape = shape,
                label = { Text(if (useCode) "验证码" else "密码") },
                visualTransformation = if (useCode) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (useCode) KeyboardType.Number else KeyboardType.Password))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button({
                when {
                    !useCode -> attempt { agent.signIn(server.trim(), email.trim(), secret) }
                    !codeSent -> attempt { agent.requestCode(server.trim(), email.trim()); codeSent = true }
                    else -> attempt { agent.signInWithCode(server.trim(), email.trim(), secret.trim()) }
                }
            }, Modifier.fillMaxWidth().heightIn(min = UiSpace.hit), !busy && email.isNotBlank() && server.isNotBlank(), shape) {
                Text(if (busy) "请稍候" else if (useCode && !codeSent) "发送验证码" else "登录")
            }
        }
    }
    Text("还没有账号？请先在 Web 控制台注册。", Modifier.padding(start = UiSpace.gap, top = UiSpace.medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
