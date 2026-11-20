package io.github.yysassist.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*

/** Configuration comes only from the environment, so no secret is ever written into the repository. */
fun main() {
    val env = System.getenv()
    val secret = env["YYS_SECRET"]?.takeIf { it.length >= 32 }?.toByteArray() ?: error("请设置 YYS_SECRET（至少 32 位随机字符）")
    // YYS_SMTP_*: a provider's SMTP relay (Alibaba Cloud DirectMail, smtpdm.aliyun.com:465). YYS_MAIL_MODE=console only
    // prints codes to the log, for local development.
    val mailer = when {
        env["YYS_MAIL_MODE"] == "console" -> ConsoleMailer
        !env["YYS_SMTP_HOST"].isNullOrBlank() -> SmtpMailer(env.getValue("YYS_SMTP_HOST"), env["YYS_SMTP_PORT"]?.toInt() ?: 465,
            env["YYS_SMTP_USER"] ?: error("请设置 YYS_SMTP_USER"), env["YYS_SMTP_PASSWORD"] ?: error("请设置 YYS_SMTP_PASSWORD"),
            env["YYS_MAIL_FROM"] ?: env.getValue("YYS_SMTP_USER"), env["YYS_MAIL_FROM_NAME"] ?: "护肝宝")
        else -> UnconfiguredMailer
    }
    // YYS_CONSOLE_URL: where the console is, for the way back from GitHub sign-in. YYS_ALLOWED_ORIGINS: the console's
    // origin(s), comma-separated, when it is served from another host than this API.
    val config = ServerConfig(env["YYS_PUBLIC_URL"] ?: "http://127.0.0.1:8787", env["YYS_SECURE_COOKIES"] != "false",
        env["YYS_CONSOLE_URL"] ?: "/console/", env["YYS_ALLOWED_ORIGINS"].orEmpty().split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.toSet())
    // YYS_CHANNELS_PRIVATE=allow lets channels post to this machine or a private network: local development only.
    val http = OkChannelHttp(allowPrivate = env["YYS_CHANNELS_PRIVATE"] == "allow")
    val service = CloudService(Database(env["YYS_DB"] ?: "yys-cloud.db"), mailer, GitHubIdentityClient(env["GITHUB_CLIENT_ID"], env["GITHUB_CLIENT_SECRET"]), secret,
        channelHttp = http)
    embeddedServer(Netty, host = env["YYS_HOST"] ?: "127.0.0.1", port = env["YYS_PORT"]?.toInt() ?: 8787) { cloudModule(service, config) }.start(wait = true)
}
