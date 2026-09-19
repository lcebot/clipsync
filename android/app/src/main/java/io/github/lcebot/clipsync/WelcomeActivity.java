package io.github.lcebot.clipsync;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.transition.TransitionManager;

import com.google.android.material.transition.MaterialSharedAxis;

/**
 * Setting ClipSync up: what it is, and the three ways in.
 *
 * <p>A screen and not a dialog, which was the first version of this. Two reasons, and the visible
 * one came first: a dialog's own padding is not symmetric, so its text sat at different distances
 * from the two edges and nothing in the layout said what the distance ought to be. The deeper one is
 * that this is not a question interrupting something — on a device with no key it is the only thing
 * there is to do, and a surface that can be dismissed by tapping beside it is the wrong shape for
 * that.
 *
 * <p>It returns a choice rather than acting on one. The pairing sheets belong to the settings page,
 * where the fields they rewrite live, so this hands back {@link #EXTRA_CHOICE} and lets
 * {@link MainActivity} open them — which is also what makes the button in Settings and the first run
 * the same code path rather than two that drift.
 */
public final class WelcomeActivity extends AppCompatActivity {
    /** {@link #JOIN}, {@link #GENERATE} or {@link #MANUAL}; absent if the user backed out. */
    static final String EXTRA_CHOICE = "choice";
    static final String JOIN = "join", GENERATE = "generate", MANUAL = "manual";

    private View intro, choose;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_welcome);
        intro = findViewById(R.id.page_intro);
        choose = findViewById(R.id.page_choose);

        Haptics.onClick(findViewById(R.id.intro_next), () -> page(true));
        Haptics.onClick(findViewById(R.id.choose_join), () -> finishWith(JOIN));
        Haptics.onClick(findViewById(R.id.choose_generate), () -> finishWith(GENERATE));
        Haptics.onClick(findViewById(R.id.choose_manual), () -> finishWith(MANUAL));

        // Back walks the pages before it leaves, which is what a series of screens promises by
        // being a series. Registered rather than overriding onBackPressed so the predictive-back
        // animation the manifest opts into still knows what is going to happen.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (choose.getVisibility() == View.VISIBLE) {
                    page(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (state == null) enter();
    }

    /**
     * The first page arrives rather than appearing.
     *
     * <p>Only on a fresh start, never on a rotation: an animation that replays every configuration
     * change stops being an arrival and becomes a stutter.
     *
     * <p>The hero scales from 80% and the two text blocks rise a little behind it, each one later
     * than the last. Staggering is the whole effect — three things moving together read as one
     * sliding panel, and three things moving in sequence read as a screen assembling itself, which
     * is what M3 Expressive means by an entrance.
     */
    private void enter() {
        View hero = findViewById(R.id.intro_hero);
        Interpolator spatial = AnimationUtils.loadInterpolator(this,
                android.R.interpolator.fast_out_slow_in);
        rise(hero, 0, spatial);
        rise(findViewById(R.id.intro_title), 60, spatial);
        rise(findViewById(R.id.intro_body), 110, spatial);
        rise(findViewById(R.id.intro_next), 160, spatial);

        hero.setScaleX(0.8f);
        hero.setScaleY(0.8f);
        hero.animate().scaleX(1f).scaleY(1f).setDuration(ENTER_MS)
                .setInterpolator(spatial).start();
    }

    private void rise(View v, long delay, Interpolator spatial) {
        v.setAlpha(0f);
        v.setTranslationY(RISE_PX);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(ENTER_MS).setInterpolator(spatial).start();
    }

    /**
     * Between the two pages, on M3's own lateral transition.
     *
     * <p>{@link MaterialSharedAxis} on X is the transition for peer destinations — the pages slide
     * and cross-fade in the direction of travel, so going back looks like going back rather than
     * like a second forward step. Left on its themed durations for the reason recorded on
     * {@code MainActivity.visibilityMotion}: setting a duration on the set overwrites the spec.
     */
    private void page(boolean forward) {
        MaterialSharedAxis axis = new MaterialSharedAxis(MaterialSharedAxis.X, forward);
        TransitionManager.beginDelayedTransition((ViewGroup) findViewById(R.id.welcome_root), axis);
        intro.setVisibility(forward ? View.GONE : View.VISIBLE);
        choose.setVisibility(forward ? View.VISIBLE : View.GONE);
        // The page turning is a small, physical event, so it gets the tick the rest of the app gives
        // a control that moved. Not the click feedback — the button already played that.
        Haptics.tick(forward ? choose : intro);
    }

    private void finishWith(String choice) {
        setResult(Activity.RESULT_OK, new android.content.Intent().putExtra(EXTRA_CHOICE, choice));
        finish();
    }

    /** Long enough to be a movement, short enough not to be a wait. */
    private static final long ENTER_MS = 350;
    private static final float RISE_PX = 32f;
}
