package io.github.yysassist.server

import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtpMailerTest {
    /** A relay that answers each command with the next reply and records what the client sent. */
    private fun relay(replies: Map<String, String>, block: (port: Int, seen: MutableList<String>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun reply(line: String) { output.write("$line\r\n"); output.flush() }
                    reply("220 relay ready")
                    var inData = false
                    while (true) {
                        val line = input.readLine() ?: break
                        seen += line
                        if (inData) { if (line == ".") { inData = false; reply(replies["."] ?: "250 queued") }; continue }
                        val verb = line.substringBefore(' ').substringBefore(':')
                        when {
                            verb == "EHLO" -> { reply("250-relay"); reply("250 AUTH LOGIN") }
                            verb == "QUIT" -> { reply("221 bye"); break }
                            verb == "DATA" -> { inData = true; reply("354 go on") }
                            else -> reply(replies[verb] ?: replies["*"] ?: "250 ok")
                        }
                    }
                }
            }
            block(server.localPort, seen)
            worker.join(5_000)
        }
    }
    private val authReplies = mapOf("AUTH" to "334 VXNlcm5hbWU6", "*" to "250 ok")

    @Test fun aMessageIsSentAfterSigningInAndReadsBackAsWritten() {
        val user = "noreply@mail.example.test"
        relay(authReplies + mapOf("bm9yZXBseUBtYWlsLmV4YW1wbGUudGVzdA==" to "334 UGFzc3dvcmQ6", "c2VjcmV0" to "235 ok")) { port, seen ->
            SmtpMailer("127.0.0.1", port, user, "secret", tls = false).send("player@example.test", "护肝宝验证码", "你的验证码是 123456。\n.\n第二行")
            assertEquals(listOf("EHLO hgb", "AUTH LOGIN", "bm9yZXBseUBtYWlsLmV4YW1wbGUudGVzdA==", "c2VjcmV0",
                "MAIL FROM:<$user>", "RCPT TO:<player@example.test>", "DATA"), seen.take(7))
            val data = seen.drop(7).takeWhile { it != "." }
            assertTrue("Subject: =?UTF-8?B?${Base64.getEncoder().encodeToString("护肝宝验证码".toByteArray())}?=" in data)
            // The body's own "." line is inside base64, so it cannot end the message early.
            val body = data.dropWhile { it.isNotEmpty() }.drop(1).joinToString("")
            assertEquals("你的验证码是 123456。\n.\n第二行", String(Base64.getDecoder().decode(body)))
            assertEquals("QUIT", seen.last())
        }
    }

    @Test fun aRefusalFromTheRelayIsAMailFailure() {
        relay(mapOf("AUTH" to "535 authentication failed")) { port, _ ->
            val failure = assertFailsWith<MailFailed> { SmtpMailer("127.0.0.1", port, "a@example.test", "wrong", tls = false).send("b@example.test", "s", "b") }
            assertTrue("535" in failure.message.orEmpty())
        }
    }

    @Test fun anUnreachableRelayIsAMailFailure() {
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<MailFailed> { SmtpMailer("127.0.0.1", port, "a@example.test", "p", tls = false, timeoutMillis = 2_000).send("b@example.test", "s", "b") }
    }
}
