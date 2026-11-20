package io.github.yysassist.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.protocol.AccountView
import io.github.yysassist.shared.console.ConsoleApi
import io.github.yysassist.shared.console.ConsoleData
import io.github.yysassist.shared.console.ConsoleStore
import io.github.yysassist.shared.console.ConsoleTransport
import io.github.yysassist.shared.pages.*
import io.github.yysassist.shared.ui.*
import io.github.yysassist.shared.console.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

/** A page of the console. Each place's list page is the bottom of its stack; details open on top of it. */
sealed interface Route {
    val section: Section?
    data object Devices : Route { override val section = Section.DEVICES }
    data class Device(val id: String) : Route { override val section = Section.DEVICES }
    data class App(val deviceId: String, val key: String) : Route { override val section = Section.DEVICES }
    data class Job(val id: String) : Route { override val section = Section.DEVICES }
    data object Accounts : Route { override val section = Section.ACCOUNTS }
    data class Account(val id: String, val tab: AccountTab = AccountTab.TASKS) : Route { override val section = Section.ACCOUNTS }
    /** A task's settings for an account on a page of their own, in the narrow and medium tiers. */
    data class Settings(val accountId: String, val taskId: String) : Route { override val section = Section.ACCOUNTS }
    data object Library : Route { override val section = Section.LIBRARY }
    data object Notices : Route { override val section = Section.NOTICES }
    data object Me : Route { override val section = Section.ME }
    /** A place only this device has (开发), drawn by the end itself. */
    data class Local(val id: String) : Route { override val section: Section? = null }
}
enum class AccountTab(val label: String) { TASKS("任务"), PLANS("计划"), RUNS("运行记录") }

private fun Section.root(): Route = when (this) {
    Section.DEVICES -> Route.Devices; Section.ACCOUNTS -> Route.Accounts; Section.LIBRARY -> Route.Library
    Section.NOTICES -> Route.Notices; Section.ME -> Route.Me
}

/** Where the user is: a stack of pages, so going back returns to the page before. */
@Stable
class ConsoleNav(start: Route = Route.Devices) {
    private val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun go(route: Route) { if (route != current) stack.add(route) }
    /** Opens a place from the navigation: its list page, with nothing on top. */
    fun open(section: Section) { stack.clear(); stack.add(section.root()) }
    fun openLocal(id: String) { stack.clear(); stack.add(Route.Local(id)) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    /** Replaces the current page, as a tab switch does. */
    fun replace(route: Route) { stack[stack.lastIndex] = route }
}

/**
 * One signed-in console: the stream's data, the requests and where the user is. An end keeps it outside its windows,
 * so a recreated window (a phone turned sideways) shows the same page without loading again; [reset] on sign-out.
 */
class ConsoleSession(transport: ConsoleTransport) {
    val store = ConsoleStore(transport)
    val api = ConsoleApi(transport)
    var nav = ConsoleNav(); private set
    private val mutableAccount = MutableStateFlow<AccountView?>(null)
    /** Who is signed in, once read; kept here so a recreated window never shows it blank. */
    val account: StateFlow<AccountView?> = mutableAccount.asStateFlow()
    fun accountChanged(account: AccountView) { mutableAccount.value = account }
    /**
     * Reads who is signed in each time the stream (re)connects, trying again every [retryMillis] while it stays
     * connected, so one failed request never leaves 我的 blank. Runs while the console is shown.
     */
    suspend fun followAccount(retryMillis: Long = 3_000, failed: (Exception) -> Unit = {}) {
        store.connection.collectLatest { state ->
            if (state != Connection.LIVE) return@collectLatest
            while (true) {
                try { mutableAccount.value = api.me(); return@collectLatest }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { failed(problem); delay(retryMillis) }
            }
        }
    }
    fun reset() { store.clear(); nav = ConsoleNav(); mutableAccount.value = null }
}

/** What every page reads: the live data, who is signed in, how to move and how to act. */
class ConsoleContext(
    val data: ConsoleData,
    val api: ConsoleApi,
    val nav: ConsoleNav,
    val account: AccountView?,
    /** Tells the session the account changed here (an email bound, another account merged in). */
    val accountChanged: (AccountView) -> Unit,
    /** This device's id on the server, on an end that runs games; its card says “这台手机”. */
    val thisDevice: String?,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    /** Runs a request for the user; a refusal or a lost connection is shown, and [done] runs only on success. */
    fun act(done: (() -> Unit)? = null, block: suspend () -> Unit) { scope.launch {
        val failure = try { block(); null } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { failureText(problem) }
        if (failure == null) done?.invoke() else snackbar.showSnackbar(failure)
    } }
    fun say(text: String) { scope.launch { snackbar.showSnackbar(text) } }
}
/** What to tell the user about a failed request: the server's own words, a lost connection, or a reply this end cannot read. */
fun failureText(problem: Exception): String = when (problem) {
    is CloudRequestException -> problem.message
    is IOException -> "无法连接服务器，请检查网络"
    else -> "服务器的回复无法识别，请更新护肝宝后再试"
}
val LocalConsole = staticCompositionLocalOf<ConsoleContext> { error("outside the console") }

/** What an end that runs games adds to the console: its run strip, its own places and its own settings in 我的. */
class LocalParts(
    val entries: List<LocalEntry> = emptyList(),
    val page: @Composable (String) -> Unit = {},
    val runStrip: (@Composable () -> Unit)? = null,
    /** Shown in 我的 under “本机”: floating ball, permissions… */
    val settings: (@Composable () -> Unit)? = null,
    /** This device's own switch for notices, on top of 通知. */
    val notices: (@Composable () -> Unit)? = null,
    /** The look of this end: "auto", "light" or "dark", and how to change it. */
    val appearance: Pair<String, (String) -> Unit>? = null,
    val signOut: suspend () -> Unit = {},
)

/** The signed-in console, the same pages on every end (C40). */
@Composable
fun ConsoleApp(session: ConsoleSession, thisDevice: String?, local: LocalParts = LocalParts()) {
    val store = session.store; val api = session.api; val nav = session.nav
    val account by session.account.collectAsState()
    val data by store.data.collectAsState()
    val connection by store.connection.collectAsState()
    val loaded by store.loaded.collectAsState()
    LaunchedEffect(store) { store.follow() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = ConsoleContext(data, api, nav, account, session::accountChanged, thisDevice, scope, snackbar)
    CompositionLocalProvider(LocalConsole provides context) {
        val route = nav.current
        ConsoleShell(route.section, nav::open, connection, local.entries, (route as? Route.Local)?.id, nav::openLocal, local.runStrip) {
            Box(Modifier.fillMaxSize()) {
                when {
                    route is Route.Local -> local.page(route.id)
                    !loaded -> Empty("正在加载…")
                    else -> when (route) {
                        Route.Devices -> DevicesPage()
                        is Route.Device -> DevicePage(route.id)
                        is Route.App -> AppPage(route.deviceId, route.key)
                        is Route.Job -> JobPage(route.id)
                        Route.Accounts -> GameAccountsPage()
                        is Route.Account -> AccountDetailPage(route.id, route.tab)
                        is Route.Settings -> SettingsPage(route.accountId, route.taskId)
                        Route.Library -> LibraryPage()
                        Route.Notices -> NoticesPage(local)
                        Route.Me -> MePage(local)
                        is Route.Local -> Unit
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
