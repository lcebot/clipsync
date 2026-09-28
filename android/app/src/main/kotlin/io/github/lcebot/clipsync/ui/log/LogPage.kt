package io.github.lcebot.clipsync.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.clipsync.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Log page: selectable text, because a log is only useful in a bug report if it can be selected
 * and copied by hand.
 *
 * One line per item, so appending never disturbs a selection on the lines already there; only a
 * full rebuild of the buffer replaces them. The buffer is capped (Logger.MAX_ENTRIES), which keeps
 * the list short enough that a selection spanning it stays cheap.
 *
 * Polled only while this page is composed and the screen is resumed: reading the log file while
 * Settings is in front is work whose result nothing displays. Lines from this process arrive at
 * once through the listener; lines from :sync arrive with the poll, which re-reads the file that
 * process writes.
 */
@Composable
fun LogPage(state: LogState, listState: LazyListState, contentPadding: PaddingValues) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(state, listState, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Arriving at the page means arriving at the newest line.
            var follow = true
            val wake = Channel<Unit>(Channel.CONFLATED)
            val listener = Logger.Listener { wake.trySend(Unit) }
            Logger.addListener(listener)
            try {
                launch {
                    while (true) {
                        withContext(Dispatchers.IO) { Logger.refresh() }
                        wake.trySend(Unit)
                        delay(POLL_MS)
                    }
                }
                for (ignored in wake) {
                    // Sampled before the append: a reader at the bottom keeps following, a reader
                    // who scrolled up to read something is left where they are.
                    val atEnd = !listState.canScrollForward
                    if (state.pull() && (follow || atEnd) && state.lines.isNotEmpty()) {
                        listState.scrollToItem(state.lines.lastIndex)
                    }
                    follow = false
                }
            } finally {
                Logger.removeListener(listener)
            }
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            // Two entries sit further apart than two wrapped lines of one entry, so where one entry
            // ends is visible at a glance without a timestamp hunt.
            verticalArrangement = Arrangement.spacedBy(ENTRY_GAP),
            modifier = Modifier
                .fillMaxSize()
                // The pane recedes behind its text: one step below the navigation bar's container.
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            items(count = state.lines.size) { i ->
                // 12 sp and a stated monospace family rather than a type-scale role: a log wants
                // density and aligned columns, a role would bring its own family and tracking, and
                // 12 sp is the floor below which the text stops being legible to those who need it.
                // sp, so it still follows the system font size. Wrapped lines of one entry sit at
                // close to single spacing; the gap between entries comes from the list.
                Text(
                    text = state.lines[i],
                    style = LogLine,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private const val POLL_MS = 1_000L

private val LogLine = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 14.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** About a third of a line: entries read as separate, the log stays dense. */
private val ENTRY_GAP = 5.dp
