package io.github.yysassist.cloud

import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.cloud.ConsoleStreamEvent
import io.github.yysassist.shared.console.ConsoleTransport
import kotlinx.coroutines.flow.Flow

/** The console's pages reach the server through this device's own session, renewed by the agent. */
class AgentTransport(private val agent: CloudAgent) : ConsoleTransport {
    override suspend fun request(method: String, path: String, body: String?): String? = agent.request(method, path, body)
    override fun events(): Flow<ConsoleStreamEvent> = agent.consoleEvents()
}
