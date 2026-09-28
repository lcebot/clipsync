package io.github.lcebot.clipsync.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * One short tick per deliberate action, and the three moments a slider owes while it is dragged.
 *
 * Platform constants rather than vibrator durations: the device maps them to its own actuator, the
 * user's touch feedback setting applies without this code knowing about it, a phone without a
 * vibrator does nothing instead of throwing, and a tick here feels like the same tick everywhere
 * else in the system. The View constants are used directly because they cover the API 34 slider
 * set (DRAG_START, SEGMENT_TICK, GESTURE_END) that minSdk 35 guarantees.
 *
 * A tick belongs only where the component is silent. Components that give their own feedback do
 * not get a second one from here, because a double buzz reads as an error.
 */
@Stable
class Haptics(private val view: View) {
    /** A press on something that does one thing: a button, a card, a chip, a navigation item. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** A stepped slider landing on a new detent while the user drags it. */
    fun detent() {
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
    }

    fun grab() {
        view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
    }

    fun release() {
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
    }

    /** Wraps an action so the tick cannot be forgotten at the call site. */
    fun ticking(action: () -> Unit): () -> Unit = {
        tick()
        action()
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * The grab and the release of a slider, from its interaction source.
 *
 * A slider that only answers on release tells the user what happened after it stopped mattering;
 * one that ticks per step but not on grab starts silently. A tap on the track arrives as a press
 * and a drag as a drag, sometimes both for one gesture, so a flag keeps it to one grab and one
 * release per gesture. The per-detent tick is the caller's, in its value change, where it can tell
 * a user's move from a programmatic one.
 */
@Composable
fun SliderGrabHaptics(interactions: InteractionSource, haptics: Haptics) {
    LaunchedEffect(interactions, haptics) {
        var holding = false
        interactions.interactions.collect { i: Interaction ->
            when (i) {
                is PressInteraction.Press, is DragInteraction.Start -> {
                    if (!holding) haptics.grab()
                    holding = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel,
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    if (holding) haptics.release()
                    holding = false
                }
            }
        }
    }
}
