package io.github.lcebot.clipsync.ui.main

import androidx.compose.runtime.Immutable

/**
 * The floating actions' state machine: which of Start, or Stop and Apply, is offered, and what the
 * screen is waiting for after a press.
 *
 * A pressed action stays disabled until the service really reaches the new state, because a second
 * press while the first is in flight either does nothing or undoes it. A state change made from
 * anywhere else (the watchdog, a boot, another screen) moves the actions just the same, since they
 * follow the service and not the buttons. If the service never arrives, [WAIT_TIMEOUT_MS] hands
 * control back rather than leaving the screen stuck.
 *
 * Pure and immutable so every transition is a JVM test. Time is always passed in.
 */
@Immutable
data class ActionState(
    val running: Boolean = false,
    val waitingForStart: Boolean = false,
    val waitingForStop: Boolean = false,
    val waitingSince: Long = 0L,
) {
    /** A fresh status read. Clears the wait the service has now satisfied, then applies the timeout. */
    fun onStatus(running: Boolean, now: Long): ActionState {
        var next = this
        if (running != this.running) {
            next = if (running) copy(running = true, waitingForStart = false)
            else copy(running = false, waitingForStop = false)
        }
        if ((next.waitingForStart || next.waitingForStop) && now - next.waitingSince > WAIT_TIMEOUT_MS) {
            next = next.copy(waitingForStart = false, waitingForStop = false)
        }
        return next
    }

    fun onStopRequested(now: Long): ActionState = copy(waitingForStop = true, waitingSince = now)

    fun onStartRequested(now: Long): ActionState = copy(waitingForStart = true, waitingSince = now)

    /**
     * Pairing wrote a new key and started or reloaded the service. Only a service that is not up yet
     * is something to wait for; a live one reloads in place.
     */
    fun onKeyChanged(alive: Boolean, now: Long): ActionState = copy(waitingForStart = !alive, waitingSince = now)

    /** What the Settings page offers. The Log page's Copy and Clear are always enabled. */
    fun settingsActions(configValid: Boolean): SettingsActions = SettingsActions(
        showStop = running,
        stopEnabled = !waitingForStop,
        primary = if (running) SettingsActions.Primary.APPLY else SettingsActions.Primary.START,
        primaryEnabled = configValid && !waitingForStart,
    )

    companion object {
        const val WAIT_TIMEOUT_MS = 12_000L
    }
}

@Immutable
data class SettingsActions(
    val showStop: Boolean,
    val stopEnabled: Boolean,
    val primary: Primary,
    val primaryEnabled: Boolean,
) {
    /** One button, two meanings: with the service stopped, applying the settings is starting it. */
    enum class Primary { START, APPLY }
}
