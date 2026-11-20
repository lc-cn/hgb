package io.github.yysassist.server

import io.github.yysassist.protocol.AuthResult
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkbenchApiTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val github = object : GitHubIdentity {
        override val clientId = "client"
        override fun exchange(code: String, verifier: String, redirectUri: String) = code
        override fun user(accessToken: String) = accessToken.takeIf { it.startsWith("gh") }?.let { GitHubUser(it.removePrefix("gh"), it.removePrefix("gh")) }
    }
    private var asked = 0
    /** GitHub logins and their permission on the task repository. */
    private val repo = RepoPermissions { _, login -> asked++; mapOf("writer" to "write", "reader" to "read")[login] ?: "none" }

    private fun api(permissions: RepoPermissions?, block: suspend ApplicationTestBuilder.() -> Unit) {
        val file = File.createTempFile("workbench", ".db")
        val db = Database(file.path)
        val service = CloudService(db, { _, _, _ -> }, github, ByteArray(32) { 7 }, { 1_000_000L })
        try { testApplication { application { cloudModule(service, ServerConfig("http://localhost", false), Workbench(db, permissions)) }; block() } }
        finally { db.close(); file.delete() }
    }
    private suspend fun ApplicationTestBuilder.signIn(login: String): String {
        val reply = client.post("/api/auth/github/token") { contentType(ContentType.Application.Json); header("X-YYS-Client", "device"); setBody("""{"accessToken":"gh$login"}""") }
        return checkNotNull(json.decodeFromString(AuthResult.serializer(), reply.bodyAsText()).accessToken)
    }
    private suspend fun ApplicationTestBuilder.get(path: String, token: String) = client.get(path) { bearerAuth(token) }
    private suspend fun ApplicationTestBuilder.check(text: String, token: String) = client.post("/api/dev/check") { bearerAuth(token); setBody(text) }

    private val target = """{"id":"game","name":"G","androidPackages":["test.game"],"identityPolicy":"NONE","categories":[{"id":"daily","name":"D"}]"""
    private fun bundle(capability: String) = """{"formatVersion":1,"version":"v1","updatedAt":"now","changelog":"","tasks":[{"id":"t","targetId":"game",
        "name":"T","category":"daily","priority":1,"steps":[{"id":"s","name":"S","program":[{"type":"action","action":{"id":"w",
        "capability":"$capability","contractVersion":1,"inputs":{"durationMs":10}}}]}]}],"targets":[$target}]}"""

    @Test fun onlyAccountsThatCanWriteTheTaskRepositoryGetIn() = api(repo) {
        assertEquals(HttpStatusCode.Forbidden, get("/api/dev/catalog", signIn("reader")).status)
        assertEquals(HttpStatusCode.Forbidden, get("/api/dev/catalog", signIn("stranger")).status)
        val writer = signIn("writer")
        val catalog = get("/api/dev/catalog", writer)
        assertEquals(HttpStatusCode.OK, catalog.status)
        val ids = Json.parseToJsonElement(catalog.bodyAsText()).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue(ids.containsAll(listOf("screen.awaitScreen", "screen.dragRegion", "control.wait", "task.result")))
        // The answer is remembered for a while, so pages do not ask GitHub on every request.
        val before = asked
        get("/api/dev/catalog", writer)
        assertEquals(before, asked)
    }

    @Test fun checksReadABundleExactlyAsTheAppWould() = api(repo) {
        val writer = signIn("writer")
        val good = Json.parseToJsonElement(check(bundle("control.wait"), writer).bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(true), good["ok"])
        val bad = Json.parseToJsonElement(check(bundle("control.nope"), writer).bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(false), bad["ok"])
        assertTrue(bad.getValue("issues").jsonArray.isNotEmpty())
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String, token: String) = client.request(path) {
        this.method = method; bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }
    private fun action(id: String, ms: Int) = buildJsonObject { put("type", "action"); putJsonObject("action") {
        put("id", id); put("capability", "control.wait"); put("contractVersion", 1); putJsonObject("inputs") { put("durationMs", ms) } } }

    @Test fun draftsMergeEditsToDifferentNodesAndRefuseStaleOnes() = api(repo) {
        val writer = signIn("writer")
        val draft = Json.parseToJsonElement(bundle("control.wait")).jsonObject
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Put, "/api/dev/drafts/game", """{"baseVersion":"v1","body":$draft}""", writer).status)
        val original = action("w", 10)
        val seen = nodeDigest(original)
        // The web page inserts a second wait after w; the phone changes w's duration, both from what they last saw.
        val web = send(HttpMethod.Post, "/api/dev/drafts/game/patches",
            """{"source":"web","patches":[{"op":"insert","path":"t/s","field":"program","after":"w","value":${action("x", 5)}}]}""", writer)
        assertEquals(HttpStatusCode.OK, web.status)
        val phone = send(HttpMethod.Post, "/api/dev/drafts/game/patches",
            """{"source":"phone","patches":[{"op":"put","path":"t/s/w","expect":"$seen","value":${action("w", 20)}}]}""", writer)
        assertEquals(HttpStatusCode.OK, phone.status)
        val body = Json.parseToJsonElement(get("/api/dev/drafts/game", writer).bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(3), body["revision"])
        val program = body.getValue("body").jsonObject.getValue("tasks").jsonArray[0].jsonObject.getValue("steps").jsonArray[0].jsonObject.getValue("program").jsonArray
        assertEquals(listOf(action("w", 20), action("x", 5)), program.toList())
        // Someone still holding the old w loses: nothing applies, and they get w as it is now.
        val stale = send(HttpMethod.Post, "/api/dev/drafts/game/patches",
            """{"patches":[{"op":"delete","path":"t/s/x","expect":"${nodeDigest(action("x", 5))}"},{"op":"put","path":"t/s/w","expect":"$seen","value":${action("w", 30)}}]}""", writer)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val conflict = Json.parseToJsonElement(stale.bodyAsText()).jsonObject.getValue("conflicts").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("t/s/w"), conflict["path"])
        assertEquals(JsonPrimitive(nodeDigest(action("w", 20))), conflict["digest"])
        val history = Json.parseToJsonElement(get("/api/dev/drafts/game/patches?since=1", writer).bodyAsText()).jsonObject
        assertEquals(2, history.getValue("records").jsonArray.size)
        assertEquals(JsonPrimitive(true), history["complete"])
        // Renaming the step (its own field) does not conflict with the insert into its program.
        val step = body.getValue("body").jsonObject.getValue("tasks").jsonArray[0].jsonObject.getValue("steps").jsonArray[0].jsonObject
        val rename = send(HttpMethod.Post, "/api/dev/drafts/game/patches",
            """{"patches":[{"op":"set","path":"t/s","expect":"${shallowDigest(step)}","value":{"name":"改名"}}]}""", writer)
        assertEquals(HttpStatusCode.OK, rename.status)
        // Inserting into something that is not a list is refused rather than overwriting it.
        val clobber = send(HttpMethod.Post, "/api/dev/drafts/game/patches",
            """{"patches":[{"op":"insert","path":"t","field":"name","value":${action("y", 1)}}]}""", writer)
        assertEquals(HttpStatusCode.Conflict, clobber.status)
        // Importing again is recorded, so an editor following patches knows to reload.
        send(HttpMethod.Put, "/api/dev/drafts/game", """{"baseVersion":"v1","body":$draft}""", writer)
        val after = Json.parseToJsonElement(get("/api/dev/drafts/game/patches?since=4", writer).bodyAsText()).jsonObject.getValue("records").jsonArray
        assertEquals(JsonPrimitive(true), after.single().jsonObject["replaced"])
        // The draft still reads as a valid bundle.
        val checked = Json.parseToJsonElement(check(Json.encodeToString(JsonObject.serializer(), body.getValue("body").jsonObject), writer).bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(true), checked["ok"])
    }

    @Test fun theDecisionFollowsTheLinkedGitHubAccount() = api(repo) {
        val writer = signIn("writer")
        assertEquals(HttpStatusCode.OK, get("/api/dev/catalog", writer).status)
        // Each GitHub account is decided on its own; a cached yes for one never lets another in.
        val reader = signIn("reader")
        assertEquals(HttpStatusCode.Forbidden, get("/api/dev/catalog", reader).status)
    }

    @Test fun withoutARepositoryTokenTheWorkbenchSaysItIsNotConfigured() = api(null) {
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/api/dev/catalog", signIn("writer")).status)
    }
}
