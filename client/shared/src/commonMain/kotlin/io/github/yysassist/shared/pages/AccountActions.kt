package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.yysassist.protocol.AccountView
import io.github.yysassist.protocol.MergeOffer
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** Binds an email to an account that has none: a code to the address, then the code (as client/web AccountActions). */
@Composable
internal fun BindEmail(done: (AccountView) -> Unit, merge: (MergeOffer) -> Unit) {
    val console = LocalConsole.current
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        TextInput(email, { if (!sent) email = it }, "邮箱", Modifier.fillMaxWidth())
        if (sent) TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "验证码", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            if (!sent) HgbButton("发送验证码", { console.act({ sent = true; console.say("验证码已发到 $email") }) { console.api.requestEmailBind(email.trim()) } },
                ButtonKind.PRIMARY, small = true, enabled = '@' in email)
            else {
                HgbButton("绑定", { console.act { console.api.verifyEmailBind(email.trim(), code).let { r -> r.merge?.let(merge) ?: r.account?.let(done) } } },
                    ButtonKind.PRIMARY, small = true, enabled = code.length == 6)
                LinkButton("换个邮箱", { sent = false; code = "" })
            }
        }
    }
}

/** What the other account holds, and the choice to merge it into this one. */
@Composable
internal fun MergeCard(offer: MergeOffer, settled: (AccountView?) -> Unit) {
    val console = LocalConsole.current
    val other = offer.other
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("${other.email ?: "GitHub ${other.githubLogin}"} 属于另一个账号", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Muted("${other.devices} 台设备 · ${other.gameAccounts} 个游戏账号 · ${other.plans} 个计划。合并后都归到当前账号，那个账号随之删除；同一台设备、同名的游戏账号只留当前账号的一份。")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                HgbButton("合并到当前账号", { console.act { settled(console.api.merge(offer.token)) } }, ButtonKind.PRIMARY, small = true)
                HgbButton("不合并", { console.act { console.api.declineMerge(offer.token); settled(null) } }, small = true)
            }
        }
    }
}

/** Deletes the account for good: the word 注销, and the emailed code when it has an email. */
@Composable
internal fun DeleteAccount(account: AccountView, deleted: suspend () -> Unit, close: () -> Unit) {
    val console = LocalConsole.current
    var word by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("注销账号", fontWeight = FontWeight.SemiBold, color = LocalTokens.current.danger, style = MaterialTheme.typography.bodyMedium)
            Muted("账号和其中的设备、游戏账号、计划、作业记录、通知都会删除，不能恢复。")
            account.email?.let { address ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "邮箱验证码", Modifier.weight(1f))
                    HgbButton("发送验证码", { console.act({ console.say("验证码已发到 $address") }) { console.api.requestDeletionCode() } }, small = true)
                }
            }
            TextInput(word, { word = it }, "输入“注销”", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                HgbButton("注销", { console.act { console.api.deleteAccount(word.trim(), code.ifEmpty { null }); deleted() } }, ButtonKind.DANGER, small = true,
                    enabled = word.trim() == "注销" && (account.email == null || code.length == 6))
                LinkButton("取消", close)
            }
        }
    }
}
