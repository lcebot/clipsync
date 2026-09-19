package io.github.lcebot.clipsync;

import android.view.View;
import android.widget.TextView;

import androidx.transition.ChangeBounds;
import androidx.transition.TransitionSet;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.transition.MaterialFade;

/**
 * The few things more than one UI class here needs, and none of them owns.
 *
 * <p>Its whole reason for existing is that the alternatives are worse. Every helper below had two
 * copies before — the fade-and-reflow transition was reachable only as a static on the Activity,
 * {@code show(TextInputLayout, String)} existed verbatim in two files, and the slider clamp was
 * written out four times with the array bound spelled as a literal in two of them. A second copy of
 * a rule is a rule that can drift, and these are exactly the rules whose drift is invisible: a fade
 * that silently stops fading, an error that stays on screen, a slider that reads one step off at the
 * top end.
 *
 * <p>Pure functions only. Nothing here holds state, so nothing here is a back door between the
 * classes that call it.
 */
final class Ui {
    private Ui() { }

    /**
     * How long the page takes to close a gap. The only pinned duration in the motion set — the fade
     * itself keeps M3's own asymmetric timing — and anything that has to move in step with a reflow
     * uses this rather than a number of its own.
     */
    static final long REFLOW_MS = 220;

    /**
     * Fade a view in or out while its siblings reflow around it.
     *
     * <p>Three things here are not obvious, and each of them silently cost a fade:
     *
     * <ul>
     *   <li><b>No duration on the set.</b> {@link TransitionSet#setDuration} loops over its children
     *       and sets theirs too, and {@code MaterialFade} only applies M3's own durations while its
     *       duration is still unset ({@code TransitionUtils.maybeApplyThemeDuration} guards on -1).
     *       Pinning the set therefore replaces the spec — 400 ms in, 150 ms out, asymmetric on
     *       purpose — with one symmetric number. The reflow is pinned on its own instead.
     *   <li><b>FadeProvider reaches full alpha at 30% of the duration.</b> So the number the set was
     *       pinning was not even the fade's length: at 220 ms the fade-in ran 66 ms, which is not a
     *       fade, it is an appearance. At M3's 400 ms it is 120 ms, which reads.
     *   <li><b>ChangeBounds has to be kept off the view that is fading.</b> Untargeted, it captures
     *       the fading view too and animates bounds that are degenerate on the GONE side, fighting
     *       the visibility animator on the same view.
     * </ul>
     *
     * <p>Which makes {@code fading} a precise list, not a convenient one: pass only the views whose
     * visibility is changing in this pass. Everything else has to stay inside ChangeBounds' reach,
     * or it will not move when the gap above it closes. {@link #turning(View, int)} is the filter.
     *
     * <p>1.14.0 has no spring-driven Transition — the Expressive spring attributes feed
     * SpringAnimation directly and are not wired into androidx.transition — so MaterialFade under its
     * own themed durations is the M3 Expressive answer here.
     */
    static TransitionSet visibilityMotion(View... fading) {
        MaterialFade fade = new MaterialFade();
        ChangeBounds bounds = new ChangeBounds();
        bounds.setDuration(REFLOW_MS);
        for (View v : fading) {
            if (v == null) continue;
            fade.addTarget(v);
            bounds.excludeTarget(v, true);
        }
        return new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(fade)
                .addTransition(bounds);
    }

    /**
     * The view if it is about to change visibility, otherwise null.
     *
     * <p>Only the views that actually turn over may be handed to {@link #visibilityMotion(View...)},
     * because it excludes them from ChangeBounds. Naming a view that is merely going to *move* — the
     * Direct card when the discovery card above it collapses — would exclude it from the only
     * transition that could have moved it, and it would jump instead of sliding. A view fades or it
     * moves; never both, and never neither.
     */
    static View turning(View v, int want) {
        return v != null && v.getVisibility() != want ? v : null;
    }

    /**
     * Put a problem on a field, or take one off, and say whether the field is now good.
     *
     * <p>{@code setErrorEnabled} as well as {@code setError}, and that pair is the point: setting the
     * error alone leaves the layout's reserved error line in place when the message goes, so a form
     * that has just been fixed keeps the gap where the complaint was.
     */
    static boolean showError(TextInputLayout l, String problem) {
        l.setError(problem);
        l.setErrorEnabled(problem != null);
        return problem == null;
    }

    /**
     * Set a TextView's text only when it differs.
     *
     * <p>Used on every setter on the status-refresh path — a file-watch event, or the 5 s backstop —
     * because {@code setText} always requests a layout whether or not the string changed, and this
     * path runs over a whole sheet of cards.
     */
    static void setTextIfChanged(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }

    /**
     * The step a slider position lands on.
     *
     * <p>The sliders carry an index, not the value: the steps are not evenly spaced, so the control
     * runs 0..n-1 and the array says what each one means. Rounding and clamping is therefore the
     * translation between the two, and it is here rather than at each call site because it was
     * written out four times — twice with the array's upper bound spelled as a literal, which is a
     * number that has to be edited in step with a constant in another file.
     */
    static int snap(int[] steps, float raw) {
        return steps[Math.max(0, Math.min(steps.length - 1, Math.round(raw)))];
    }

    /** The inverse: the slider position that means this value, or the first one if none does. */
    static int indexOf(int[] steps, int v) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == v) return i;
        return 0;
    }
}
