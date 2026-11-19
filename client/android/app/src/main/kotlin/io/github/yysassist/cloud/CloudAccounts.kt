package io.github.yysassist.cloud

import android.util.AtomicFile
import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.cloud.CloudApp
import io.github.yysassist.core.cloud.CloudDataStore
import io.github.yysassist.core.cloud.CloudState
import io.github.yysassist.protocol.AccountConfig
import io.github.yysassist.protocol.DeviceData
import io.github.yysassist.protocol.GameAccountView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** The last data the server sent, kept in the app's own storage so it can be shown and run with offline. */
internal class CloudDataFile(file: File) : CloudDataStore {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun read(): DeviceData? = withContext(Dispatchers.IO) {
        if (!atomic.baseFile.exists()) null else json.decodeFromString(DeviceData.serializer(), atomic.readFully().decodeToString())
    }
    override suspend fun write(value: DeviceData?) = withContext(Dispatchers.IO) {
        if (value == null) { atomic.delete(); return@withContext }
        val output = atomic.startWrite()
        try { output.write(json.encodeToString(DeviceData.serializer(), value).toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}

/**
 * Which game account each game on this device is signed in to, and that account's settings, as the server last said.
 * The user says which account is signed in until the app can read it from the game.
 */
class CloudAccounts(val agent: CloudAgent, private val installed: () -> List<CloudApp>) {
    val data get() = agent.data
    val online: Boolean get() = (agent.state.value as? CloudState.SignedIn)?.online == true
    val signedIn: Boolean get() = agent.state.value is CloudState.SignedIn
    /** The games on this device that the active task version supports. */
    fun apps(): List<CloudApp> = installed()
    /** The app of [targetId] on this device. A phone has one per game until clones are told apart. */
    fun app(targetId: String): CloudApp? = apps().firstOrNull { it.gameId == targetId }
    fun accounts(targetId: String, data: DeviceData? = agent.data.value): List<GameAccountView> = data?.accounts.orEmpty().filter { it.gameId == targetId }
    fun current(targetId: String, data: DeviceData? = agent.data.value): GameAccountView? {
        val app = app(targetId) ?: return null
        val id = data?.apps?.firstOrNull { it.key == app.key }?.accountId ?: return null
        return data.accounts.firstOrNull { it.id == id }
    }
    /** The current account's saved settings for the task, or null when none are saved or no account is set. */
    fun config(targetId: String, taskId: String, data: DeviceData? = agent.data.value): AccountConfig? {
        val account = current(targetId, data) ?: return null
        return data?.configs?.firstOrNull { it.accountId == account.id && it.targetId == targetId && it.taskId == taskId }
    }
}
