package io.github.yysassist.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.yysassist.core.auth.GitHubToken
import java.io.*
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal enum class GitHubRenewal { IDLE, IN_FLIGHT, REAUTHORIZE }
internal data class StoredGitHubSession(val id: String, val clientId: String, val access: String,
    val accessExpiresAt: Long?, val refresh: String?, val refreshExpiresAt: Long?,
    val renewal: GitHubRenewal = GitHubRenewal.IDLE, val login: String? = null, val accessRejected: Boolean = false) {
    val refreshPending get() = renewal == GitHubRenewal.IN_FLIGHT
    override fun toString() = "StoredGitHubSession(<redacted>)"
}
internal interface GitHubSessionStore {
    suspend fun read(): StoredGitHubSession?
    suspend fun write(record: StoredGitHubSession?)
}

/** One application owner serializes this store. No raw credential file or backup/export path. */
internal class EncryptedGitHubSessionStore(private val file: File, private val keyAlias: String) : GitHubSessionStore {
    private val atomic = AtomicFile(file)
    private val aad = "io.github.yysassist/github-session/v1".toByteArray(Charsets.UTF_8)
    override suspend fun read(): StoredGitHubSession? = withContext(Dispatchers.IO) {
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).all { Files.notExists(it.toPath()) }) return@withContext null
        try {
            val input = DataInputStream(ByteArrayInputStream(atomic.readFully()))
            check(input.readInt() == 1 && input.readInt() == 12)
            val iv = ByteArray(12).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
            decode(cipher.doFinal(input.readBytes()).toString(Charsets.UTF_8))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException("GitHub 登录资料无法读取，原记录已保留") }
    }
    override suspend fun write(record: StoredGitHubSession?) = withContext(Dispatchers.IO) {
        try {
            val plain = encode(record)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
                it.writeInt(1); it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted)
            } }.toByteArray()
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (failed: Exception) { atomic.failWrite(stream); throw failed }
            check(read() == record)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException("GitHub 登录资料保存结果暂无法确认") }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Missing local key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encode(record: StoredGitHubSession?): String = buildJsonObject {
        put("formatVersion", 1)
        put("session", record?.let { value -> buildJsonObject {
            put("id", value.id); put("clientId", value.clientId); put("access", value.access)
            put("accessExpiresAt", value.accessExpiresAt?.let(::JsonPrimitive) ?: JsonNull)
            put("refresh", value.refresh?.let(::JsonPrimitive) ?: JsonNull)
            put("refreshExpiresAt", value.refreshExpiresAt?.let(::JsonPrimitive) ?: JsonNull)
            put("renewal", value.renewal.name); put("login", value.login?.let(::JsonPrimitive) ?: JsonNull)
            put("accessRejected", value.accessRejected)
        } } ?: JsonNull)
    }.toString()
    private fun decode(text: String): StoredGitHubSession? {
        val root = Json.parseToJsonElement(text).jsonObject
        check(root.getValue("formatVersion").jsonPrimitive.int == 1)
        val value = root.getValue("session").takeUnless { it == JsonNull }?.jsonObject ?: return null
        fun string(name: String) = value.getValue(name).jsonPrimitive.content
        fun optionalString(name: String) = value.getValue(name).jsonPrimitive.contentOrNull
        fun optionalLong(name: String) = value.getValue(name).takeUnless { it == JsonNull }?.jsonPrimitive?.long
        val record = StoredGitHubSession(string("id"), string("clientId"), GitHubToken(string("access")).value,
            optionalLong("accessExpiresAt"), optionalString("refresh")?.let { GitHubToken(it).value }, optionalLong("refreshExpiresAt"),
            value["renewal"]?.jsonPrimitive?.content?.let(GitHubRenewal::valueOf)
                ?: if (value["refreshPending"]?.jsonPrimitive?.boolean == true) GitHubRenewal.IN_FLIGHT else GitHubRenewal.IDLE,
            optionalString("login"), value["accessRejected"]?.jsonPrimitive?.boolean ?: false)
        check(record.id.isNotBlank() && record.clientId.isNotBlank())
        check((record.accessExpiresAt == null) == (record.refresh == null) && (record.refresh == null) == (record.refreshExpiresAt == null))
        check(!record.refreshPending || record.refresh != null)
        return record
    }
}
