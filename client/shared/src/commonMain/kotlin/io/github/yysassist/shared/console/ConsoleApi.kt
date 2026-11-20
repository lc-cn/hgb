package io.github.yysassist.shared.console

import io.github.yysassist.protocol.*
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * The requests the console's pages make, the same ones the web console makes (client/web/src/features). Event rules
 * and channels are set up on the web only (C38), so they are not here.
 */
class ConsoleApi(private val transport: ConsoleTransport) {
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    private suspend inline fun <reified T> get(path: String): T = consoleJson.decodeFromString(checkNotNull(transport.request("GET", path)))
    private suspend inline fun <reified B> send(method: String, path: String, body: B) { transport.request(method, path, consoleJson.encodeToString(body)) }
    private suspend fun post(path: String) { transport.request("POST", path, "{}") }

    suspend fun me(): AccountView = get("/api/me")
    suspend fun setTimeZone(zone: String) = send("PUT", "/api/me/time-zone", TimeZoneSave(zone))
    suspend fun unlinkGitHub() { transport.request("DELETE", "/api/auth/github") }
    suspend fun requestEmailBind(email: String) = send("POST", "/api/me/email/request", CodeRequest(email))
    suspend fun verifyEmailBind(email: String, code: String): EmailBindResult =
        consoleJson.decodeFromString(checkNotNull(transport.request("POST", "/api/me/email/verify", consoleJson.encodeToString(VerifyEmailRequest(email, code)))))
    suspend fun merge(token: String): AccountView = consoleJson.decodeFromString(checkNotNull(transport.request("POST", "/api/me/merge/${enc(token)}", "{}")))
    suspend fun declineMerge(token: String) { transport.request("DELETE", "/api/me/merge/${enc(token)}") }
    suspend fun requestDeletionCode() = post("/api/me/delete/request")
    suspend fun deleteAccount(confirm: String, code: String?) = send("POST", "/api/me/delete", AccountDeletion(confirm, code))
    suspend fun removeDevice(id: String) { transport.request("DELETE", "/api/devices/${enc(id)}") }
    /** Which account the app is signed in to, and which one it returns to after a job. */
    suspend fun setAppAccounts(deviceId: String, appKey: String, accounts: AppAccounts) =
        send("PUT", "/api/devices/${enc(deviceId)}/apps/${enc(appKey)}", accounts)

    suspend fun createAccount(save: GameAccountSave): GameAccountView =
        consoleJson.decodeFromString(checkNotNull(transport.request("POST", "/api/accounts", consoleJson.encodeToString(save))))
    suspend fun changeAccount(id: String, save: GameAccountSave) = send("PUT", "/api/accounts/${enc(id)}", save)
    suspend fun deleteAccount(id: String) { transport.request("DELETE", "/api/accounts/${enc(id)}") }

    suspend fun config(accountId: String, task: CloudTask): ConfigView = get(configPath(accountId, task))
    suspend fun saveConfig(accountId: String, task: CloudTask, values: JsonObject, revision: Long): ConfigView =
        consoleJson.decodeFromString(checkNotNull(transport.request("PUT", configPath(accountId, task), consoleJson.encodeToString(ConfigSave(values, revision)))))
    private fun configPath(accountId: String, task: CloudTask) = "/api/accounts/${enc(accountId)}/configs/${enc(task.targetId)}/${enc(task.taskId)}"

    suspend fun createPlan(accountId: String, save: PlanSave) = send("POST", "/api/accounts/${enc(accountId)}/plans", save)
    suspend fun changePlan(id: String, save: PlanSave) = send("PUT", "/api/plans/${enc(id)}", save)
    suspend fun deletePlan(id: String) { transport.request("DELETE", "/api/plans/${enc(id)}") }
    suspend fun runPlan(id: String) = post("/api/plans/${enc(id)}/run")

    /** Runs a task for an account; without [deviceId] the server picks an app signed in to it. */
    suspend fun run(request: CreateJobRequest) = send("POST", "/api/jobs", request)
    suspend fun pause(job: String) = post("/api/jobs/${enc(job)}/pause")
    suspend fun resume(job: String) = post("/api/jobs/${enc(job)}/resume")
    suspend fun cancel(job: String) = post("/api/jobs/${enc(job)}/cancel")
    suspend fun jobEvents(job: String): List<JobEventView> = get("/api/jobs/${enc(job)}/events")

    /** Notices newest first; [after] continues a page from its last notice. */
    suspend fun notices(after: NoticeView? = null, rule: String? = null): List<NoticeView> {
        val query = listOfNotNull(rule?.let { "rule=${enc(it)}" }, after?.let { "after=${enc("${it.at}:${it.id}")}" }).joinToString("&")
        return get("/api/notifications/events" + if (query.isEmpty()) "" else "?$query")
    }
    suspend fun rules(): List<EventRuleView> = get("/api/notifications/rules")
    suspend fun retryDelivery(id: String) = post("/api/notifications/deliveries/${enc(id)}/retry")
}
