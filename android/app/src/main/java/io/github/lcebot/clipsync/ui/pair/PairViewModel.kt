package io.github.lcebot.clipsync.ui.pair

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

enum class PairEvent {
    /** A new key is in the config file. */
    KEY_CHANGED,

    /** The sheet is gone and its session is over. */
    CLOSED,
}

/**
 * Owns the pairing session for one screen.
 *
 * In a view model and not in composition because a rotation must not end a session the user is
 * in the middle of, and must not leave one running behind a screen that no longer shows it. Here the
 * sheet's visibility IS [session], so after a rotation the same sheet comes back over the same
 * window, and the only ways a session ends are [dismiss] and [onCleared], both of which close it.
 *
 * Events go through a buffered channel rather than a shared flow, because a key that changes while
 * the activity is being recreated must still reach the screen that comes back.
 */
class PairViewModel(app: Application) : AndroidViewModel(app) {
    private val _session = MutableStateFlow<PairController?>(null)
    val session: StateFlow<PairController?> = _session.asStateFlow()

    private val eventChannel = Channel<PairEvent>(Channel.BUFFERED)

    /**
     * Whether any session of this screen has written a key. Lives as long as the view model, so a
     * rotation between pairing and closing the sheet does not forget it.
     */
    @Volatile
    var keyChanged = false
        private set
    val events: Flow<PairEvent> = eventChannel.receiveAsFlow()

    fun offer() = start { it.offer() }

    fun join() = start { it.join() }

    fun generateAndOffer() = start { it.generateAndOffer() }

    fun dismiss() {
        val c = _session.value ?: return
        _session.value = null
        c.close()
        eventChannel.trySend(PairEvent.CLOSED)
    }

    override fun onCleared() {
        _session.value?.close()
        _session.value = null
    }

    private fun start(begin: (PairController) -> Unit) {
        dismiss()
        val c = PairController(getApplication(), viewModelScope, object : PairController.Events {
            override fun keyChanged() {
                this@PairViewModel.keyChanged = true
                eventChannel.trySend(PairEvent.KEY_CHANGED)
            }
        })
        _session.value = c
        begin(c)
    }
}
