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
import io.github.yysassist.cloud.CloudAccounts
import io.github.yysassist.cloud.LocalNotices
import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.core.cloud.CloudState
import io.github.yysassist.protocol.AppAccounts
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Plain HTTP is only accepted for a local server while developing. */
internal fun cloudServerIssue(server: String, debug: Boolean = BuildConfig.DEBUG): String? {
    val url = server.trim().toHttpUrlOrNull() ?: return "请输入完整的服务器地址"
    return if (url.isHttps || (debug && url.host in setOf("10.0.2.2", "127.0.0.1"))) null else "服务器地址必须使用 https"
}

@Composable
internal fun CloudPage(accounts: CloudAccounts, identify: (String) -> Unit, notices: LocalNotices? = null) {
    val agent = accounts.agent
    val state by agent.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
        when (val current = state) {
            CloudState.Loading -> CircularProgressIndicator(Modifier.padding(UiSpace.page))
            is CloudState.SignedOut -> CloudSignIn(agent, current.message)
            is CloudState.SignedIn -> { CloudAccount(agent, current, notices); GameAccounts(accounts, current.online, identify) }
        }
    }
}

@Composable
private fun CloudAccount(agent: CloudAgent, state: CloudState.SignedIn, notices: LocalNotices?) {
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
        if (notices != null) {
            val shown by notices.enabled.collectAsState()
            RowDivider()
            // Which events reach this phone is set on the web (a 本机通知 channel); this only turns showing them here on or off.
            SettingRow(AssistIcons.Bell, p.violet, "在这台手机上弹出通知", "网页“通知 · 渠道”里添加“本机通知”并选这台设备") {
                AssistSwitch(shown, label = "在这台手机上弹出通知", change = notices::setEnabled)
            }
        }
    }
    Spacer(Modifier.height(UiSpace.medium))
    OutlinedButton({ busy = true; scope.launch { agent.signOut(); busy = false } }, Modifier.fillMaxWidth().heightIn(min = UiSpace.hit), !busy,
        RoundedCornerShape(UiSpace.medium)) { Text("退出登录") }
}

/**
 * Which game account each game here is signed in to: read from the game when its task version can identify accounts,
 * otherwise chosen by the user. Settings, plans and runs on this phone follow it.
 */
@Composable
private fun GameAccounts(accounts: CloudAccounts, online: Boolean, identify: (String) -> Unit) {
    val data by accounts.agent.data.collectAsState()
    val scope = rememberCoroutineScope()
    val p = LocalAssistPalette.current
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val apps = accounts.apps()
    Spacer(Modifier.height(UiSpace.medium))
    SectionLabel("这台设备上的游戏")
    AssistCard {
        if (apps.isEmpty()) SettingRow(AssistIcons.Tasks, p.violet, "还没有可用的游戏", "启用任务版本并安装游戏后显示")
        apps.forEachIndexed { index, app ->
            if (index > 0) RowDivider()
            val current = accounts.current(app.gameId, data)
            val seen = data?.apps?.firstOrNull { it.key == app.key }?.observed
            SettingRow(AssistIcons.User, p.violet, app.name, "当前登录：${current?.name ?: if (seen != null) "未保存的账号" else "未指定"}" +
                (seen?.let { "\n游戏里：${it.nickname}（编号 ${it.uid}）" } ?: "")) {
                Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                    if (app.switchable) SmallAction("识别") { identify(app.gameId) }
                    if (seen != null && current == null) SmallAction("保存", enabled = online) {
                        error = null
                        scope.launch {
                            try { accounts.agent.createAccount(io.github.yysassist.protocol.GameAccountSave(app.gameId, seen.nickname, seen.uid, seen.nickname)) }
                            catch (rejected: CloudRequestException) { error = rejected.message }
                            catch (_: IOException) { error = "没有连上服务器，请稍后再试" }
                        }
                    }
                }
                // Read from the game once it can identify the account; chosen by hand only for games it cannot.
                if (seen == null) Box {
                    SmallAction("选择", enabled = online) { choosing = app.key }
                    DropdownMenu(choosing == app.key, { choosing = null }) {
                        val saved = data?.apps?.firstOrNull { it.key == app.key }
                        (accounts.accounts(app.gameId, data).map { it.id to it.name } + (null to "未指定")).forEach { (id, name) ->
                            DropdownMenuItem({ Text(name) }, onClick = {
                                choosing = null; error = null
                                scope.launch {
                                    try { accounts.agent.setAppAccounts(app.key, AppAccounts(id, saved?.defaultAccountId)) }
                                    catch (rejected: CloudRequestException) { error = rejected.message }
                                    catch (_: IOException) { error = "没有连上服务器，请稍后再试" }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
    Text(error ?: if (online) "点“识别”让护肝宝读出游戏里登录的账号；没保存过的点“保存”存为游戏账号。不能识别的游戏，换号后在这里手动选。"
        else "没有连上服务器，当前账号只能查看。", Modifier.padding(start = UiSpace.gap, top = UiSpace.gap),
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
