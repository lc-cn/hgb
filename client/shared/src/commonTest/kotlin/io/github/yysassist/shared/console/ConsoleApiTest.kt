package io.github.yysassist.shared.console

import io.github.yysassist.core.cloud.ConsoleStreamEvent
import io.github.yysassist.shared.ConsoleSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsoleApiTest {
    private val me = """{"userId":"0f4d9426-30d8-4140-9bb1-e05d9a526c24","email":"a@example.com","hasPassword":true,"githubLogin":"someone","timeZone":"Asia/Shanghai"}"""

    private class Canned(private val reply: String) : ConsoleTransport {
        override suspend fun request(method: String, path: String, body: String?): String? = reply
        override fun events(): Flow<ConsoleStreamEvent> = emptyFlow()
    }

    @Test fun theAccountReadsAsTheServerSendsIt() = runBlocking {
        assertEquals("a@example.com", ConsoleApi(Canned(me)).me().email)
    }

    /** A connected stream whose first account request fails, as on a flaky network. */
    private inner class Flaky : ConsoleTransport {
        var calls = 0
        override suspend fun request(method: String, path: String, body: String?): String? =
            if (++calls == 1) throw IOException("dropped") else me
        override fun events(): Flow<ConsoleStreamEvent> = flow { emit(ConsoleStreamEvent.Opened); awaitCancellation() }
    }

    @Test fun aFailedAccountReadIsTriedAgainAndTheAccountOutlivesTheWindow() = runBlocking {
        val transport = Flaky()
        val session = ConsoleSession(transport)
        val failures = mutableListOf<Exception>()
        val stream = launch { session.store.follow() }
        val reader = launch { session.followAccount(retryMillis = 10) { failures += it } }
        assertEquals("a@example.com", withTimeout(5_000) { session.account.filterNotNull().first() }.email)
        assertEquals(1, failures.size)
        // The window going away stops the reading but keeps what was read.
        reader.cancel(); stream.cancel()
        assertEquals("a@example.com", session.account.value?.email)
        session.reset()
        assertNull(session.account.value)
    }
}
