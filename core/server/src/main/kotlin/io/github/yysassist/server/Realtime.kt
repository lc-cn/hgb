package io.github.yysassist.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One event for a user's open consoles. [id] counts up per user, so a reconnecting console can ask for what it missed. */
data class ConsoleEvent(val id: Long, val name: String, val data: String)

/**
 * Per-user event log for the console stream. Only the last [capacity] events are kept; a console that missed more than
 * that, or reconnects after a server restart, gets a fresh snapshot instead.
 */
class ConsoleEvents(private val capacity: Int = 256) {
    private class Channel(capacity: Int) {
        var nextId = 1L
        val recent = ArrayDeque<ConsoleEvent>()
        val flow = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = capacity)
    }
    private val channels = ConcurrentHashMap<String, Channel>()
    private fun channel(userId: String) = channels.computeIfAbsent(userId) { Channel(capacity) }

    fun publish(userId: String, name: String, data: String) {
        val channel = channel(userId)
        val event = synchronized(channel) {
            ConsoleEvent(channel.nextId++, name, data).also {
                channel.recent.addLast(it)
                while (channel.recent.size > capacity) channel.recent.removeFirst()
            }
        }
        channel.flow.tryEmit(event)
    }
    fun stream(userId: String): SharedFlow<ConsoleEvent> = channel(userId).flow.asSharedFlow()
    /** The id of the newest event, 0 when there is none yet. */
    fun latest(userId: String): Long = channel(userId).let { synchronized(it) { it.nextId - 1 } }
    /** Events after [lastId], or null when some of them are no longer kept and the console needs a snapshot. */
    fun since(userId: String, lastId: Long): List<ConsoleEvent>? {
        val channel = channel(userId)
        return synchronized(channel) {
            val newest = channel.nextId - 1
            when {
                lastId > newest -> null
                lastId == newest -> emptyList()
                (channel.recent.firstOrNull()?.id ?: Long.MAX_VALUE) > lastId + 1 -> null
                else -> channel.recent.filter { it.id > lastId }
            }
        }
    }
}

/**
 * Open device sockets. A device has at most one; a newer connection replaces the older one. [send] queues a text frame
 * and returns false when the device is not connected.
 */
class DeviceSockets {
    class Connection(val userId: String, val deviceId: String, val generation: Long, val send: suspend (String) -> Boolean, val close: suspend () -> Unit)
    private val open = ConcurrentHashMap<String, Connection>()
    private val generations = AtomicLong()

    /** Returns the new connection and the one it replaced, which the caller should close. */
    fun attach(userId: String, deviceId: String, send: suspend (String) -> Boolean, close: suspend () -> Unit): Pair<Connection, Connection?> {
        val connection = Connection(userId, deviceId, generations.incrementAndGet(), send, close)
        return connection to open.put(deviceId, connection)
    }
    /** Only removes [connection] if it is still the current one, so a late close cannot hide a newer socket. */
    fun detach(connection: Connection): Boolean = open.remove(connection.deviceId, connection)
    fun isOnline(deviceId: String) = open.containsKey(deviceId)
    suspend fun send(deviceId: String, text: String): Boolean = open[deviceId]?.send?.invoke(text) ?: false
    suspend fun close(deviceId: String) { open.remove(deviceId)?.close?.invoke() }
}
