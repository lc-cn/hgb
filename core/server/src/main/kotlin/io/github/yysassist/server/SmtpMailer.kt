package io.github.yysassist.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

/**
 * Sends plain-text mail through a provider's SMTP relay (Alibaba Cloud DirectMail: smtpdm.aliyun.com:465), over
 * implicit TLS with AUTH LOGIN. Subject and body are UTF-8 in base64, so no line of the body can end the message.
 * One connection per message: codes and notices are few, and a fresh connection never inherits a broken one.
 */
class SmtpMailer(
    private val host: String,
    private val port: Int = 465,
    private val user: String,
    private val password: String,
    private val from: String = user,
    private val fromName: String = "护肝宝",
    /** False only in tests, which talk to a plain local socket. */
    private val tls: Boolean = true,
    private val timeoutMillis: Int = 15_000,
) : Mailer {
    override fun send(to: String, subject: String, body: String) {
        require(to.matches(Regex("[^\\s@<>,]+@[^\\s@<>,]+"))) { "Invalid recipient" }
        try { deliver(to, subject, body) }
        catch (failure: java.io.IOException) { throw MailFailed("连不上邮件服务器：${failure.message ?: failure.javaClass.simpleName}") }
    }

    private fun deliver(to: String, subject: String, body: String) {
        val socket = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        socket.use { connection ->
            connection.connect(InetSocketAddress(host, port), timeoutMillis)
            connection.soTimeout = timeoutMillis
            val input = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
            val output = connection.getOutputStream()
            fun expect(code: Int) {
                // A reply may span lines ("250-…" then "250 …"); the last one has a space after the code.
                var line: String
                do { line = input.readLine() ?: throw MailFailed("邮件服务器断开了连接") } while (line.length > 3 && line[3] == '-')
                if (!line.startsWith(code.toString())) throw MailFailed("邮件服务器拒绝：${line.take(200)}")
            }
            fun say(line: String, code: Int) { output.writeAscii("$line\r\n"); expect(code) }
            expect(220)
            say("EHLO hgb", 250)
            say("AUTH LOGIN", 334)
            say(b64(user), 334)
            say(b64(password), 235)
            say("MAIL FROM:<$from>", 250)
            say("RCPT TO:<$to>", 250)
            say("DATA", 354)
            output.writeAscii(message(to, subject, body))
            say(".", 250)
            output.writeAscii("QUIT\r\n")
        }
    }

    private fun message(to: String, subject: String, body: String): String {
        val headers = listOf(
            "From: =?UTF-8?B?${b64(fromName)}?= <$from>",
            "To: <$to>",
            "Subject: =?UTF-8?B?${b64(subject)}?=",
            "Date: ${ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)}",
            "Message-ID: <${UUID.randomUUID()}@${from.substringAfter('@')}>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
        )
        val encoded = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return headers.joinToString("\r\n") + "\r\n\r\n" + encoded + "\r\n"
    }
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
    private fun OutputStream.writeAscii(text: String) { write(text.toByteArray(StandardCharsets.US_ASCII)); flush() }

    override fun toString() = "SmtpMailer($host:$port, $from)"
}

/** The provider refused or the connection broke; the caller tells the user to try again later. */
class MailFailed(message: String) : IllegalStateException(message)
