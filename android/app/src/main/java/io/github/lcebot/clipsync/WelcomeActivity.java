package io.github.lcebot.clipsync;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.transition.TransitionManager;

import com.google.android.material.motion.MotionUtils;
import com.google.android.material.transition.MaterialSharedAxis;

/**
 * Setting ClipSync up: what it is, and the three ways in.
 *
 * <p>A screen and not a dialog. Two reasons, and the visible one first: a dialog's own padding is
 * not symmetric, so its text sits at different distances from the two edges with nothing in the
 * layout to say what the distance ought to be. The deeper one is that this is not a question
 * interrupting something: on a device with no key it is the only thing there is to do, and a
 * surface that can be dismissed by tapping beside it is the wrong shape for that.
 *
 * <p>The pairing sheets open <b>over</b> this screen rather than being handed back to the settings
 * page. A sheet is a sheet, and it belongs on top of whatever asked for it, and finishing the setup
 * flow in order to perform the one thing the setup flow exists for put the user back on a page they
 * had not chosen to be on. {@link PairSheet.Host} is what lets one sheet serve both callers; only
 * *Set up manually* still leaves, because leaving is what it means.
 */
public final class WelcomeActivity extends AppCompatActivity {
    /**
     * {@link #MANUAL} if the user chose to type a key in themselves; absent otherwise.
     *
     * <p>The only choice that still travels, because it is the only one that means "leave this
     * screen". Joining and generating open a sheet over this activity and finish it when they are
     * done, so there is nothing for the settings page to act on.
     */
    static final String EXTRA_CHOICE = "choice";
    static final String MANUAL = "manual";

    private View intro, choose;

    /**
     * The open pairing sheet, or null.
     *
     * <p>Held for {@link #onDestroy()} alone. A BottomSheetDialog is not in this Activity's view
     * tree and survives it; the sheet opened by *Generate* holds a {@link PairProvider}, which holds
     * an mDNS advertisement and an accept loop that gives the PSK away. A rotation on this screen
     * left exactly that running with nothing on screen: no code visible, and the key still on
     * offer to the network.
     */
    private PairSheet sheet;

    /** True once a key exists, by either route: this screen's whole job, done. */
    private boolean setUp;

    /**
     * Nothing to reload; unlike the settings page there are no fields here showing the old key,
     * so this only has to notice whether the screen still has a reason to exist.
     *
     * <p>Keyed on the key being written rather than on a device having paired, because generating
     * one and then pairing nobody is still a finished setup: there is a key now, and the offer can
     * be made again from Settings whenever the other devices are to hand.
     */
    private final PairSheet.Host host = new PairSheet.Host() {
        @Override public void keyChanged() {
            setUp = true;
        }

        @Override public void closed() {
            if (setUp) finish();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_welcome);
        intro = findViewById(R.id.page_intro);
        choose = findViewById(R.id.page_choose);

        Haptics.onClick(findViewById(R.id.intro_next), () -> page(true));
        // Pairing happens ON this screen, over it, rather than by handing the job back: the sheet is
        // a sheet, and a sheet that can only exist on the settings page would mean leaving the setup
        // flow to do the one thing the setup flow is for. Only "set up manually" leaves, because
        // leaving IS what it means.
        Haptics.onClick(findViewById(R.id.choose_join), () -> sheet = PairSheet.join(this, host));
        Haptics.onClick(findViewById(R.id.choose_generate), () -> sheet = PairSheet.generateAndOffer(this, host));
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
     * than the last. Staggering is the whole effect: three things moving together read as one
     * sliding panel, and three things moving in sequence read as a screen assembling itself, which
     * is what M3 Expressive means by an entrance.
     */
    private void enter() {
        View hero = findViewById(R.id.intro_hero);
        // From the theme, not from the platform. android.R.interpolator.fast_out_slow_in is
        // Material 2's curve and is symmetric-ish; M3 Expressive's entrances use *emphasized
        // decelerate*, which arrives fast and settles slowly, and the difference is exactly the
        // "assembling itself" quality this staggered entrance is for. Reading it from the theme
        // rather than naming a curve also means a theme that retunes its motion retunes this too.
        //
        // Both calls are checked against Material 1.14.0's own source rather than guessed at:
        //   MotionUtils.resolveThemeInterpolator(Context, @AttrRes int, TimeInterpolator) returns TimeInterpolator
        //   MotionUtils.resolveThemeDuration(Context, @AttrRes int, int) returns int
        // (lib/java/com/google/android/material/motion/MotionUtils.java @ 1.14.0). The third
        // argument of each is the fallback used when the theme does not name the attribute, which
        // is why neither call can fail on a theme that has not been given these tokens.
        //
        // TimeInterpolator, from android.animation and not view.animation.Interpolator, is what the
        // signature actually returns, and it has to be: the *Interpolator attributes point at an
        // @interpolator RESOURCE, and for the legacy string forms MotionUtils builds a
        // PathInterpolator itself. Both attribute names exist in 1.14
        // (motion/res/values/attrs.xml declares motionEasingEmphasizedDecelerateInterpolator and
        // motionDurationLong2), so neither of these is a hopeful guess at a token name.
        //
        // The duration is widened from int to long on the way into `enter`; that is deliberate, since
        // everything it is handed to (setStartDelay, setDuration) takes a long.
        TimeInterpolator spatial = MotionUtils.resolveThemeInterpolator(this,
                com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator,
                AnimationUtils.loadInterpolator(this, android.R.interpolator.decelerate_quint));
        long enter = MotionUtils.resolveThemeDuration(this,
                com.google.android.material.R.attr.motionDurationLong2, 450);
        // The stagger is a fraction of the duration rather than three pinned numbers: it is what
        // makes three views read as a sequence instead of one sliding panel, and it only does that
        // if it keeps its proportion when the duration changes.
        long step = enter / 6;
        rise(hero, 0, enter, spatial);
        rise(findViewById(R.id.intro_title), step, enter, spatial);
        rise(findViewById(R.id.intro_body), step * 2, enter, spatial);
        rise(findViewById(R.id.intro_next), step * 3, enter, spatial);

        hero.setScaleX(0.8f);
        hero.setScaleY(0.8f);
        hero.animate().scaleX(1f).scaleY(1f).setDuration(enter)
                .setInterpolator(spatial).start();
    }

    private void rise(View v, long delay, long duration, TimeInterpolator spatial) {
        v.setAlpha(0f);
        // RISE_DP is a dp figure and setTranslationY takes pixels, so it must be converted here;
        // left as-is it becomes physical pixels, small enough on a high-density phone to read as a
        // wobble rather than an arrival.
        v.setTranslationY(RISE_DP * v.getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(duration).setInterpolator(spatial).start();
    }

    /**
     * Between the two pages, on M3's own lateral transition.
     *
     * <p>{@link MaterialSharedAxis} on X is the transition for peer destinations: the pages slide
     * and cross-fade in the direction of travel, so going back looks like going back rather than
     * like a second forward step. Left on its themed durations for the reason recorded on
     * {@code Ui.visibilityMotion}: setting a duration on the set overwrites the spec.
     */
    private void page(boolean forward) {
        MaterialSharedAxis axis = new MaterialSharedAxis(MaterialSharedAxis.X, forward);
        TransitionManager.beginDelayedTransition((ViewGroup) findViewById(R.id.welcome_root), axis);
        intro.setVisibility(forward ? View.GONE : View.VISIBLE);
        choose.setVisibility(forward ? View.VISIBLE : View.GONE);
        // The page turning is a small, physical event, so it gets the tick the rest of the app gives
        // a control that moved. Not the click feedback; the button already played that.
        Haptics.tick(forward ? choose : intro);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PairSheet s = sheet;
        sheet = null;
        // Dismissing runs the sheet's own shutdown, the same path Back takes, which is what closes
        // the pairing window rather than leaving it advertising without a surface.
        if (s != null) s.dismiss();
    }

    private void finishWith(String choice) {
        setResult(Activity.RESULT_OK, new android.content.Intent().putExtra(EXTRA_CHOICE, choice));
        finish();
    }

    /**
     * How far the three text blocks travel. The one number still written here: a distance, not a
     * duration, and M3 has no token for it: the spec says "a short distance", and 32dp is the one
     * that reads as a rise rather than a slide at every screen size this app sees.
     */
    private static final float RISE_DP = 32f;
}
