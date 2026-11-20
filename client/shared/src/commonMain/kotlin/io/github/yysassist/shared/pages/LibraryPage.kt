package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** 任务库: each game's tasks as the server knows them, and which devices carry the game. */
@Composable
fun LibraryPage() = Page {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    var chosen by remember { mutableStateOf(data.games.firstOrNull()?.id) }
    val game = data.games.firstOrNull { it.id == chosen } ?: data.games.firstOrNull()
    PageHeader("任务库")
    if (game == null) { Card { Empty("还没有任务。设备上线并上传任务包后会出现在这里。") }; return@Page }
    if (data.games.size > 1) Segments(data.games.map { it.id to it.name }, game.id) { chosen = it }
    Block("任务", "${game.tasks.size} 个") {
        Card {
            if (game.tasks.isEmpty()) Empty("这个游戏还没有任务")
            game.tasks.forEachIndexed { i, task -> ListRow(last = i == game.tasks.lastIndex) {
                Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }
    val devices = data.devices.filter { device -> device.apps.any { it.gameId == game.id } || device.tasks.any { it.targetId == game.id } }
    Block("装了${game.name}的设备") {
        Card {
            if (devices.isEmpty()) Empty("还没有设备装了这个游戏")
            devices.forEachIndexed { i, device -> ListRow(last = i == devices.lastIndex) {
                Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val count = device.tasks.count { it.targetId == game.id }
                Text(if (device.online) "$count 个任务" else "离线",
                    color = if (device.online) tokens.ok else tokens.muted, style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}
