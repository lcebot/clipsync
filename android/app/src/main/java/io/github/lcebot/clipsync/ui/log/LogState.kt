package io.github.lcebot.clipsync.ui.log

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import io.github.lcebot.clipsync.Logger

/**
 * The log as the screen shows it: the lines, and the reader's place in [Logger]'s buffer.
 *
 * Outlives the Log page, so switching tabs does not rebuild the list from nothing. Main thread only,
 * like the cursor it wraps.
 */
@Stable
class LogState {
    private val cursor = Logger.Cursor()
    val lines = mutableStateListOf<String>()

    /**
     * Catches up with the buffer: usually an append, a full replace when the buffer has been rebuilt
     * or has dropped more than this reader can bridge.
     *
     * @return whether anything changed
     */
    fun pull(): Boolean {
        val tail = Logger.read(cursor)
        if (tail.isEmpty()) return false
        if (tail.replace) lines.clear()
        lines.addAll(tail.lines)
        return true
    }

    fun clear() {
        Logger.clear()
        pull()
    }

    fun text(): String = lines.joinToString("\n")
}
