package io.github.lcebot.clipsync.ui.main

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.clipsync.Config
import io.github.lcebot.clipsync.Logger
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.log.LogState
import io.github.lcebot.clipsync.ui.settings.SaveProblem
import io.github.lcebot.clipsync.ui.settings.SettingsRules
import io.github.lcebot.clipsync.ui.settings.SettingsState
import io.github.lcebot.clipsync.ui.status.StatusRepository
import io.github.lcebot.clipsync.ui.status.StatusUi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Something to tell the user in a snackbar. */
class UiMessage(@StringRes val text: Int, vararg val args: Any)

/**
 * The main screen's state and everything it does to the service and the config file.
 *
 * Anything that has to see two surfaces at once lives here: the status that both the chip and the
 * sheet show, the action state machine the floating actions follow, and the form that Apply saves.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val service: ServiceControl = AndroidServiceControl(app)
    private val repository = StatusRepository(app)

    val settings = SettingsState(SettingsRules.load(Config.raw(app)))
    val log = LogState()

    private val _actions = MutableStateFlow(ActionState())
    val actions: StateFlow<ActionState> = _actions.asStateFlow()

    /**
     * One status for the whole screen, so the chip and an open sheet can never disagree. Every read
     * also advances the action state machine, whose timeout is measured on the same clock.
     *
     * Shared while the screen is visible, plus a few seconds so a rotation does not restart the
     * file watch.
     */
    val status: StateFlow<StatusUi> = repository.updates()
        .onEach { s -> _actions.update { it.onStatus(s.running, now()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_GRACE_MS), StatusUi.Initial)

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    init {
        // An installed but never started app is in the "stopped" state and receives no
        // BOOT_COMPLETED; opening it once and starting the service clears that.
        if (!service.isAlive() && service.isAutoStartEnabled()) {
            try {
                Config.load(app)
                service.start()
            } catch (e: RuntimeException) {
                // An invalid config: the user fixes it on the page and presses Apply.
            }
        }
    }

    /**
     * Saves the form and brings the service onto it: a reload in place when it is running, a start
     * when it is not. Auto-start is switched back on, because Apply is how Stop is undone.
     */
    fun apply() {
        val values = settings.values()
        if (!SettingsRules.validate(values).ok) return
        val app = getApplication<Application>()
        try {
            val stored = Config.raw(app).getProperty("psk", "")
            val c = Config.save(app, SettingsRules.toProperties(values, stored, now()))
            service.setAutoStart(true)
            if (service.isAlive()) {
                service.reload()
            } else {
                service.start()
                _actions.update { it.onStartRequested(now()) }
            }
            Logger.i(
                "config applied: " +
                    (if (c.peers.isEmpty()) "no addresses" else c.peers.joinToString(", ") + ":" + c.port) +
                    (if (c.discovery) " + discovery (browse ${c.mdnsTimeoutMs} ms)" else "") +
                    ", ${c.threads} streams, files in ${c.filesDir}",
            )
            settings.saveProblem = null
            say(UiMessage(R.string.snack_applied))
        } catch (e: IllegalArgumentException) {
            // Live validation runs the same checks, so this is a disagreement between the form and
            // Config.from; it still lands on a field the user can act on.
            settings.saveProblem = SaveProblem.parse(e.message)
        } catch (e: Exception) {
            // The user gets a sentence; the log gets the exception. A class name in a snackbar is
            // not something anyone can act on.
            Logger.w("config save failed", e)
            val reason = e.message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.save_failed_generic)
            say(UiMessage(R.string.snack_save_failed, reason))
        }
    }

    /**
     * Stops the service and keeps it stopped: auto-start goes off first, so neither a boot nor the
     * system_server watchdog brings it back behind the user's back.
     */
    fun stop() {
        service.setAutoStart(false)
        service.stop()
        _actions.update { it.onStopRequested(now()) }
        say(UiMessage(R.string.snack_stopped))
    }

    /**
     * Pairing wrote a new key and started or reloaded the service. The form re-reads the whole file,
     * because pairing writes more than the key.
     */
    fun onKeyChanged() {
        reloadSettings()
        service.setAutoStart(true)
        _actions.update { it.onKeyChanged(service.isAlive(), now()) }
    }

    fun reloadSettings() {
        settings.load(SettingsRules.load(Config.raw(getApplication())))
    }

    /** Pairing needs discovery at both ends; turning it on is said out loud, never done silently. */
    fun prepareToPair() {
        if (settings.ensureDiscovery()) say(UiMessage(R.string.pair_turned_discovery_on))
    }

    fun say(message: UiMessage) {
        messageChannel.trySend(message)
    }

    /** Whether the first-run choice should be offered: without a key, nothing else works at all. */
    fun needsFirstRun(): Boolean = Config.checkPsk(Config.raw(getApplication()).getProperty("psk", "")) != null

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val SHARE_GRACE_MS = 5_000L
    }
}
