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
 * that gets a fresh snapshot instead. Ids sent to browsers carry [boot], so ids from before a restart never match.
 */
class ConsoleEvents(private val capacity: Int = 64) {
    val boot: String = java.lang.Long.toString(System.nanoTime() xor java.util.UUID.randomUUID().mostSignificantBits, 36).removePrefix("-")
    private class Channel(capacity: Int) {
        var nextId = 1L
        val recent = ArrayDeque<ConsoleEvent>()
        val flow = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = capacity)
    }
    private val channels = ConcurrentHashMap<String, Channel>()
    private fun channel(userId: String) = channels.computeIfAbsent(userId) { Channel(capacity) }

    /** Ids are taken and emitted under one lock, so subscribers always see them in order. */
    fun publish(userId: String, name: String, data: String) {
        val channel = channel(userId)
        synchronized(channel) {
            val event = ConsoleEvent(channel.nextId++, name, data)
            channel.recent.addLast(event)
            while (channel.recent.size > capacity) channel.recent.removeFirst()
            channel.flow.tryEmit(event)
        }
    }
    fun stream(userId: String): SharedFlow<ConsoleEvent> = channel(userId).flow.asSharedFlow()
    /** The id of the newest event, 0 when there is none yet. */
    fun latest(userId: String): Long = channel(userId).let { synchronized(it) { it.nextId - 1 } }
    fun wireId(id: Long) = "$boot.$id"
    /** The event number in a browser's last id, or null when it is missing or from before a restart. */
    fun idOf(lastEventId: String?): Long? = lastEventId?.takeIf { it.startsWith("$boot.") }?.substringAfter('.')?.toLongOrNull()
    /**
     * Events after the browser's last id, or null when it needs a snapshot: the id is missing, from before a
     * restart, or older than what is still kept.
     */
    fun since(userId: String, lastEventId: String?): List<ConsoleEvent>? {
        val lastId = idOf(lastEventId) ?: return null
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
 * Open device sockets. A device has at most one; a newer connection replaces the older one. Each connection has an
 * outbox its socket session drains, so business code can queue a message without suspending.
 */
class DeviceSockets {
    class Connection(val userId: String, val deviceId: String, val generation: Long, val outbox: kotlinx.coroutines.channels.Channel<String>, val close: suspend () -> Unit)
    private val open = ConcurrentHashMap<String, Connection>()
    private val generations = AtomicLong()

    /** Returns the new connection and the one it replaced, which the caller should close. */
    fun attach(userId: String, deviceId: String, close: suspend () -> Unit): Pair<Connection, Connection?> {
        val connection = Connection(userId, deviceId, generations.incrementAndGet(), kotlinx.coroutines.channels.Channel(OUTBOX), close)
        return connection to open.put(deviceId, connection)
    }
    /** Only removes [connection] if it is still the current one, so a late close cannot hide a newer socket. */
    fun detach(connection: Connection): Boolean = open.remove(connection.deviceId, connection).also { connection.outbox.close() }
    fun isOnline(deviceId: String) = open.containsKey(deviceId)
    /** False when the device is not connected or its outbox is full; anything that matters is sent again later. */
    fun send(deviceId: String, text: String): Boolean = open[deviceId]?.outbox?.trySend(text)?.isSuccess ?: false
    suspend fun close(deviceId: String) { open.remove(deviceId)?.let { it.outbox.close(); it.close() } }
    private companion object { const val OUTBOX = 64 }
}
