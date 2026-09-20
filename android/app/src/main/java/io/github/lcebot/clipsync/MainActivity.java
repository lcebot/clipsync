package io.github.lcebot.clipsync;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.transition.MaterialFadeThrough;

/**
 * Two pages behind a bottom navigation bar, Settings and Log, cross-faded into each other under
 * an M3 collapsing top app bar. The connection status is the bar's only menu action, a
 * Chip whose icon is the pulsing dot; tapping it opens the peer and address in full. The actions
 * are extended FABs bottom-right, a different pair per page: Settings gets "Start", or "Stop" plus
 * "Apply" once the service runs, and Log gets "Copy" and "Clear".
 *
 * <p>What this class still does after the split is assembly and the things only an Activity can do:
 * the lifecycle, the two-page navigation, the window insets, the action state machine, the status
 * file's watch-and-render, and starting or stopping the service. The three surfaces themselves are
 * elsewhere and are reached only through their own small interfaces:
 *
 * <ul>
 *   <li>{@link SettingsForm}: the form, its validation and everything the config file means. Every
 *       field is validated live; Apply is enabled only when all of them are valid and sends a
 *       RELOAD to the running service (no restart).
 *   <li>{@link LogPane}: the log tail, its poll and its follow-the-bottom rule.
 *   <li>{@link StatusSheet}: the connection-details bottom sheet and its keyed diff.
 * </ul>
 *
 * <p>The split is by surface and not by kind on purpose: each of those three owns a set of views
 * nothing else touches, which is what lets them be reasoned about one at a time. Anything that has
 * to see two of them at once, such as which page is in front, where an inset lands, or what the service is
 * doing, is here, because that is the only place it can be true.
 */
public class MainActivity extends AppCompatActivity implements SettingsForm.Host, StatusSheet.Host {
    private SettingsForm form;
    private LogPane logPane;
    private StatusSheet details;

    // app bar / actions
    private AppBarLayout appbar;
    private MaterialToolbar toolbar;
    private CollapsingToolbarLayout collapsing;
    // the layout's own margins, kept so insets are added to them and not to themselves
    private int baseTitleStart, baseTitleEnd, baseFabMargin;
    private View coordinator;
    private BottomNavigationView nav;
    private ExtendedFloatingActionButton stopFab, applyFab;      // Settings tab
    private ExtendedFloatingActionButton copyFab, clearFab;      // Log tab
    // pages
    private ViewGroup pages;
    private NestedScrollView pageSettings, pageLog;
    private View batteryRow, batteryFix;
    private TextView batteryText;
    // status
    private Chip statusChip;
    private int shownDot, shownLabel;                       // colours already on the chip
    private OnBackPressedCallback backToSettings;
    // The chip does not keep its own copy of the peer list: the sheet reads the snapshot when it
    // opens, so there is nothing to keep in step here.
    private boolean wasConnected;
    private ValueAnimator pulse;
    // action state: which of Start / Stop + Apply is shown, and what we are waiting for
    private boolean serviceRunning, waitingForStop, waitingForStart;
    private boolean onLogTab, configValid;
    // mirrors of the FABs' own shown/hidden state, so we only drive show()/hide() on a real change
    private boolean stopShown, applyShown, logFabsShown;
    private int applyIcon;
    private long waitingSince;
    private static final long WAIT_TIMEOUT_MS = 12_000;
    private static final String KEY_LOG_TAB = "log_tab";

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** True between onResume and onPause: the other half of "should the log be polled right now". */
    private boolean resumed;

    /**
     * Read the status file and render it. Posted, never called from the observer's thread.
     *
     * <p>Both users of a status change end here: the file watch, which is the normal path, and the
     * slow poll below.
     */
    private final Runnable statusChanged = this::refreshStatus;
    /**
     * Coalesces a burst. One event on the service side, such as a network change or a reload, makes several
     * threads rewrite the file within a few milliseconds of each other, and rendering each of those
     * would start a transition and cancel it with the next.
     */
    private static final long STATUS_DEBOUNCE_MS = 60;
    /**
     * The backstop, and it cannot be removed however good the watch is, because two of the things
     * this render decides are <b>timeouts</b>: {@code Snapshot.alive()}, which is how a service that
     * was killed is noticed, and {@link #WAIT_TIMEOUT_MS}, which hands the buttons back when a start
     * never arrives. Neither has an event; they are both the absence of one, so something has to
     * look. Five seconds is fine against a 120 s liveness window and a 12 s wait, and is a fraction
     * of the work a one-second poll would do.
     */
    private static final long STATUS_POLL_MS = 5_000;
    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, STATUS_POLL_MS);
        }
    };
    /** Held in a field on purpose: an unreferenced FileObserver is collected and stops delivering. */
    private FileObserver statusWatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(this);
        setContentView(R.layout.activity_main);
        bind();
        form.loadFields();
        wire();
        form.validate();
        if (savedInstanceState == null) {
            // Open with the title showing. What keeps it open is settings_root being
            // focusableInTouchMode: without it the first text field takes focus on start and the
            // scroll container scrolls to it, taking the bar with it. This is the belt to that
            // braces.
            appbar.setExpanded(true, false);
        } else if (savedInstanceState.getBoolean(KEY_LOG_TAB)) {
            // Views do not save their own visibility, so after a recreate both pages come back at
            // their XML defaults, while the bottom bar restores its own selection independently,
            // without this, a rotation on the Log tab would land on Settings with "Log" still
            // highlighted.
            showPage(true, false);
            nav.setSelectedItemId(R.id.nav_log);             // no-op if it restored by itself
        }

        // an installed-but-never-started app is in the "stopped" state and gets no BOOT_COMPLETED;
        // opening the activity once (and starting the service) clears that.
        if (!Status.read(this).alive() && autoStartEnabled()) {
            try {
                Config.load(this);
                startForegroundService(new Intent(this, SyncService.class));
            } catch (RuntimeException ignored) {
                // invalid config: user will fix it and press Apply
            }
        }

        // Last, so it lands on top of a page that is already built: the choice it offers writes to
        // the very fields behind it, and a dialog racing the first layout pass would be reloading a
        // form that had not finished being filled.
        //
        // Only on a cold start. A recreate, such as a rotation, a theme change, or the system rebuilding the
        // task, runs onCreate again while the welcome screen is already on top of this activity,
        // and an unconditional call launched a second copy of it onto the stack every time.
        if (savedInstanceState == null) offerFirstRunIfUnconfigured();
    }

    private void bind() {
        // the two pages live in their own layout files and are pulled in with <include>, so this
        // stays one flat findViewById pass over the whole tree
        pages = findViewById(R.id.pages);
        pageSettings = findViewById(R.id.page_settings);
        pageLog = findViewById(R.id.page_log);
        batteryRow = findViewById(R.id.battery_row);
        batteryText = findViewById(R.id.battery_text);
        batteryFix = findViewById(R.id.battery_fix);

        form = new SettingsForm(this, this);
        logPane = new LogPane(pageLog, findViewById(R.id.log));
        details = new StatusSheet(this, this);

        // Offers this device's key, rather than going looking for one: a device with the key is the
        // provider, and a device without it goes through the welcome screen instead.
        Haptics.onClick(findViewById(R.id.pair), () -> { if (form.ensureDiscovery()) pairSheet = PairSheet.offer(this, pairHost); });
        Haptics.onClick(findViewById(R.id.setup), () -> welcome.launch(new Intent(this, WelcomeActivity.class)));

        appbar = findViewById(R.id.appbar);
        coordinator = findViewById(R.id.coordinator);
        nav = findViewById(R.id.nav);
        stopFab = findViewById(R.id.stop);
        applyFab = findViewById(R.id.apply);
        copyFab = findViewById(R.id.log_copy);
        clearFab = findViewById(R.id.log_clear);

        toolbar = findViewById(R.id.toolbar);
        collapsing = findViewById(R.id.collapsing);
        statusChip = findViewById(R.id.status_chip);       // a plain Toolbar child

        // The margins the layout starts with, read before any inset is added to them: the inset
        // listener runs repeatedly (rotation, a cutout coming into play) and has to add to the
        // designed value each time, not to whatever it left behind last time. The two pages keep
        // their own base paddings, for the same reason and in the same way.
        baseTitleStart = collapsing.getExpandedTitleMarginStart();
        baseTitleEnd = collapsing.getExpandedTitleMarginEnd();
        baseFabMargin = ((ViewGroup.MarginLayoutParams) applyFab.getLayoutParams()).getMarginEnd();

        // ---- the mirrors of component state, seeded from the components themselves.
        //
        // Every one of these exists so that a setter is only called when it would change something:
        // show()/hide(), setChipIconTint, setTextColor and setIconResource all invalidate or request
        // a layout unconditionally, and this path runs on every status refresh. What they must not
        // do is start out disagreeing with the view, which is one frame of wrong state and, worse, a
        // bug that appears when someone edits the layout and not this file.
        //
        // So the three that the layout does state are READ from it, and the three it does not,
        // the chip's icon tint, the chip's label colour, the Apply FAB's icon, are WRITTEN here and
        // recorded in the same breath. Either way the field and the view cannot be out of step.
        stopShown = stopFab.getVisibility() == View.VISIBLE;
        applyShown = applyFab.getVisibility() == View.VISIBLE;
        logFabsShown = copyFab.getVisibility() == View.VISIBLE;
        shownDot = MaterialColors.getColor(statusChip, com.google.android.material.R.attr.colorOutline);
        statusChip.setChipIconTint(ColorStateList.valueOf(shownDot));
        shownLabel = statusChip.getCurrentTextColor();
        applyIcon = R.drawable.ic_restart;
        applyFab.setIconResource(applyIcon);
    }

    // ------------------------------------------------------------------ wiring
    private void wire() {
        form.wire();

        Haptics.onClick(applyFab, this::apply);
        Haptics.onClick(stopFab, () -> {
            setAutoStart(false);                       // watchdog / boot must not bring it back
            stopService(new Intent(this, SyncService.class));
            waitingForStop = true;
            waitingSince = System.currentTimeMillis();
            refreshActions();                          // greys Stop out until the service is gone
            snack(R.string.snack_stopped);
        });
        Haptics.onClick(clearFab, () -> logPane.clear());
        Haptics.onClick(copyFab, () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync log", logPane.text()));
            snack(R.string.snack_log_copied);
        });
        // The secondary FAB rides to the left of the primary one; following the primary's animated
        // width by translation keeps the pairing out of the layout pass entirely
        pairFabs(applyFab, stopFab);
        pairFabs(copyFab, clearFab);
        Haptics.onClick(batteryFix, () -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        });

        // Shrink to the icons while the page scrolls down, extend again on the way up.
        //
        // This cannot be left to ExtendedFloatingActionButtonBehavior, which is checked here
        // because the reason is not the obvious one. That Behavior has no nested-scroll hooks at
        // all; it reacts only in onDependentViewChanged, and shouldUpdateVisibility() returns
        // early unless the FAB's layout_anchor IS the AppBarLayout. These FABs sit in the corner
        // with no anchor, so it never runs. (Anchoring them would move them onto the app bar,
        // which is the whole point of not doing it.)
        shrinkOnScroll(pageSettings, applyFab, stopFab);
        shrinkOnScroll(pageLog, copyFab, clearFab);

        // Edge-to-edge. Both children are handed the full insets rather than left to the default
        // serial dispatch, where the first one to consume them starves the other: the app bar
        // needs the top, the navigation bar needs the bottom, and each applies its own (the root
        // consumes nothing).
        //
        // The horizontal pair is only non-zero beside a display cutout, and M3's rule there is that
        // a container may run under the cutout while anything readable or touchable steps aside. So
        // it is never applied to a view that paints a surface, not the CoordinatorLayout, not the
        // app bar, not the log pane, only to the content inside them. BottomNavigationView already
        // does exactly this for itself, which is why its bar spans the screen while its items sit
        // clear of the camera; everything else here now matches it.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Insets are absolute (left/right) and half of what they are applied to is relative
            // (Start/End). The two coincide in LTR, which is why mixing them went unnoticed, and are
            // swapped in RTL, so a cutout on the physical left was being stepped around on the
            // right. setPadding below is absolute and takes the insets as they come; everything
            // named Start/End takes these two instead.
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int startInset = rtl ? bars.right : bars.left;
            int endInset = rtl ? bars.left : bars.right;
            // Each page pads its own content, because each knows which of its views paints a surface
            // and which carries the text.
            form.applyInsets(bars.left, bars.right);
            logPane.applyInsets(bars.left, bars.right);
            toolbar.setPadding(bars.left, toolbar.getPaddingTop(), bars.right, toolbar.getPaddingBottom());
            // the expanded title is drawn by the CollapsingToolbarLayout itself and never sees the
            // toolbar's padding, so it needs the same offset stated separately; without it the
            // title jumps sideways between its expanded and collapsed positions beside a cutout
            collapsing.setExpandedTitleMarginStart(baseTitleStart + startInset);
            collapsing.setExpandedTitleMarginEnd(baseTitleEnd + endInset);
            // setLayoutParams always requests a layout, and this listener also runs whenever the
            // IME opens or closes, so only when the value really changed, or a keyboard appearing
            // mid-animation would drop a stray measure into a shrink or extend.
            // The FABs are gravity=bottom|end, so the inset that matters to them is the one on
            // their own end: the right in LTR, the left in RTL.
            int margin = baseFabMargin + endInset;
            for (ExtendedFloatingActionButton f : new ExtendedFloatingActionButton[]{applyFab, stopFab, copyFab, clearFab}) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) f.getLayoutParams();
                if (lp.getMarginEnd() != margin) {
                    lp.setMarginEnd(margin);
                    f.setLayoutParams(lp);
                }
            }
            ViewCompat.dispatchApplyWindowInsets(coordinator, insets);
            ViewCompat.dispatchApplyWindowInsets(nav, insets);
            return insets;
        });

        // Back from the Log returns to Settings, the start destination, rather than leaving the
        // app. Registered through the dispatcher (not onBackPressed) so the platform knows the app
        // will consume the gesture: with android:enableOnBackInvokedCallback the system then shows
        // no "leaving the app" preview while this is enabled, and the real one when it is not.
        backToSettings = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                nav.setSelectedItemId(R.id.nav_settings);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToSettings);

        nav.setOnItemSelectedListener(item -> {
            Haptics.tick(nav);
            showPage(item.getItemId() == R.id.nav_log, true);
            return true;
        });

        // The same read builds the chip and the sheet, which is why the snapshot is taken here and
        // handed over rather than read again inside the sheet.
        Haptics.onClick(statusChip, () -> details.show(Status.read(this)));
        expandTouch(statusChip);
        // the dot is the chip's icon, so the pulse animates that drawable rather than a second view
        pulse = ValueAnimator.ofInt(255, 60);
        pulse.setDuration(1400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.addUpdateListener(a -> {
            if (statusChip.getChipIcon() == null) return;
            int alpha = (Integer) a.getAnimatedValue();
            statusChip.getChipIcon().setAlpha(alpha);
            statusChip.invalidate();
        });
    }

    /**
     * Swaps the visible page.
     *
     * <p>The two pages want different app bars. Settings gets the collapsing one: open on a cold
     * start, then free to follow the scroll, and reopened on the way back whenever the page is at
     * the top, since a collapsed bar over un-scrolled content reads as broken. The Log wants every
     * pixel it can get, so it arrives collapsed and stays that way (nested scrolling is switched off
     * on that page in {@link LogPane}, which is the whole of the "always collapsed" rule).
     *
     * <p>{@code animate} is false when restoring after a recreate: there is no state to move from.
     */
    private void showPage(boolean toLog, boolean animate) {
        if (toLog == onLogTab) return;                       // re-tapping the current tab
        // MaterialFadeThrough is M3's own transition for a navigation bar destination change: the
        // outgoing page fades and scales down, the incoming one fades in. No lateral motion, which
        // the spec reserves for peers in a sequence.
        if (animate) TransitionManager.beginDelayedTransition(pages, new MaterialFadeThrough());
        pageSettings.setVisibility(toLog ? View.GONE : View.VISIBLE);
        pageLog.setVisibility(toLog ? View.VISIBLE : View.GONE);
        if (toLog) appbar.setExpanded(false, animate);
        else if (pageSettings.getScrollY() == 0) appbar.setExpanded(true, animate);
        onLogTab = toLog;
        backToSettings.setEnabled(toLog);                    // back only means something off Settings
        refreshActions();
        // The poll follows the page: reading the log file while Settings is in front is work whose
        // result nothing displays. showPage also runs from onCreate, before onResume, which is why
        // this is gated on `resumed` as well: onResume starts it.
        logPane.setPolling(toLog && resumed);
        if (toLog) logPane.follow();
    }

    /**
     * Keeps {@code second} pinned 12dp <em>before</em> {@code first}, whatever width it animates to.
     *
     * <p>Translation, so the pairing stays out of the layout pass entirely, but translation is in
     * absolute pixels while the FABs are placed with {@code gravity=bottom|end}, and those two only
     * agree in a left-to-right layout. In RTL the primary FAB sits at the left edge and a negative
     * translation moved the secondary one off the screen entirely. The sign is the whole fix: "12dp
     * before it" is leftwards in LTR and rightwards in RTL.
     *
     * <p>Kept rather than replaced by an M3 Expressive FAB menu or button group. Checked against
     * Material 1.14.0 rather than assumed, because both halves of the answer turned out to matter:
     *
     * <ul>
     *   <li><b>FAB menu does not exist here at all.</b> Its own doc says so in as many words: "The
     *       FAB menu component is currently not available as a native <b>Views</b> component", and
     *       offers only Compose interop through a {@code ComposeView}
     *       (docs/components/FloatingActionButtonMenu.md @ 1.14.0). Adding Compose to this app to
     *       host one control is not a trade worth making.
     *   <li><b>Button group does exist</b>: {@code com.google.android.material.button
     *       .MaterialButtonGroup}, plus {@code MaterialSplitButton}, and it is the wrong shape
     *       twice over: it arranges {@code MaterialButton}s, not FABs, so it cannot be the
     *       screen's primary action at all; and the split-button variant is precisely the thing to
     *       avoid here, since it hides its second action behind a press. These two are peers that
     *       must both be reachable at a glance: "Stop" and "Apply" are the running service's two
     *       answers, and "Copy" and "Clear" are always live together.
     * </ul>
     */
    private void pairFabs(ExtendedFloatingActionButton first, ExtendedFloatingActionButton second) {
        first.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int gap = first.getWidth() + dp(12);
            boolean rtl = second.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            second.setTranslationX(rtl ? gap : -gap);
        });
    }

    /**
     * Give a small control a 48dp touch target without changing how big it looks.
     *
     * <p>The status chip is about 32dp tall because it is a status *readout* that happens to be
     * tappable, and {@code ensureMinTouchTargetSize} is switched off on it deliberately: a chip
     * padded out to 48dp in an app bar reads as a button and crowds the title. That decision stands.
     * What does not stand is the consequence: it is the only way into the connection details, and a
     * 32dp target is below what anyone can reliably hit, which is an accessibility failure rather
     * than a matter of taste.
     *
     * <p>A {@link android.view.TouchDelegate} is the way to have both. It lives on the PARENT and
     * routes touches in a rectangle to the child, so nothing about the chip's own size, padding or
     * appearance changes; only where the parent decides a touch belongs.
     *
     * <p>Recomputed on every layout, because the rectangle is in the parent's coordinates and the
     * chip moves whenever its text does ("Connected (2)" is wider than "Idle"). A delegate installed
     * once would point at wherever the chip was the first time it was measured.
     *
     * <p>One caveat, recorded because it is the way this breaks: a View has exactly one touch
     * delegate, so if another child of the same Toolbar ever needs one, this becomes a
     * {@code TouchDelegateComposite} rather than a second call.
     */
    private void expandTouch(View v) {
        View parent = v.getParent() instanceof View p ? p : null;
        if (parent == null) return;
        int min = dp(48);
        v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            android.graphics.Rect hit = new android.graphics.Rect();
            view.getHitRect(hit);
            hit.inset(-Math.max(0, (min - hit.width()) / 2), -Math.max(0, (min - hit.height()) / 2));
            parent.setTouchDelegate(new android.view.TouchDelegate(hit, view));
        });
    }

    private void shrinkOnScroll(NestedScrollView page, ExtendedFloatingActionButton... fabs) {
        page.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (y > oy + dp(4)) for (ExtendedFloatingActionButton f : fabs) f.shrink();
            else if (y < oy - dp(4) || y <= 0) for (ExtendedFloatingActionButton f : fabs) f.extend();
        });
    }

    @Override
    public void snack(int textRes) {
        Snackbar.make(coordinator, textRes, Snackbar.LENGTH_SHORT).setAnchorView(anchor()).show();
    }

    /** The form says whether Apply may be pressed; the buttons are this class's to move. */
    @Override
    public void onValidity(boolean valid) {
        configValid = valid;
        refreshActions();
    }

    // ------------------------------------------------------------------ pairing
    // A short code shown on the device that already has the key and typed into the one that wants
    // it: the code derives a one-time channel over mDNS, and the PSK travels across it.
    /**
     * The welcome screen's answer, brought back to the page whose fields the answer rewrites.
     *
     * <p>Registered as a field, which is not decoration: {@code registerForActivityResult} has to be
     * called before the activity is STARTED, so it cannot live inside a click listener however much
     * it belongs there.
     */
    private final androidx.activity.result.ActivityResultLauncher<Intent> welcome =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), r -> {
                // Whatever happened there, the key may have changed, because pairing now runs on that
                // screen rather than handing the job back, so this is a refresh and not a dispatch.
                // MANUAL needs nothing done either: the page behind it IS the manual setup.
                reloadAfterPairing();
            });

    /** What the pairing sheets do to this page when they change the key. */
    private final PairSheet.Host pairHost = new PairSheet.Host() {
        @Override public void keyChanged() {
            reloadAfterPairing();
            // The service is started by the sheet; this is only the part of it this page owns:
            // the buttons have to show that something is expected to come up.
            setAutoStart(true);
            waitingForStart = !Status.read(MainActivity.this).alive();
            waitingSince = System.currentTimeMillis();
            refreshActions();
        }
    };

    /**
     * A new key has been written to the file, so the form is showing the old one.
     *
     * <p>{@link SettingsForm#loadFields} rather than setting the PSK field directly: pairing turns
     * discovery on as well, and re-reading is the only version of this that cannot fall behind
     * whatever else pairing decides to write next.
     */
    void reloadAfterPairing() {
        form.loadFields();
        form.validate();
    }

    /**
     * Offer the first-run choice to a device that has no key.
     *
     * <p>Keyed on the PSK alone, because that is the one setting without which nothing works at all:
     * every other field has a usable default. Asked once per launch and never again once a key
     * exists, so the escape hatch really is an escape rather than a question that keeps returning.
     */
    private void offerFirstRunIfUnconfigured() {
        if (Config.checkPsk(Config.raw(this).getProperty("psk", "")) == null) return;
        welcome.launch(new Intent(this, WelcomeActivity.class));
    }

    /** Above whichever FAB pair is on screen, above the navigation bar otherwise. */
    private View anchor() {
        if (onLogTab) return copyFab;
        return applyFab.getVisibility() == View.VISIBLE ? applyFab : nav;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * When stopped, a single "Start"; when running, "Stop" and "Apply". After pressing one of them the
     * button greys out until the service actually reaches the new state (or the wait times out),
     * and a state change made from anywhere else moves the buttons just the same. The Log tab's
     * Copy / Clear pair only follows the tab: both stay enabled at all times.
     * Everything here goes through the components' own animations, and nothing is touched unless
     * it actually changed; scrolling drives shrink()/extend() and must not be measured against.
     */
    private void refreshActions() {
        boolean showStop = serviceRunning && !onLogTab;
        boolean showApply = !onLogTab;
        // show()/hide() are the component's own animations; going through setVisibility would
        // desync its internal state
        if (showStop != stopShown) {
            stopShown = showStop;
            if (showStop) stopFab.show(); else stopFab.hide();
        }
        if (showApply != applyShown) {
            applyShown = showApply;
            if (showApply) applyFab.show(); else applyFab.hide();
        }
        if (onLogTab != logFabsShown) {
            logFabsShown = onLogTab;
            if (onLogTab) { copyFab.show(); clearFab.show(); } else { copyFab.hide(); clearFab.hide(); }
        }
        stopFab.setEnabled(!waitingForStop);

        Ui.setTextIfChanged(applyFab, getString(serviceRunning ? R.string.action_apply : R.string.action_start));
        // setIconResource() always requests a layout; this runs on every status refresh, such as a
        // file-watch event or the 5 s backstop, so an unconditional call would drop a stray
        // measure into whatever shrink/extend is in flight
        int icon = serviceRunning ? R.drawable.ic_restart : R.drawable.ic_play;
        if (icon != applyIcon) { applyIcon = icon; applyFab.setIconResource(icon); }
        applyFab.setEnabled(configValid && !waitingForStart);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_LOG_TAB, onLogTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        logPane.onResume();
        wasConnected = false;
        logPane.setPolling(onLogTab);
        ui.post(statusPoll);
        statusWatch = Status.watch(this, () -> {
            // Off the observer's thread and coalesced in one step: removeCallbacks + postDelayed is
            // the whole debounce, and it lands the work on the thread that may touch views.
            ui.removeCallbacks(statusChanged);
            ui.postDelayed(statusChanged, STATUS_DEBOUNCE_MS);
        });
        statusWatch.startWatching();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        logPane.onPause();
        ui.removeCallbacks(statusPoll);
        ui.removeCallbacks(statusChanged);
        if (statusWatch != null) {
            statusWatch.stopWatching();
            statusWatch = null;
        }
        pulse.cancel();
    }

    /**
     * The open pairing sheet, or null. Held for one reason: {@link #onDestroy()}.
     *
     * <p>A Dialog is not part of the Activity's view tree and is not torn down with it. On a
     * rotation the Activity is destroyed and rebuilt while this carries on, and for a pairing sheet
     * that is worse than a leaked window: its {@link PairProvider} keeps advertising and keeps
     * handing out the key with nothing on screen to say so. {@link StatusSheet} holds its own dialog
     * for the same reason and is dismissed alongside it.
     */
    private PairSheet pairSheet;

    /**
     * Everything this Activity owns that would otherwise outlive it.
     *
     * <p>Dialogs are most of it, and the pairing sheet is the one that matters: it holds a
     * {@link PairProvider}, which holds an mDNS advertisement and an accept loop that hands out the
     * PSK. A rotation destroys this Activity without touching either, so without this the code
     * window would survive on the network with no surface showing the code: nobody watching, and
     * the key still on offer.
     *
     * <p>Dismissing is what stops them: the sheet's own dismiss listener runs its shutdown, which is
     * the same path the user pressing Back takes. The log pane's reader thread is the remainder.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        PairSheet p = pairSheet;
        pairSheet = null;
        if (p != null) p.dismiss();
        details.dismiss();
        logPane.shutdown();
    }

    // ------------------------------------------------------------------ auto-start switch
    /**
     * "Auto-start" = the BootReceiver component being enabled. Stop disables it so neither
     * BOOT_COMPLETED nor the system_server watchdog bring the service back; Apply re-enables it.
     */
    private boolean autoStartEnabled() {
        int s = getPackageManager().getComponentEnabledSetting(new ComponentName(this, BootReceiver.class));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setAutoStart(boolean on) {
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, BootReceiver.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    // ------------------------------------------------------------------ status
    private void refreshStatus() {
        Status.Snapshot s = Status.read(this);
        if (!s.alive() && !"stopped".equals(s.state)) {
            // the service process is gone without saying goodbye (killed); the watchdog restarts it
            s = Status.stopped(getString(autoStartEnabled() ? R.string.state_not_running : R.string.state_autostart_off),
                    s.suspended);
        }
        boolean connected = "connected".equals(s.state) || "relay".equals(s.state);
        boolean busy = "connecting".equals(s.state);
        boolean stopped = "stopped".equals(s.state);

        // this runs on every status change and on the 5 s backstop: every setter here either
        // invalidates or requests a layout, so none of them is called unless the value changed
        int dot = MaterialColors.getColor(statusChip, connected ? androidx.appcompat.R.attr.colorPrimary
                : busy ? com.google.android.material.R.attr.colorTertiary : com.google.android.material.R.attr.colorOutline);
        if (dot != shownDot) {
            shownDot = dot;
            statusChip.setChipIconTint(ColorStateList.valueOf(dot));
        }
        int label = MaterialColors.getColor(statusChip, stopped
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface);
        if (label != shownLabel) {
            shownLabel = label;
            statusChip.setTextColor(label);
        }

        // The chip carries a count, not a connection kind: with several peers "Direct (LAN)" is a
        // fact about one of them and the chip has no room to say which. What kind each link is now
        // belongs beside that link, in the sheet.
        //
        // The count is Snapshot.count(), which is the number of DIRECT peers, the devices this one
        // holds an open connection to. Peers known only through another device's roster are not in
        // it, on purpose: the chip says how connected THIS device is, and a device reachable only
        // second-hand is not something this one is connected to. They are listed in the sheet under
        // their own heading, which is where a number that included them would have to be explained.
        String titleText = connected
                ? getString("relay".equals(s.state) ? R.string.state_relay : R.string.state_connected, s.count())
                : getString(switch (s.state) {
                    case "connecting" -> R.string.state_connecting;
                    case "no network" -> R.string.state_no_network;
                    case "idle" -> R.string.state_idle;
                    default -> R.string.state_stopped;
                });
        // same path: only touch the TextView when the text really changed, or every status refresh
        // would queue a layout pass for the status chip
        Ui.setTextIfChanged(statusChip, titleText);
        // Tappable whenever there is anything to list, which now includes "nothing is connected and
        // here is why", the case the sheet is most worth opening for.
        statusChip.setClickable(!s.peers.isEmpty() || !s.indirectPeers.isEmpty() || !s.targets.isEmpty());
        // The same snapshot the chip was just built from, rather than a second read: two reads a
        // second of a file another process rewrites can disagree, and the chip saying Connected (2)
        // above a sheet listing one peer is the kind of contradiction nobody can explain.
        details.refresh(s);

        if (connected != wasConnected) {
            wasConnected = connected;
            if (connected) {
                if (!pulse.isRunning()) pulse.start();
            } else {
                pulse.cancel();
                if (statusChip.getChipIcon() != null) statusChip.getChipIcon().setAlpha(255);
                statusChip.invalidate();
            }
        }

        // the actions follow the service: Start alone while it is stopped, Stop + Apply while it runs
        boolean running = !stopped;
        if (running != serviceRunning) {
            serviceRunning = running;
            if (running) waitingForStart = false; else waitingForStop = false;
        }
        if ((waitingForStart || waitingForStop) && System.currentTimeMillis() - waitingSince > WAIT_TIMEOUT_MS) {
            waitingForStart = waitingForStop = false;  // the service never got there; hand control back
        }
        refreshActions();

        // background-permission card
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (exempt && !s.suspended) {
            batteryRow.setVisibility(View.GONE);
        } else {
            batteryRow.setVisibility(View.VISIBLE);
            // setTextIfChanged, like every other setter on this status-refresh path: setText always
            // requests a layout, so an unconditional call here queues one on every refresh.
            Ui.setTextIfChanged(batteryText, getString(exempt ? R.string.battery_still_frozen : R.string.battery_on));
            batteryFix.setVisibility(exempt ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ apply
    private void apply() {
        if (!form.validate()) return;
        try {
            Config c = Config.save(this, form.values());
            setAutoStart(true);
            Intent svc = new Intent(this, SyncService.class);
            if (Status.read(this).alive()) {
                startService(svc.setAction(SyncService.ACTION_RELOAD));      // in place: no restart, no process churn
            } else {
                startForegroundService(svc);                                 // this was the "Start" button
                waitingForStart = true;
                waitingSince = System.currentTimeMillis();
                refreshActions();
            }
            Logger.i("config applied: " + (c.peers.isEmpty() ? "no addresses" : String.join(", ", c.peers) + ":" + c.port)
                    + (c.discovery ? " + discovery (browse " + c.mdnsTimeoutMs + " ms)" : "")
                    + ", " + c.threads + " streams, files in " + c.filesDir);
            snack(R.string.snack_applied);
        } catch (IllegalArgumentException e) {
            // should not happen (live validation), but map it back to a field anyway
            form.showSaveProblem(e);
        } catch (Exception e) {
            // The user gets a sentence; the log gets the exception. A stack-trace class name in a
            // Snackbar, such as "java.io.IOException: /storage/...: EACCES (Permission denied)", is not
            // something anyone can act on, and it was also the only record of what went wrong.
            Logger.w("config save failed", e);
            String msg = e.getMessage() == null || e.getMessage().isBlank()
                    ? getString(R.string.save_failed_generic) : e.getMessage();
            Snackbar.make(coordinator, getString(R.string.snack_save_failed, msg), Snackbar.LENGTH_LONG)
                    .setAnchorView(anchor()).show();
        }
    }
}
