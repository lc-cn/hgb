package io.github.yysassist.shared.console

import io.github.yysassist.core.cloud.ConsoleStreamEvent
import io.github.yysassist.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** How the console reaches the server: the user's requests and the live event stream (CloudAgent on the App and Windows). */
interface ConsoleTransport {
    /** The reply's JSON, or null when it has none; fails with CloudRequestException when refused, IOException when unreachable. */
    suspend fun request(method: String, path: String, body: String? = null): String?
    fun events(): Flow<ConsoleStreamEvent>
}

internal val consoleJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Everything the console shows, as the server's event stream last described it (as client/web/src/state/console.ts). */
data class ConsoleData(
    val devices: List<DeviceView> = emptyList(),
    val jobs: List<JobView> = emptyList(),
    val accounts: List<GameAccountView> = emptyList(),
    val plans: List<PlanView> = emptyList(),
    val games: List<GameView> = emptyList(),
    val timeZone: String = "Asia/Shanghai",
    /** Bumped when a job's log grows, so an open log knows to read it again. */
    val logVersion: Map<String, Int> = emptyMap(),
    /** Bumped when a rule notices something or the rules change, so the 通知 page reads them again. */
    val noticeVersion: Int = 0,
    /** Bumped by every snapshot: after a gap, anything read before may be stale. */
    val epoch: Int = 0,
)

enum class Connection { CONNECTING, LIVE, LOST }

private val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
/** Devices in the order the web console lists them: by name as Chinese sorts, then by id. */
private val byName = Comparator<DeviceView> { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

/** Applies one event of the stream; an unknown event leaves the data as it was, and one that does not parse is null. */
fun ConsoleData.applyOrNull(name: String, data: String): ConsoleData? = runCatching {
    when (name) {
        "snapshot" -> consoleJson.decodeFromString<ConsoleSnapshot>(data).let { snapshot ->
            ConsoleData(snapshot.devices.sortedWith(byName), snapshot.jobs, snapshot.accounts, snapshot.plans, snapshot.games, snapshot.timeZone,
                emptyMap(), noticeVersion + 1, epoch + 1)
        }
        "device" -> consoleJson.decodeFromString<DeviceView>(data).let { device -> copy(devices = (devices.filter { it.id != device.id } + device).sortedWith(byName)) }
        "device-removed" -> consoleJson.decodeFromString<RemovedDevice>(data).let { removed -> copy(devices = devices.filter { it.id != removed.id }) }
        "jobs" -> copy(jobs = consoleJson.decodeFromString(data))
        "accounts" -> copy(accounts = consoleJson.decodeFromString(data))
        "plans" -> copy(plans = consoleJson.decodeFromString(data))
        "games" -> copy(games = consoleJson.decodeFromString(data))
        "time-zone" -> copy(timeZone = consoleJson.decodeFromString<TimeZoneSave>(data).timeZone)
        "job-log" -> consoleJson.decodeFromString<JobLogUpdated>(data).let { copy(logVersion = logVersion + (it.id to (logVersion[it.id] ?: 0) + 1)) }
        "notice", "rules", "delivery" -> copy(noticeVersion = noticeVersion + 1)
        else -> this
    }
}.getOrNull()
/** As [applyOrNull], keeping the data as it was when the event does not parse. */
fun ConsoleData.apply(name: String, data: String): ConsoleData = applyOrNull(name, data) ?: this

/** Follows the server's event stream into [data]; [follow] runs while the console is shown and signed in. */
class ConsoleStore(private val transport: ConsoleTransport) {
    private val mutableData = MutableStateFlow(ConsoleData())
    val data: StateFlow<ConsoleData> = mutableData.asStateFlow()
    private val mutableConnection = MutableStateFlow(Connection.CONNECTING)
    val connection: StateFlow<Connection> = mutableConnection.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    /** Whether a snapshot has arrived; before that the pages show that they are loading. */
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()
    private val mutableProblem = MutableStateFlow<String?>(null)
    /** Why the stream is down, while it is. */
    val problem: StateFlow<String?> = mutableProblem.asStateFlow()

    /** Collects the stream until cancelled; the transport reopens it after a drop. */
    suspend fun follow() {
        mutableConnection.value = Connection.CONNECTING
        transport.events().collect { event ->
            when (event) {
                ConsoleStreamEvent.Opened -> { mutableConnection.value = Connection.LIVE; mutableProblem.value = null }
                is ConsoleStreamEvent.Lost -> { mutableConnection.value = Connection.LOST; mutableProblem.value = event.message }
                is ConsoleStreamEvent.Message -> {
                    val next = mutableData.value.applyOrNull(event.name, event.data)
                    if (next == null) mutableProblem.value = "服务器发来的数据无法识别，请更新护肝宝"
                    else { mutableData.value = next; if (event.name == "snapshot") mutableLoaded.value = true }
                }
            }
        }
    }

    /** Forgets everything shown, for the next user to sign in. */
    fun clear() { mutableData.value = ConsoleData(); mutableLoaded.value = false; mutableConnection.value = Connection.CONNECTING }
}
