package io.github.yysassist.auth

import io.github.yysassist.core.auth.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface GitHubSessionState {
    data object Loading : GitHubSessionState
    data class SignedOut(val message: String? = null) : GitHubSessionState
    data class SigningIn(val prompt: GitHubDevicePrompt? = null) : GitHubSessionState
    data object Saving : GitHubSessionState
    data class Connected(val login: String?, val checking: Boolean = false, val message: String? = null) : GitHubSessionState
    data class Failure(val message: String) : GitHubSessionState
}

/** One App-owned credential owner. Screen/request cancellation cannot abandon a refresh rotation. */
internal class GitHubSession(private val clientId: String, private val store: GitHubSessionStore,
    private val protocol: GitHubDeviceAuthorization, private val users: GitHubUserApi,
    private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) : GitHubAuthorization {
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var record: StoredGitHubSession? = null
    private var issuedToken: GitHubToken? = null
    private var storageFault = false
    private var loginEpoch: Any? = null
    private var loginView: GitHubSessionState? = null
    private var loginJob: Job? = null
    private var lookupJob: Job? = null
    private val mutableState = MutableStateFlow<GitHubSessionState>(GitHubSessionState.Loading)
    val state = mutableState.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            try { mutex.withLock { record = store.read(); publish() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageFault = true; publish("GitHub 登录资料无法读取，原记录已保留") }
            finally { ready.complete(Unit) }
        }
    }
    private fun publish(message: String? = null, checking: Boolean = false) {
        val saved = record
        val at = now()
        mutableState.value = when {
            loginEpoch != null -> checkNotNull(loginView)
            storageFault -> GitHubSessionState.Failure(message ?: "GitHub 登录资料暂不可用，原记录已保留")
            saved == null -> GitHubSessionState.SignedOut(message)
            saved.clientId != clientId -> GitHubSessionState.SignedOut("GitHub 登录配置已变，请重新登录")
            saved.refreshPending -> GitHubSessionState.SignedOut(message ?: "上次登录续期结果未确认，请重新登录")
            saved.renewal == GitHubRenewal.REAUTHORIZE -> GitHubSessionState.SignedOut(message ?: "GitHub 登录已失效，请重新登录")
            !usable(saved, at) && !renewable(saved, at) -> GitHubSessionState.SignedOut(message ?: "GitHub 登录已失效，请重新登录")
            else -> GitHubSessionState.Connected(saved.login, checking = checking, message = message)
        }
    }
    /** All writes are reconciled before in-memory admission. A failed read is never absence. */
    private suspend fun persist(next: StoredGitHubSession?) = withContext(NonCancellable + Dispatchers.IO) {
        try { store.write(next) }
        catch (_: Exception) {
            val observed = try { store.read() } catch (_: Exception) { storageFailure() }
            if (observed != next) storageFailure()
        }
        if (next?.id != record?.id || next?.access != record?.access) issuedToken = null
        record = next; storageFault = false
    }
    private fun storageFailure(): Nothing {
        storageFault = true
        publish("GitHub 登录保存结果暂无法确认，请重新检查")
        throw GitHubAuthException(GitHubAuthProblem.STORAGE, "GitHub 登录资料暂不可用，原记录已保留")
    }
    override suspend fun token(): GitHubToken? {
        ready.await()
        // This child belongs to the App, not to the requesting pull/screen coroutine.
        return scope.async(Dispatchers.IO) { mutex.withLock {
            if (storageFault) throw GitHubAuthException(GitHubAuthProblem.STORAGE, "GitHub 登录资料暂不可用，请重新检查")
            val saved = record ?: return@withLock null
            if (saved.clientId != clientId || saved.renewal != GitHubRenewal.IDLE) throw GitHubAuthException(GitHubAuthProblem.EXPIRED, "GitHub 登录需要重新授权")
            if (usable(saved, now())) return@withLock issue(saved)
            val refresh = saved.refresh?.takeIf { renewable(saved, now()) }
                ?: run { publish("GitHub 登录已失效，请重新登录")
                    throw GitHubAuthException(GitHubAuthProblem.EXPIRED, "GitHub 登录已失效，请重新登录") }
            persist(saved.copy(renewal = GitHubRenewal.IN_FLIGHT))
            try {
                val start = now()
                val renewed = protocol.refresh(GitHubToken(refresh))
                persist(stored(renewed, start, login = saved.login)); publish()
                issue(record!!)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is GitHubAuthException && failure.problem in setOf(GitHubAuthProblem.EXPIRED, GitHubAuthProblem.DENIED, GitHubAuthProblem.CONFIGURATION))
                    persist(checkNotNull(record).copy(renewal = GitHubRenewal.REAUTHORIZE))
                publish(if (failure is GitHubAuthException) failure.message else "GitHub 登录续期结果未确认，请重新登录")
                throw if (failure is GitHubAuthException) failure else GitHubAuthException(GitHubAuthProblem.NETWORK, "GitHub 登录续期结果未确认，请重新登录")
            }
        } }.await()
    }
    override suspend fun rejected(token: GitHubToken) {
        ready.await()
        scope.async(Dispatchers.IO) { mutex.withLock {
            val saved = record ?: return@withLock
            if (storageFault || issuedToken !== token || saved.renewal != GitHubRenewal.IDLE) return@withLock
            try { persist(saved.copy(accessRejected = true)); publish("GitHub 授权需要重新验证") }
            catch (_: GitHubAuthException) { /* persist already exposed the storage fault; preserve the HTTP result. */ }
        } }.await()
    }
    private fun issue(saved: StoredGitHubSession): GitHubToken = issuedToken ?: GitHubToken(saved.access).also { issuedToken = it }
    private fun usable(saved: StoredGitHubSession, at: Long) = !saved.accessRejected && (saved.accessExpiresAt == null || at < saved.accessExpiresAt)
    private fun renewable(saved: StoredGitHubSession, at: Long) = saved.refresh != null && saved.refreshExpiresAt != null && at < saved.refreshExpiresAt
    fun signIn() {
        scope.launch(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                if (loginJob?.isActive == true) return@withLock
                lookupJob?.cancel(); lookupJob = null
                val epoch = Any(); loginEpoch = epoch
                loginView = GitHubSessionState.SigningIn(); publish()
                loginJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    val owner = currentCoroutineContext()[Job]
                    try {
                        var requestedAt = now()
                        val tokens = protocol.authorize(beforeTokenRequest = { requestedAt = now() }) { prompt -> mutex.withLock {
                            if (loginEpoch === epoch) { loginView = GitHubSessionState.SigningIn(prompt); publish() }
                        } }
                        withContext(NonCancellable) { mutex.withLock {
                            if (loginEpoch === epoch) {
                                loginView = GitHubSessionState.Saving; publish()
                                persist(stored(tokens, requestedAt)); loginEpoch = null; loginView = null; publish()
                            }
                        } }
                        checkAccount()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { mutex.withLock {
                        if (loginEpoch === epoch) {
                            loginEpoch = null; loginView = null
                            publish(if (failure is GitHubAuthException) failure.message else "GitHub 登录未完成，请重试")
                        }
                    } }
                    finally { withContext(NonCancellable) { mutex.withLock {
                        if (loginJob === owner) loginJob = null
                        if (loginEpoch === epoch) { loginEpoch = null; loginView = null }
                    } } }
                }.also { it.start() }
            }
        }
    }
    fun cancelSignIn() {
        scope.launch(Dispatchers.IO) { ready.await(); mutex.withLock {
            if (mutableState.value !is GitHubSessionState.SigningIn) return@withLock
            loginEpoch = null; loginView = null; loginJob?.cancel(); loginJob = null; publish()
        } }
    }
    fun signOut() {
        scope.launch(Dispatchers.IO) { ready.await(); mutex.withLock {
            loginEpoch = null; loginView = null; loginJob?.cancel(); loginJob = null; lookupJob?.cancel(); lookupJob = null
            try { persist(null); publish() } catch (_: GitHubAuthException) { /* Keep the storage fault visible. */ }
        } }
    }
    fun checkAccount() {
        scope.launch(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                if (lookupJob?.isActive == true || loginEpoch != null || storageFault || record == null) return@withLock
                lookupJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    var used: GitHubToken? = null; var sessionId: String? = null
                    try {
                        val activeToken = token() ?: return@launch
                        used = activeToken
                        mutex.withLock {
                            val saved = record?.takeIf { it.access == activeToken.value } ?: return@launch
                            sessionId = saved.id; publish(checking = true)
                        }
                        val login = users.login(activeToken)
                        mutex.withLock {
                            val saved = record?.takeIf { it.id == sessionId && it.access == activeToken.value } ?: return@withLock
                            persist(saved.copy(login = login)); publish()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failure is GitHubAuthException && failure.httpCode == 401) used?.let { rejected(it) }
                        mutex.withLock {
                            if (!storageFault && record?.id == sessionId)
                                publish(if (failure is GitHubAuthException) failure.message else "GitHub 账号资料暂无法读取")
                        }
                    }
                }.also { it.start() }
            }
        }
    }
    private fun stored(tokens: GitHubUserTokens, started: Long, id: String = UUID.randomUUID().toString(), login: String? = null): StoredGitHubSession {
        fun deadline(seconds: Long?) = seconds?.let { Math.addExact(started, Math.multiplyExact(it, 1_000)) }
        return StoredGitHubSession(id, clientId, tokens.accessToken.value, deadline(tokens.expiresInSeconds), tokens.refreshToken?.value,
            deadline(tokens.refreshExpiresInSeconds), login = login)
    }
}
