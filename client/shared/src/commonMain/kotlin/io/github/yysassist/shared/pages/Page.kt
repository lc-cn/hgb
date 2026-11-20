package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.ui.*

/** A page's scrolling body, padded for the tier and at most 1200 wide (the web console's main area). */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 1264.dp).fillMaxWidth().padding(pagePadding()), verticalArrangement = Arrangement.spacedBy(pageGap()), content = content)
    }
}
/** A titled block inside a page, like 最近运行. */
@Composable
fun Block(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (note != null) Text(" · $note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
        }
        content()
    }
}
/** Shown when a page's item is gone (removed elsewhere, or a stale link). */
@Composable
fun NotFound(text: String, backText: String, back: () -> Unit) = Page {
    Card { Empty(text); Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) { HgbButton(backText, back) } }
}
