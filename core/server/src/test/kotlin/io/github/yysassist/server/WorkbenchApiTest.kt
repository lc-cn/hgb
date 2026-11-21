package io.github.yysassist.server

import io.github.yysassist.protocol.AuthResult
import io.github.yysassist.protocol.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
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
    private val repo = RepoPermissions { _, login -> asked++; mapOf("writer" to "write", "second" to "admin", "reader" to "read")[login] ?: "none" }

    /** A task repository's releases in memory. */
    private class FakeHost : ReleaseHost {
        val list = mutableListOf<HostRelease>()
        val bodies = mutableMapOf<Long, String>()
        val files = mutableMapOf<Pair<Long, String>, ByteArray>()
        var latest: Boolean? = null
        var failUpload = false
        override fun releases() = list.reversed()
        override fun create(tag: String, body: String): Long { val id = (list.maxOfOrNull { it.id } ?: 0) + 1; list += HostRelease(id, tag, true, emptyList()); bodies[id] = body; return id }
        override fun upload(release: Long, name: String, bytes: ByteArray, type: String) {
            if (failUpload) throw ApiException(502, "github_failed", "boom")
            files[release to name] = bytes
            list.replaceAll { if (it.id == release) it.copy(assets = it.assets + HostAsset(name, bytes.size.toLong(), sha(bytes))) else it }
        }
        override fun publish(release: Long, latest: Boolean) { this.latest = latest; list.replaceAll { if (it.id == release) it.copy(draft = false) else it } }
        override fun discard(release: Long) { list.removeAll { it.id == release } }
    }
    private val host = FakeHost()

    private fun api(permissions: RepoPermissions?, block: suspend ApplicationTestBuilder.() -> Unit) {
        val file = File.createTempFile("workbench", ".db")
        val dir = kotlin.io.path.createTempDirectory("workbench").toFile()
        val db = Database(file.path)
        val service = CloudService(db, { _, _, _ -> }, github, ByteArray(32) { 7 }, { 1_000_000L })
        try { testApplication { application { cloudModule(service, ServerConfig("http://localhost", false), Workbench(db, permissions, dir, host)) }; block() } }
        finally { db.close(); file.delete(); dir.deleteRecursively() }
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

    private fun png(width: Int, height: Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 7 + y * 13) and 0xffffff)
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun body(reply: HttpResponse) = kotlinx.coroutines.runBlocking { Json.parseToJsonElement(reply.bodyAsText()).jsonObject }

    @Test fun templatesAreCutSmallFromSamplesAndNeverOverPlayerInformation() = api(repo) {
        val writer = signIn("writer")
        val upload = client.post("/api/dev/games/game/samples?name=寮&device=phone") { bearerAuth(writer); contentType(ContentType.Image.PNG); setBody(png(200, 100)) }
        assertEquals(HttpStatusCode.Created, upload.status)
        val sample = body(upload).getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/dev/games/game/samples") { bearerAuth(writer); setBody("not a png".toByteArray()) }.status)
        val other = signIn("second")
        send(HttpMethod.Patch, "/api/dev/samples/$sample", """{"texts":[{"text":"寄养","box":[10,10,20,10]}],"privateRegions":[[150,0,50,30]]}""", writer)
        assertEquals(HttpStatusCode.NotFound, get("/api/dev/samples/$sample", other).status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Patch, "/api/dev/samples/$sample", """{"name":"x"}""", other).status)
        // A tiny file claiming a huge picture is refused before it is decoded.
        val bomb = png(1, 1).also { b -> java.nio.ByteBuffer.wrap(b, 16, 8).putInt(60000).putInt(60000) }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/dev/games/game/samples") { bearerAuth(writer); setBody(bomb) }.status)
        fun cut(id: String, rect: String, extra: String = "") = """{"id":"$id","sample":"$sample","rect":$rect$extra}"""
        // Others cannot see or touch it, and nothing is cut before the phone said what it recognized.
        assertEquals(HttpStatusCode.Forbidden, get("/api/dev/samples/$sample", signIn("reader")).status)
        val fresh = body(client.post("/api/dev/games/game/samples") { bearerAuth(writer); contentType(ContentType.Image.PNG); setBody(png(20, 20)) }).getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/dev/games/game/templates", """{"id":"early","sample":"$fresh","rect":[0,0,8,8]}""", writer).status)
        // Text inside the box must be confirmed as the game's own.
        val unconfirmed = send(HttpMethod.Post, "/api/dev/games/game/templates", cut("foster", "[0,0,40,40]"), writer)
        assertEquals(HttpStatusCode.Conflict, unconfirmed.status)
        assertTrue(body(unconfirmed).toString().contains("寄养"))
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/dev/games/game/templates", cut("name", "[140,0,20,20]"), writer).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/dev/games/game/templates", cut("wide", "[0,50,100,20]"), writer).status)
        val made = send(HttpMethod.Post, "/api/dev/games/game/templates", cut("foster", "[0,0,40,40]", ",\"confirmedTexts\":[\"寄养\"]"), writer)
        assertEquals(HttpStatusCode.Created, made.status)
        val template = body(made)
        assertTrue(template.getValue("assetName").jsonPrimitive.content.matches(Regex("foster\\.[0-9a-f]{8}\\.png")))
        val image = javax.imageio.ImageIO.read(client.get("/api/dev/games/game/templates/foster/image") { bearerAuth(writer) }.readRawBytes().inputStream())
        assertEquals(40 to 40, image.width to image.height)
        // A wide one with a reason is kept; the same picture under another name is pointed out.
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/dev/games/game/templates", cut("wide", "[0,50,100,20]", ",\"reason\":\"整条标题\""), writer).status)
        send(HttpMethod.Post, "/api/dev/games/game/templates", cut("again", "[0,0,40,40]", ",\"confirmedTexts\":[\"寄养\"]"), writer)
        val listed = Json.parseToJsonElement(get("/api/dev/games/game/templates", writer).bodyAsText()).jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(JsonArray(listOf(JsonPrimitive("again"))), listed.getValue("foster").jsonObject["sameAs"])
        // Browsers send DELETE without a length.
        assertEquals(HttpStatusCode.OK, client.delete("/api/dev/games/game/templates/again") { bearerAuth(writer) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/dev/samples/$sample") { bearerAuth(writer) }.status)
        assertEquals(HttpStatusCode.NotFound, get("/api/dev/samples/$sample", writer).status)
    }

    @Test fun publishingUploadsOnlyNewAssetsAndOldAppsKeepTheirVersion() = api(repo) {
        val writer = signIn("writer")
        val model = "model".toByteArray()
        host.list += HostRelease(100, "models-1", false, listOf(HostAsset("det.onnx", model.size.toLong(), sha(model))))
        client.post("/api/dev/games/game/samples") { bearerAuth(writer); contentType(ContentType.Image.PNG); setBody(png(64, 64)) }
        val sample = Json.parseToJsonElement(get("/api/dev/games/game/samples", writer).bodyAsText()).jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        send(HttpMethod.Patch, "/api/dev/samples/$sample", """{"texts":[]}""", writer)
        val template = body(send(HttpMethod.Post, "/api/dev/games/game/templates", """{"id":"plus","sample":"$sample","rect":[0,0,16,16]}""", writer))
        // The same picture under a second name: each keeps its own attachment on every publish.
        val twin = body(send(HttpMethod.Post, "/api/dev/games/game/templates", """{"id":"twin","sample":"$sample","rect":[0,0,16,16]}""", writer))
        fun entry(t: JsonObject) = """{"id":"${t.getValue("id").jsonPrimitive.content}","assetName":"${t.getValue("assetName").jsonPrimitive.content}","bytes":${t.getValue("bytes")},"sha256":"${t.getValue("sha256").jsonPrimitive.content}"}"""
        val resources = """[{"id":"det","assetName":"det.onnx","bytes":${model.size},"sha256":"${sha(model)}"},${entry(template)},${entry(twin)}]"""
        val draft = Json.parseToJsonElement(bundle("control.wait")).jsonObject.let { JsonObject(it + ("resources" to Json.parseToJsonElement(resources))) }
        send(HttpMethod.Put, "/api/dev/drafts/game", """{"baseVersion":"v1","body":$draft}""", writer)
        val request = """{"version":"v1.2.0","changelog":"寄养","minApp":"0.2.0""""
        val plan = body(send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request,\"dryRun\":true}", writer))
        assertEquals(JsonPrimitive(false), plan["published"])
        assertEquals(listOf("plus", "twin"), plan.getValue("uploads").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request}", signIn("reader")).status)
        // Old Apps read the latest release and know nothing of assets kept elsewhere.
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request,\"latest\":true}", writer).status)
        assertEquals(JsonPrimitive("models-1"), plan.getValue("reused").jsonArray.single().jsonObject["release"])
        assertEquals(1, host.list.size)
        // A failed upload leaves no half-made release behind.
        host.failUpload = true
        assertEquals(HttpStatusCode.BadGateway, send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request}", writer).status)
        assertEquals(1, host.list.size)
        host.failUpload = false
        val done = send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request}", writer)
        assertEquals(HttpStatusCode.OK, done.status)
        val release = host.list.last()
        assertEquals("v1.2.0", release.tag)
        assertFalse(release.draft)
        assertEquals(false, host.latest)
        assertTrue(host.bodies.getValue(release.id).lines().contains("min-app: 0.2.0"))
        val published = Json.parseToJsonElement(host.files.getValue(release.id to "task-bundle.json").decodeToString()).jsonObject
        val placed = published.getValue("resources").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(JsonPrimitive("models-1"), placed.getValue("det").jsonObject["release"])
        assertNull(placed.getValue("plus").jsonObject["release"])
        assertEquals(setOf("task-bundle.json", template.getValue("assetName").jsonPrimitive.content, twin.getValue("assetName").jsonPrimitive.content),
            host.files.keys.filter { it.first == release.id }.map { it.second }.toSet())
        // The same version twice is refused; the next one reuses the template now in v1.2.0.
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/dev/drafts/game/publish", "$request}", writer).status)
        // An interrupted publish leaves a draft of its version; publishing again clears it. A min-app line in the notes is dropped.
        host.list += HostRelease(50, "v1.2.1", true, emptyList())
        val next = body(send(HttpMethod.Post, "/api/dev/drafts/game/publish", """{"version":"v1.2.1","changelog":"修\nmin-app: 0.0.1","minApp":"0.2.0"}""", writer))
        assertEquals(JsonPrimitive(true), next["published"])
        assertEquals(listOf("v1.2.1"), host.list.filter { it.tag == "v1.2.1" }.map { it.tag })
        assertFalse(host.bodies.getValue(host.list.last().id).contains("0.0.1"))
        assertTrue(next.getValue("uploads").jsonArray.isEmpty())
        assertEquals(3, next.getValue("reused").jsonArray.map { it.jsonObject.getValue("assetName").jsonPrimitive.content }.toSet().size)
        assertEquals(setOf("models-1", "v1.2.0"), next.getValue("reused").jsonArray.map { it.jsonObject.getValue("release").jsonPrimitive.content }.toSet())
    }

    @Test fun phoneCommandsGoOnlyToAnIdleConnectedPhoneAndItsReportComesBack() = api(repo) {
        val writer = signIn("writer")
        send(HttpMethod.Put, "/api/dev/drafts/game", """{"baseVersion":"v1","body":${bundle("control.wait")}}""", writer)
        val device = body(send(HttpMethod.Post, "/api/device/register", """{"installId":"i1","name":"手机","platform":"android"}""", writer).also {
            assertEquals(HttpStatusCode.OK, it.status) }).getValue("deviceId").jsonPrimitive.content
        val start = """{"kind":"trial","target":"game","device":"$device","task":"t"}"""
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/dev/runs", start, writer).status)
        createClient { install(WebSockets) }.webSocket(request = { url("/api/device/socket"); bearerAuth(writer); header("X-YYS-Client", "device") }) {
            suspend fun status(phase: String) = send(Frame.Text("""{"type":"status","status":{"phase":"$phase"}}"""))
            suspend fun next(): ServerMessage.Dev = withTimeout(5000) {
                while (true) { val frame = incoming.receive() as? Frame.Text ?: continue
                    (Json { ignoreUnknownKeys = true }.decodeFromString(ServerMessage.serializer(), frame.readText()) as? ServerMessage.Dev)?.let { return@withTimeout it } }
                @Suppress("UNREACHABLE_CODE") error("")
            }
            status("IDLE"); kotlinx.coroutines.delay(300)
            val created = send(HttpMethod.Post, "/api/dev/runs", start, writer)
            assertEquals(HttpStatusCode.Created, created.status)
            val run = body(created).getValue("id").jsonPrimitive.content
            val command = next().command
            assertEquals(run, command.id)
            assertEquals(DevKind.RUN, command.kind)
            val compiled = Json.parseToJsonElement(command.bundle!!).jsonObject
            assertTrue(compiled.getValue("version").jsonPrimitive.content.startsWith("dev-"))
            assertEquals("t", command.task)
            // Only one at a time.
            assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/dev/runs", start, writer).status)
            // Not started yet: once the phone says it is idle again (as after reconnecting), the command is sent again.
            kotlinx.coroutines.delay(5_100); status("IDLE")
            assertEquals(run, next().command.id)
            val reported = send(HttpMethod.Post, "/api/dev/runs/$run/report", """{"state":"FINISHED","detail":"完成","facts":[{"type":"task-completed"}]}""", writer)
            assertEquals(HttpStatusCode.OK, reported.status)
            val view = Json.parseToJsonElement(get("/api/dev/runs/$run", writer).bodyAsText()).jsonObject
            assertEquals(JsonPrimitive("FINISHED"), view["state"])
            // Someone else cannot read it; a phone running a job gets none.
            assertEquals(HttpStatusCode.NotFound, get("/api/dev/runs/$run", signIn("second")).status)
            status("RUNNING"); kotlinx.coroutines.delay(300)
            assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/dev/runs", start, writer).status)
            status("IDLE"); kotlinx.coroutines.delay(300)
            // A probe needs a vision profile in the draft; this one has none.
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/dev/runs",
                """{"kind":"probe","target":"game","device":"$device","states":["home"]}""", writer).status)
        }
    }
}

private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
