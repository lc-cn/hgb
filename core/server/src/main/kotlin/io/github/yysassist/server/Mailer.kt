package io.github.yysassist.server

/** The mail provider is chosen at deployment; nothing else depends on which one it is. */
fun interface Mailer { fun send(to: String, subject: String, body: String) }

class MailUnavailable : IllegalStateException("发信服务未配置")

/** Refuses to pretend a code was delivered when no provider is configured. */
object UnconfiguredMailer : Mailer { override fun send(to: String, subject: String, body: String) = throw MailUnavailable() }

/** Local development only: prints the message to the server console. */
object ConsoleMailer : Mailer { override fun send(to: String, subject: String, body: String) = println("[开发邮件] 收件人=$to 主题=$subject\n$body") }
