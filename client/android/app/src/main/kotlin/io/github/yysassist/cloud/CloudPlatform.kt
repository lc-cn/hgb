package io.github.yysassist.cloud

import io.github.yysassist.core.content.userTasks
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.RuntimeCommandResult
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.TaskUpdates
import io.github.yysassist.VersionCatalogState
import io.github.yysassist.core.cloud.*
import io.github.yysassist.protocol.*
import io.github.yysassist.core.execution.EnqueueResult
import io.github.yysassist.core.execution.ExecutorCommand
import io.github.yysassist.core.execution.ExecutorReply
import io.github.yysassist.storage.ActiveTaskContent
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sign-in tokens stay encrypted with a key that cannot leave this device's keystore. */
internal class EncryptedCloudStore(private val file: File, private val keyAlias: String) : CloudCredentialStore {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }
    private val aad = "io.github.yysassist/cloud-session/v1".toByteArray()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun read(): CloudCredentials? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val buffer = ByteBuffer.wrap(atomic.readFully())
        val iv = ByteArray(12).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(aad) }
        val plain = ByteArray(buffer.remaining()).also(buffer::get)
        json.decodeFromString(CloudCredentials.serializer(), cipher.doFinal(plain).decodeToString())
    }
    override suspend fun write(value: CloudCredentials?) = withContext(Dispatchers.IO) {
        if (value == null) { atomic.delete(); return@withContext }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val sealed = cipher.doFinal(json.encodeToString(CloudCredentials.serializer(), value).toByteArray())
        val output = atomic.startWrite()
        try { output.write(cipher.iv); output.write(sealed); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}

internal class RuntimeCloudExecutor(private val runtime: ExecutionRuntime, private val updates: TaskUpdates,
    private val library: io.github.yysassist.storage.TaskVersionLibrary, private val database: io.github.yysassist.storage.AssistDatabase,
    private val observed: kotlinx.coroutines.flow.StateFlow<Map<String, ObservedAccount>>,
    private val notices: LocalNotices? = null,
    private val dev: () -> DevRunner? = { null },
    private val installed: (String) -> Boolean) : CloudExecutorPort {
    override fun develop(command: DevCommand) { dev()?.start(command) }
    override fun developing(): Boolean = dev()?.busy == true
    /** The executor waits before a step for a reason only the phone sees: no accessibility, no screen, game not in front. */
    override fun blocked(): String? = (runtime.status.value as? io.github.yysassist.RuntimeStatus.Waiting)?.message
        ?.takeIf { it in io.github.yysassist.device.CAPABILITY_WAITS }
    override fun notify(key: String, title: String, text: String): Boolean = notices?.show(key, title, text) == true
    private fun bundle() = ((updates.catalog.value as? VersionCatalogState.Ready)?.catalog?.activeContent as? ActiveTaskContent.Available)?.bundle
    override fun observedAccount(targetId: String): ObservedAccount? = observed.value[targetId]
    /** The game's own account tasks, with the nickname as the switch task's only setting. */
    override suspend fun enqueueAccountTask(targetId: String, task: AccountTask, commandId: String, digest: String, nickname: String?): CloudEnqueueResult {
        val procedures = bundle()?.targets?.firstOrNull { it.id == targetId }?.accounts ?: return CloudEnqueueResult.Rejected("这个游戏不支持识别和切换账号")
        val (taskId, settings) = when (task) {
            AccountTask.IDENTIFY -> procedures.identify to kotlinx.serialization.json.JsonObject(emptyMap())
            AccountTask.SWITCH -> procedures.switchTo to kotlinx.serialization.json.buildJsonObject {
                put(io.github.yysassist.core.content.AccountProcedures.NICKNAME_FIELD, kotlinx.serialization.json.JsonPrimitive(checkNotNull(nickname))) }
        }
        return enqueue(targetId, taskId, commandId, digest, settings)
    }
    /** One app per installed package the active bundle targets. Clones and work profiles are not told apart yet. */
    override fun apps(): List<CloudApp> = bundle()?.targets.orEmpty()
        .flatMap { target -> target.androidPackages.filter(installed).map { CloudApp(it, target.id, target.name, target.accounts != null) } }
    override suspend fun traces(runId: Long, after: Long): List<CloudTrace> = withContext(Dispatchers.IO) {
        database.actionTraces().forRun(runId).filter { it.id > after }
            .map { CloudTrace(it.id, it.recordedAt, it.stepId, it.actionId, it.result, it.reason, it.elapsedMillis) }
    }
    override val state get() = runtime.state
    override suspend fun awaitRestored() {
        runtime.status.first { it != RuntimeStatus.Loading }
        updates.catalog.first { it != VersionCatalogState.Loading }
    }
    /** Passive tasks start from what appears on screen, so they are never offered as jobs. */
    override fun tasks(): List<CloudTask> = ((updates.catalog.value as? VersionCatalogState.Ready)?.catalog?.activeContent as? ActiveTaskContent.Available)
        ?.bundle?.userTasks.orEmpty().filter { it.trigger == null }.map { CloudTask(it.targetId, it.id, it.name) }
    override suspend fun catalog(): CloudCatalog? = withContext(Dispatchers.IO) {
        runCatching { library.activeSource() }.getOrNull()?.let { CloudCatalog(it.version, it.digest, it.json) }
    }
    override suspend fun enqueue(targetId: String, taskId: String, commandId: String, digest: String, configuration: kotlinx.serialization.json.JsonObject): CloudEnqueueResult =
        when (val result = runtime.enqueueTask(TaskAddress(targetId, taskId), commandId,
            io.github.yysassist.storage.ProvidedSettings(digest, configuration)).await()) {
            is RuntimeCommandResult.Applied -> when (val reply = (result.reply as? ExecutorReply.Enqueued)?.result) {
                is EnqueueResult.Accepted -> CloudEnqueueResult.Accepted(reply.runId)
                is EnqueueResult.Duplicate -> CloudEnqueueResult.Accepted(reply.runId)
                is EnqueueResult.Rejected -> CloudEnqueueResult.Rejected(reply.reason)
                null -> CloudEnqueueResult.Unconfirmed("任务提交结果未确认")
            }
            is RuntimeCommandResult.Rejected -> CloudEnqueueResult.Rejected(result.message)
            is RuntimeCommandResult.Unconfirmed -> CloudEnqueueResult.Unconfirmed(result.message)
        }
    override fun remove(runId: Long) { runtime.submit(ExecutorCommand.Remove(runId)) }
    override fun pause(runId: Long) { runtime.submit(ExecutorCommand.PauseAfterStep(runId)) }
    override fun resume(runId: Long) { runtime.submit(ExecutorCommand.ResumeCurrent(runId, io.github.yysassist.core.execution.ResumeMode.CONTINUE)) }
}
