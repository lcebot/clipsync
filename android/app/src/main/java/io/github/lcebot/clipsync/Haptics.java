package io.github.lcebot.clipsync;

import android.view.HapticFeedbackConstants;
import android.view.View;

import com.google.android.material.slider.Slider;

/**
 * One short tick per deliberate action, and the three a slider owes while it is being dragged.
 *
 * <p>The switches on the Settings page came with haptics and nothing else did, which is worse than
 * no haptics at all: it makes the two controls that already feel the most physical the only ones
 * that answer, and every other tap read as not having registered.
 *
 * <p><b>Constants, not durations.</b> Every method here names a
 * {@link HapticFeedbackConstants} rather than driving the vibrator, which matters for three reasons:
 * the device maps them to its own actuator, the user's "touch feedback" setting is honoured without
 * this code knowing about it, and a phone with no vibrator does nothing instead of throwing. It also
 * means the feel matches the platform's own, because a tick here is the same tick the system uses.
 *
 * <p>minSdk is 35, so the API 34 additions are simply available: {@code DRAG_START} and
 * {@code SEGMENT_TICK} exist precisely for a slider being dragged across detents, and using them is
 * the difference between "a vibration happens" and "this feels like the rest of the system".
 */
final class Haptics {
    private Haptics() { }

    /** A press on something that does one thing: a button, a card, a chip, a navigation item. */
    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /** Wraps a click listener so the tick cannot be forgotten at the call site. */
    static void onClick(View v, Runnable action) {
        v.setOnClickListener(x -> {
            tick(x);
            action.run();
        });
    }

    /**
     * The three moments a slider has to answer: the grab, every detent it snaps to, and the release.
     *
     * <p>A slider that only ticks on release tells the user what happened after it stopped mattering;
     * one that ticks per step and not on grab starts silently. The detent tick is also the only way a
     * stepped slider communicates that it <em>is</em> stepped without the user watching the label.
     *
     * @param onChange runs after the tick, for whatever the caller already did with the value
     */
    static void bind(Slider slider, Slider.OnChangeListener onChange) {
        slider.addOnChangeListener((s, value, fromUser) -> {
            // fromUser only: a programmatic setValue() during load would otherwise buzz the phone
            // once per slider as the page fills in.
            if (fromUser) s.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
            onChange.onValueChange(s, value, fromUser);
        });
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider s) {
                s.performHapticFeedback(HapticFeedbackConstants.DRAG_START);
            }

            @Override public void onStopTrackingTouch(Slider s) {
                s.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
            }
        });
    }
}
