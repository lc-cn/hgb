package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.failureText
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*
import kotlinx.coroutines.launch

/** What signing in can do; an end runs these against its server (the App through CloudAgent). */
interface SignInActions {
    suspend fun password(email: String, password: String)
    suspend fun requestCode(email: String)
    suspend fun codeLogin(email: String, code: String)
    suspend fun register(email: String, password: String)
    suspend fun verify(email: String, code: String)
    suspend fun requestReset(email: String)
    suspend fun reset(email: String, code: String, password: String)
}

private enum class SignInTab(val label: String) { PASSWORD("密码登录"), CODE("验证码登录"), REGISTER("注册") }
/** What the form asks for now: the tab's own fields, the address to reset, or the code sent for a purpose. */
private sealed interface FormStep {
    data object Start : FormStep
    data object Forgot : FormStep
    data class Code(val email: String, val purpose: Purpose) : FormStep
}
private enum class Purpose { VERIFY, LOGIN, RESET }

/**
 * Sign-in, sign-up and password reset by email, as the web's (client/web/src/features/auth/AuthPage.tsx), in the three
 * tiers: wide and medium put the brand beside the form, narrow puts it on top. [server] is the address field an end
 * that is not the web needs; [github] draws its GitHub sign-in; [footer] sits under the form (the App's 开发).
 */
@Composable
fun SignInPage(actions: SignInActions, notice: String? = null, server: Pair<String, (String) -> Unit>? = null,
    github: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    /** False when the server has no mail service: then only the server address and GitHub are offered. */
    email: Boolean = true) {
    val tokens = LocalTokens.current
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.bg)) {
        val tier = tierOf(maxWidth)
        CompositionLocalProvider(LocalTier provides tier) {
            val form: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s8),
                        verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        if (tier == Tier.NARROW) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HgbMark(32.dp); Text("护肝宝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        }
                        if (email) SignInForm(actions, notice, server)
                        else {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                                Text("登录护肝宝", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Muted(if (github != null) "用 GitHub 账号登录" else "这台服务器还没有配置登录方式")
                            }
                            if (server != null) Field("服务器地址") { TextInput(server.first, server.second, io.github.yysassist.shared.LocalInstance.current.apiUrl, Modifier.fillMaxWidth()) }
                            notice?.let { Message(it) }
                        }
                        if (github != null) {
                            if (email) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                HorizontalDivider(Modifier.weight(1f), color = tokens.line); Muted("或者"); HorizontalDivider(Modifier.weight(1f), color = tokens.line)
                            }
                            github()
                        }
                        footer?.invoke()
                    }
                }
            }
            if (tier == Tier.NARROW) form(Modifier.fillMaxSize())
            else Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(if (tier == Tier.WIDE) 1f else 0.8f).fillMaxHeight().background(tokens.heroBg).padding(Space.s8),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HgbMark(36.dp); Text("护肝宝", color = tokens.heroInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Text("今晚的日常，\n交给它值班。", color = tokens.heroInk, style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize * 1.4f, lineHeight = MaterialTheme.typography.headlineSmall.lineHeight * 1.4f), fontWeight = FontWeight.Bold)
                        Text("网页、Windows 和安卓 App 用同一个账号。", color = tokens.heroMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                form(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SignInForm(actions: SignInActions, notice: String?, server: Pair<String, (String) -> Unit>?) {
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SignInTab.PASSWORD) }
    var step by remember { mutableStateOf<FormStep>(FormStep.Start) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember(notice) { mutableStateOf(notice?.let { it to false }) }
    fun run(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { message = failureText(problem) to false }
            busy = false
        }
    }
    val (title, sub) = when (val now = step) {
        FormStep.Forgot -> "重置密码" to "验证码会发到你的邮箱"
        is FormStep.Code -> "输入验证码" to "验证码已发到 ${now.email}，10 分钟内有效"
        FormStep.Start -> when (tab) {
            SignInTab.PASSWORD -> "欢迎回来" to "用邮箱和密码登录"
            SignInTab.CODE -> "用验证码登录" to "验证码发到你的邮箱，10 分钟内有效"
            SignInTab.REGISTER -> "注册护肝宝" to "注册后会给邮箱发一个验证码确认"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted(sub)
    }
    PageTabs(SignInTab.entries.map { it to it.label }, tab) { tab = it; step = FormStep.Start; message = null; code = ""; password = "" }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        if (server != null && step == FormStep.Start) Field("服务器地址") { TextInput(server.first, server.second, io.github.yysassist.shared.LocalInstance.current.apiUrl, Modifier.fillMaxWidth()) }
        val wide = Modifier.fillMaxWidth()
        when (val now = step) {
            is FormStep.Code -> {
                Field("验证码") { TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "6 位数字", wide) }
                if (now.purpose == Purpose.RESET) Field("新密码（至少 10 位）") { TextInput(password, { password = it }, "", wide, secret = true) }
                HgbButton(when (now.purpose) { Purpose.RESET -> "重置密码"; Purpose.VERIFY -> "完成注册"; Purpose.LOGIN -> "登录" }, {
                    run {
                        when (now.purpose) {
                            Purpose.RESET -> { actions.reset(now.email, code, password); tab = SignInTab.PASSWORD; step = FormStep.Start; password = ""; message = "密码已重置，请重新登录" to true }
                            Purpose.VERIFY -> actions.verify(now.email, code)
                            Purpose.LOGIN -> actions.codeLogin(now.email, code)
                        }
                    }
                }, ButtonKind.PRIMARY, enabled = !busy && code.length == 6 && (now.purpose != Purpose.RESET || password.length >= 10), modifier = wide)
            }
            FormStep.Forgot -> {
                Field("邮箱") { TextInput(email, { email = it.trim() }, "you@example.com", wide) }
                HgbButton("发送验证码", { run { actions.requestReset(email); step = FormStep.Code(email, Purpose.RESET) } }, ButtonKind.PRIMARY, enabled = !busy && '@' in email, modifier = wide)
            }
            FormStep.Start -> {
                Field("邮箱") { TextInput(email, { email = it.trim() }, "you@example.com", wide) }
                when (tab) {
                    SignInTab.PASSWORD -> {
                        Field("密码") { TextInput(password, { password = it }, "", wide, secret = true) }
                        HgbButton("登录", { run { actions.password(email, password) } }, ButtonKind.PRIMARY, enabled = !busy && '@' in email && password.isNotEmpty(), modifier = wide)
                        LinkButton("忘记密码", { step = FormStep.Forgot; message = null })
                    }
                    SignInTab.CODE -> HgbButton("发送验证码", { run { actions.requestCode(email); step = FormStep.Code(email, Purpose.LOGIN) } },
                        ButtonKind.PRIMARY, enabled = !busy && '@' in email, modifier = wide)
                    SignInTab.REGISTER -> {
                        Field("密码（至少 10 位）") { TextInput(password, { password = it }, "", wide, secret = true) }
                        HgbButton("注册", { run { actions.register(email, password); step = FormStep.Code(email, Purpose.VERIFY) } },
                            ButtonKind.PRIMARY, enabled = !busy && '@' in email && password.length >= 10, modifier = wide)
                    }
                }
            }
        }
        message?.let { (text, ok) -> Message(text, ok) }
        if (step != FormStep.Start) LinkButton("返回", { step = FormStep.Start; message = null; code = "" })
    }
}
