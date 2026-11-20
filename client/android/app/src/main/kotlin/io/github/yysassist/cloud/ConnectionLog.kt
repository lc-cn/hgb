package io.github.yysassist.cloud

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The last [KEEP] things the cloud connection and job loop did, each with its time: shown in 开发 → 设置 → 连接日志, kept
 * across restarts in [file], and written to the system log under the tag "hgb". It names jobs by a short id and
 * failures by their kind or status code only: no tokens, emails, game accounts, server messages or screen content.
 */
class ConnectionLog(private val file: File, scope: CoroutineScope) {
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()
    private val writes = Channel<List<String>>(Channel.CONFLATED)
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    /** Cleared before the saved lines were read: they are not brought back. */
    @Volatile private var cleared = false

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { file.readLines().takeLast(KEEP) }.getOrDefault(emptyList())
            if (!cleared) mutableLines.update { (saved + it).takeLast(KEEP) }
            // Lines added while the file was read were queued without the saved ones; write the whole list once now.
            writes.trySend(mutableLines.value)
            for (snapshot in writes) runCatching {
                val partial = File(file.parentFile, file.name + ".part")
                partial.writeText(snapshot.joinToString("\n", postfix = "\n")); partial.renameTo(file)
            }
        }
    }

    fun add(event: String) {
        val line = "${LocalDateTime.now().format(time)}  $event"
        Log.i("hgb", event)
        mutableLines.update { (it + line).takeLast(KEEP) }
        writes.trySend(mutableLines.value)
    }

    fun clear() { cleared = true; mutableLines.value = emptyList(); writes.trySend(emptyList()) }

    private companion object { const val KEEP = 500 }
}
