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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.transition.ChangeBounds;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.transition.MaterialFade;
import com.google.android.material.transition.MaterialFadeThrough;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Two pages behind a bottom navigation bar — Settings and Log — cross-faded into each other under
 * an M3 collapsing top app bar. The connection status is the bar's only menu action, a
 * Chip whose icon is the pulsing dot; tapping it opens the peer and address in full. The actions
 * are extended FABs bottom-right, a different pair per page: Settings gets "Start", or "Stop" plus
 * "Apply" once the service runs, and Log gets "Copy" and "Clear".
 * Every field is validated live; Apply is enabled only when all of them are valid and sends a
 * RELOAD to the running service (no restart).
 */
public class MainActivity extends AppCompatActivity {
    // fields
    private TextInputLayout portL, pskL, textKbL, fileMbL, fileMbLocalL, pathL, keepHoursL, keepMbL;
    private TextInputEditText port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb;
    private MaterialSwitch discovery, direct, pskRotate;
    /** The paragraph under {@link #pskRotate}, shown only while it is on. */
    private View pskRotateHelp;
    /** The Direct connections list, and this device's own addresses — one class, twice (§4a). */
    private AddressList peerList, ownList;
    private MaterialButton pskRandom;
    /** The group each switch governs. Whole cards now, because the switches sit outside them. */
    private View discoveryCard, directCard;
    /** Shown under the pair when both switches are off — see {@link #validate()}. */
    private View pathsError;
    /**
     * How long the page takes to close a gap. The only pinned duration in the motion set — the fade
     * itself keeps M3's own asymmetric timing — and anything that has to move in step with a reflow
     * uses this rather than a number of its own.
     */
    private static final long REFLOW_MS = 220;

    /** The collapsible own-addresses group: the card that is pressed, its body, and the chevron. */
    private View ownCard, ownContent, ownChevron;
    /** Whether that group currently holds a bad address — see the card's click listener. */
    private boolean ownHasError;
    private Slider browse, threads;
    private TextView browseLabel, threadsLabel;
    private ViewGroup settingsRoot;
    // app bar / actions
    private AppBarLayout appbar;
    private MaterialToolbar toolbar;
    private CollapsingToolbarLayout collapsing;
    // the layout's own paddings and margins, kept so insets are added to them and not to themselves
    private int basePadSettings, basePadLog, baseTitleStart, baseTitleEnd, baseFabMargin;
    private View coordinator;
    private BottomNavigationView nav;
    private ExtendedFloatingActionButton stopFab, applyFab;      // Settings tab
    private ExtendedFloatingActionButton copyFab, clearFab;      // Log tab
    // pages / log
    private ViewGroup pages;
    private NestedScrollView pageSettings, pageLog;
    private View batteryRow, batteryFix;
    private TextView log, batteryText;
    // status
    private Chip statusChip;
    private int shownDot, shownLabel;                       // colours already on the chip
    private OnBackPressedCallback backToSettings;
    // The chip's own mirror of the peer list is gone with the single connection it described: the
    // sheet reads the snapshot when it opens, so there is nothing to keep in step here.
    private boolean wasConnected;
    private ValueAnimator pulse;
    // action state: which of Start / Stop + Apply is shown, and what we are waiting for
    private boolean serviceRunning, waitingForStop, waitingForStart;
    private boolean onLogTab, configValid;
    private final Logger.Cursor logCursor = new Logger.Cursor();
    private boolean logToBottom;                            // jump to the newest line once laid out
    // mirrors of the FABs' own shown/hidden state, so we only drive show()/hide() on a real change
    private boolean stopShown, applyShown = true, logFabsShown;
    private int applyIcon;                                  // 0 = never set, so the first pass applies
    private long waitingSince;
    private static final long WAIT_TIMEOUT_MS = 12_000;
    private static final String KEY_LOG_TAB = "log_tab";

    private final Handler ui = new Handler(Looper.getMainLooper());
    // lines written in this process arrive here; lines written by the :sync process arrive through
    // the file, picked up by the poll below
    private final Logger.Listener logListener = line -> ui.post(this::showLog);
    // The log is a tail: it grows by appending, there is no "the whole thing changed" event to wait
    // for, and a second's latency on a line of text is not felt. So it is still polled.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Logger.refresh();
            showLog();
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * Read the status file and render it. Posted, never called from the observer's thread.
     *
     * <p>Both users of a status change end here: the file watch, which is the normal path, and the
     * slow poll below.
     */
    private final Runnable statusChanged = this::refreshStatus;
    /**
     * Coalesces a burst. One event on the service side — a network change, a reload — makes several
     * threads rewrite the file within a few milliseconds of each other, and rendering each of those
     * would start a transition and cancel it with the next.
     */
    private static final long STATUS_DEBOUNCE_MS = 60;
    /**
     * The backstop, and it cannot be removed however good the watch is, because two of the things
     * this render decides are <b>timeouts</b>: {@code Snapshot.alive()}, which is how a service that
     * was killed is noticed, and {@link #WAIT_TIMEOUT_MS}, which hands the buttons back when a start
     * never arrives. Neither has an event — they are both the absence of one — so something has to
     * look. Five seconds is fine against a 120 s liveness window and a 12 s wait, and it is a
     * twentieth of the work the old one-second poll did.
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

    /**
     * Brings the visible log up to date, appending where it can.
     *
     * <p>Appending is what makes the log usable in a bug report: the text is one TextView so a
     * selection can span any number of lines, and appending leaves that selection, the scroll
     * position and the already-laid-out text alone. Only a rebuild (the buffer was cleared, or has
     * dropped enough old entries to be worth trimming) replaces the text.
     *
     * <p>While anything is selected the log is left frozen — nothing reflows under the reader's
     * fingers, and nothing is lost either, because the cursor is only advanced by a read that
     * actually happened; the next tick catches up.
     *
     * <p>Following the tail is likewise only automatic while the view is already at the bottom, so
     * reading further up is not yanked away by the next line.
     */
    private void showLog() {
        if (log.hasSelection()) return;
        Logger.Tail tail = Logger.read(logCursor);
        if (tail.isEmpty()) return;
        // false both at the bottom and while the page is still GONE or unmeasured, which is what
        // we want: a page that has not been shown yet starts at the end
        if (!pageLog.canScrollVertically(1)) logToBottom = true;
        String text = String.join("\n", tail.lines);
        if (tail.replace) {
            // EDITABLE from the outset: append() would otherwise convert the buffer on its first
            // call, and that conversion is itself a setText that drops the selection
            log.setText(text, TextView.BufferType.EDITABLE);
        } else if (log.length() == 0) {
            log.append(text);
        } else {
            log.append("\n" + text);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(this);
        setContentView(R.layout.activity_main);
        bind();
        loadFields();
        wire();
        validate();
        if (savedInstanceState == null) {
            // Open with the title showing. What keeps it open is settings_root being
            // focusableInTouchMode: without it the first text field takes focus on start and the
            // scroll container scrolls to it, taking the bar with it. This is the belt to that
            // braces.
            appbar.setExpanded(true, false);
        } else if (savedInstanceState.getBoolean(KEY_LOG_TAB)) {
            // Views do not save their own visibility, so after a recreate both pages come back at
            // their XML defaults — while the bottom bar does restore its selection, which is how
            // a rotation on the Log tab used to land on Settings with "Log" still highlighted.
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
        offerFirstRunIfUnconfigured();
    }

    private void bind() {
        // the two pages live in their own layout files and are pulled in with <include>, so this
        // stays one flat findViewById pass over the whole tree
        discovery = findViewById(R.id.discovery);
        direct = findViewById(R.id.direct);
        peerList = new AddressList(this, findViewById(R.id.peers_box), findViewById(R.id.peer_add),
                R.string.hint_peer, false, listHost);
        ownList = new AddressList(this, findViewById(R.id.own_box), findViewById(R.id.own_add),
                R.string.hint_peer, true, listHost);
        pskRandom = findViewById(R.id.psk_random);
        pskRotate = findViewById(R.id.psk_rotate);
        pskRotateHelp = findViewById(R.id.psk_rotate_help);
        portL = findViewById(R.id.port_layout);       port = findViewById(R.id.port);
        pskL = findViewById(R.id.psk_layout);         psk = findViewById(R.id.psk);
        textKbL = findViewById(R.id.text_kb_layout);  textKb = findViewById(R.id.text_kb);
        fileMbL = findViewById(R.id.file_mb_layout);  fileMb = findViewById(R.id.file_mb);
        fileMbLocalL = findViewById(R.id.file_mb_local_layout); fileMbLocal = findViewById(R.id.file_mb_local);
        pathL = findViewById(R.id.path_layout);       path = findViewById(R.id.path);
        keepHoursL = findViewById(R.id.keep_hours_layout); keepHours = findViewById(R.id.keep_hours);
        keepMbL = findViewById(R.id.keep_mb_layout);  keepMb = findViewById(R.id.keep_mb);
        discoveryCard = findViewById(R.id.discovery_card);
        directCard = findViewById(R.id.direct_card);
        pathsError = findViewById(R.id.paths_error);
        ownContent = findViewById(R.id.own_content);
        ownChevron = findViewById(R.id.own_chevron);
        ownCard = findViewById(R.id.own_card);
        // What a screen reader is told about the group. Two separate things, and it needs both: the
        // *state* ("collapsed") so it can say what it is looking at, and a *label for the click
        // action* ("Show this device's addresses") so it can say what pressing would do. Neither is
        // derivable from the card's text, and without them the control announces as an unlabelled
        // clickable panel whose contents appear and disappear for no stated reason.
        ownCard.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                boolean open = ownContent.getVisibility() == View.VISIBLE;
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                        getString(open ? R.string.own_collapse : R.string.own_expand)));
            }
        });
        Haptics.onClick(ownCard, () -> {
            boolean open = ownContent.getVisibility() != View.VISIBLE;
            // Never close over an error. The message is inside the group, and hiding it would leave
            // Apply disabled with nothing on screen to say why. Doing nothing is not a dead end
            // either — the reason the tap was refused is the red line the user is looking at.
            if (!open && ownHasError) return;
            setOwnExpanded(open, true);
        });
        browse = findViewById(R.id.browse);
        browseLabel = findViewById(R.id.browse_label);
        // Offers this device's key, rather than going looking for one: a device with the key is the
        // provider, and a device without it goes through the welcome screen instead.
        Haptics.onClick(findViewById(R.id.pair), () -> { if (needsDiscovery()) PairSheet.offer(this, pairHost); });
        Haptics.onClick(findViewById(R.id.setup), () -> welcome.launch(new Intent(this, WelcomeActivity.class)));
        threads = findViewById(R.id.threads);
        threadsLabel = findViewById(R.id.threads_label);
        settingsRoot = findViewById(R.id.settings_root);
        batteryRow = findViewById(R.id.battery_row);
        batteryText = findViewById(R.id.battery_text);
        batteryFix = findViewById(R.id.battery_fix);
        pages = findViewById(R.id.pages);
        pageSettings = findViewById(R.id.page_settings);
        pageLog = findViewById(R.id.page_log);
        log = findViewById(R.id.log);

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

        // The paddings and margins the layout starts with, read before any inset is added to them:
        // the inset listener runs repeatedly (rotation, a cutout coming into play) and has to add
        // to the designed value each time, not to whatever it left behind last time.
        basePadSettings = settingsRoot.getPaddingLeft();
        basePadLog = log.getPaddingLeft();
        baseTitleStart = collapsing.getExpandedTitleMarginStart();
        baseTitleEnd = collapsing.getExpandedTitleMarginEnd();
        baseFabMargin = ((ViewGroup.MarginLayoutParams) applyFab.getLayoutParams()).getMarginEnd();
    }

    // ------------------------------------------------------------------ values <-> fields
    private void loadFields() {
        Properties p = Config.raw(this);
        discovery.setChecked(bool(p.getProperty("discovery", "true")));
        direct.setChecked(bool(p.getProperty("direct", "false")));
        peerList.setValues(Config.peerList(p.getProperty("peers", "")));
        ownList.setValues(Config.peerList(p.getProperty("own_addresses", "")));
        // Collapsed by default, but never over content: a device that has an address of its own
        // should show it, and a group the user cannot see is worse than one that takes a tap.
        setOwnExpanded(!ownList.values().isEmpty(), false);
        port.setText(p.getProperty("port", "47521"));
        psk.setText(p.getProperty("psk", ""));
        pskRotate.setChecked(bool(p.getProperty("psk_rotate", "false")));
        showRotateHelp(pskRotate.isChecked(), false);
        textKb.setText(String.valueOf(longOf(p, "max_bytes", 1048576) / 1024));
        fileMb.setText(String.valueOf(longOf(p, "max_file_bytes", 10485760) / (1024 * 1024)));
        fileMbLocal.setText(String.valueOf(longOf(p, "max_file_bytes_local", 104857600) / (1024 * 1024)));
        path.setText(p.getProperty("files_dir", Config.DEFAULT_FILES_DIR));
        keepHours.setText(String.valueOf(longOf(p, "keep_hours", 2)));
        keepMb.setText(String.valueOf(longOf(p, "keep_max_mb", 256)));
        int t = Config.snapThreads((int) longOf(p, "threads", 8));
        threads.setValue(indexOf(Config.THREAD_STEPS, t));
        threadsLabel.setText(getString(R.string.threads_label, t));
        int b = Config.snapBrowse((int) longOf(p, "mdns_timeout_ms", 4000));
        browse.setValue(indexOf(Config.BROWSE_STEPS_MS, b));
        browseLabel.setText(getString(R.string.browse_label, b));
        applySwitches(false);
    }

    // ------------------------------------------------------------------ the address lists
    /**
     * Both lists are {@link AddressList}, which owns the rows, the numbered hints, the blank-row rule
     * and the per-row errors. What this class still supplies is the motion and the scene root, since
     * those belong to the page rather than to either list.
     */
    private final AddressList.Host listHost = new AddressList.Host() {
        @Override public Transition motion(View changing) { return visibilityMotion(changing); }
        @Override public ViewGroup sceneRoot() { return settingsRoot; }
        @Override public void onChanged() { validate(); }
    };

    private static int indexOf(int[] steps, int v) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == v) return i;
        return 0;
    }

    private int threadsValue() {
        return Config.THREAD_STEPS[Math.max(0, Math.min(Config.THREAD_STEPS.length - 1, Math.round(threads.getValue())))];
    }

    private int browseValue() {
        return Config.BROWSE_STEPS_MS[Math.max(0, Math.min(Config.BROWSE_STEPS_MS.length - 1, Math.round(browse.getValue())))];
    }

    /**
     * Show / hide the card each switch governs. The switches are rows at page level above their
     * cards, so the card is the whole hideable unit and the switch is never inside what it hides.
     * Both cards are named as the fading views, because either or both can turn over in one call.
     *
     * @see #visibilityMotion(View...)
     */
    private void applySwitches(boolean animate) {
        int wantDiscovery = discovery.isChecked() ? View.VISIBLE : View.GONE;
        int wantDirect = direct.isChecked() ? View.VISIBLE : View.GONE;
        // validate() sets this one, just after this method returns and inside the same transition.
        int wantError = discovery.isChecked() || direct.isChecked() ? View.GONE : View.VISIBLE;

        if (animate)
            TransitionManager.beginDelayedTransition(settingsRoot, visibilityMotion(
                    turning(discoveryCard, wantDiscovery),
                    turning(directCard, wantDirect),
                    turning(pathsError, wantError)));

        discoveryCard.setVisibility(wantDiscovery);
        directCard.setVisibility(wantDirect);
        peerList.setEnabled(direct.isChecked());
    }

    /**
     * Open or close the own-addresses group.
     *
     * <p>Built from a card, a clickable header and the transition machinery that is already here,
     * because Material's View library has no expandable *container* — {@code ExpandableWidget} is
     * an interface the FAB and the Chip implement for themselves, not something a group of settings
     * can be. So the M3 parts are used and the assembly is local: a list-item-height header with the
     * platform ripple, a chevron that turns, and the same fade-and-reflow every other group on this
     * page uses when it appears.
     */
    private void setOwnExpanded(boolean open, boolean animate) {
        // Before the guard below, because the first call is usually a no-op — the group starts
        // collapsed in the layout and is asked to be collapsed — and a screen reader would then
        // never be told the state at all.
        ownCard.setStateDescription(getString(open ? R.string.own_state_expanded : R.string.own_state_collapsed));
        // Nothing to do is not the same as doing nothing cheaply: validate() runs on every keystroke
        // and calls this whenever the group holds an error, so without this guard every character
        // typed anywhere on the page would start a transition.
        if (open == (ownContent.getVisibility() == View.VISIBLE)) return;
        if (animate) TransitionManager.beginDelayedTransition(
                settingsRoot, visibilityMotion(turning(ownContent, open ? View.VISIBLE : View.GONE)));
        ownContent.setVisibility(open ? View.VISIBLE : View.GONE);
        // The chevron turns over exactly the span the group takes to reflow — REFLOW_MS is the one
        // ChangeBounds is pinned to. A rotation on its own timing would either finish early, over a
        // card that is still moving, or lag one that has already settled.
        float to = open ? 180f : 0f;
        if (animate) ownChevron.animate().rotation(to).setDuration(REFLOW_MS).start();
        else ownChevron.setRotation(to);
    }

    /**
     * The rotation explanation, faded in and out with everything below it sliding to follow.
     *
     * <p>Same motion as the settings groups, same reason it is one call: the paragraph is the only
     * view whose visibility changes, so it is the only one named, and everything else in the card
     * has to stay inside ChangeBounds' reach or it will not move when the gap opens under it.
     */
    private void showRotateHelp(boolean shown, boolean animate) {
        int want = shown ? View.VISIBLE : View.GONE;
        if (pskRotateHelp.getVisibility() == want) return;
        if (animate) TransitionManager.beginDelayedTransition(
                settingsRoot, visibilityMotion(pskRotateHelp));
        pskRotateHelp.setVisibility(want);
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
    private static View turning(View v, int want) {
        return v != null && v.getVisibility() != want ? v : null;
    }

    /**
     * A fresh key, straight into the field. Asked about first when one is already there: it
     * invalidates every other device at once, and a mis-tap that costs re-pairing the household is
     * not something to find out about afterwards.
     */
    private void newPsk() {
        if (Config.checkPsk(text(psk)) != null) {          // nothing usable there to lose
            psk.setText(Crypto.randomPskHex());
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.psk_replace_title)
                .setMessage(R.string.psk_replace_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.psk_replace_ok, (d, w) -> psk.setText(Crypto.randomPskHex()))
                .show();
    }

    private static boolean bool(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("on");
    }

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

    private Properties values() {
        Properties v = new Properties();
        v.setProperty("discovery", String.valueOf(discovery.isChecked()));
        v.setProperty("direct", String.valueOf(direct.isChecked()));
        v.setProperty("peers", Config.storePeers(peerList.values()));
        v.setProperty("own_addresses", Config.storePeers(ownList.values()));
        v.setProperty("port", text(port));
        v.setProperty("psk", text(psk));
        v.setProperty("psk_rotate", String.valueOf(pskRotate.isChecked()));
        // A key typed or generated here is a NEW key, so its clock starts now. Without this, Apply
        // would write a fresh key over an old activation time — and rotation would pre-retire it
        // within minutes, on the strength of how long the one it replaced had been in use.
        //
        // Only when it changed: pressing Apply after editing a limit must not keep resetting the age
        // of a key that has been in service for a day.
        if (!text(psk).equalsIgnoreCase(Config.raw(this).getProperty("psk", "").trim())) {
            v.setProperty("psk_since", String.valueOf(System.currentTimeMillis()));
            v.setProperty("psk_next", "");
            v.setProperty("psk_retire", "0");
            v.setProperty("psk_agreed", "0");
        }
        v.setProperty("mdns_timeout_ms", String.valueOf(browseValue()));
        v.setProperty("threads", String.valueOf(threadsValue()));
        v.setProperty("max_bytes", kb(text(textKb)));
        v.setProperty("max_file_bytes", mb(text(fileMb)));
        v.setProperty("max_file_bytes_local", mb(text(fileMbLocal)));
        v.setProperty("files_dir", text(path));
        v.setProperty("keep_hours", text(keepHours));
        v.setProperty("keep_max_mb", text(keepMb));
        return v;
    }

    private static String kb(String s) { try { return String.valueOf(Long.parseLong(s.trim()) * 1024); } catch (Exception e) { return s; } }
    private static String mb(String s) { try { return String.valueOf(Long.parseLong(s.trim()) * 1024 * 1024); } catch (Exception e) { return s; } }

    // ------------------------------------------------------------------ live validation
    /** Runs every field check, shows errors, and gates Apply. Returns true when everything is valid. */
    private boolean validate() {
        // Config.from() refuses a config with neither path enabled, so Apply has to refuse it first.
        // This check has no field of its own: with both switches off, both group cards are hidden,
        // which is exactly why it needs its own line rather than an error on one of the fields.
        boolean anyPath = discovery.isChecked() || direct.isChecked();
        pathsError.setVisibility(anyPath ? View.GONE : View.VISIBLE);

        boolean ok = anyPath;
        // The own list first: the peer list is checked against it, so it has to be current.
        boolean ownOk = ownList.validate(Set.of(), null, null);
        ownHasError = !ownOk;
        // An error inside a collapsed group is an error nobody can act on, and Apply is disabled
        // with no visible reason. Opening it is the only honest thing to do. The card's click
        // listener keeps it open from there.
        if (!ownOk) setOwnExpanded(true, true);
        ok &= ownOk;
        ok &= peerList.validate(ownList.normalised(),
                getString(R.string.peer_empty_last, getString(R.string.switch_direct)),
                getString(R.string.peer_empty));
        ok &= show(portL, Config.checkPort(text(port)));
        ok &= show(pskL, Config.checkPsk(text(psk)));
        ok &= show(textKbL, Config.checkRange(text(textKb), 1, 65536, "KB"));
        ok &= show(fileMbL, Config.checkRange(text(fileMb), 1, 4096, "MB"));
        ok &= show(fileMbLocalL, Config.checkRange(text(fileMbLocal), 1, 4096, "MB"));
        ok &= show(pathL, Config.checkPath(text(path)));
        ok &= show(keepHoursL, Config.checkRange(text(keepHours), 0, 8760, "h"));
        ok &= show(keepMbL, Config.checkRange(text(keepMb), 0, 1024 * 1024, "MB"));
        configValid = ok;
        refreshActions();
        return ok;
    }

    private static boolean show(TextInputLayout l, String problem) {
        l.setError(problem);
        l.setErrorEnabled(problem != null);
        return problem == null;
    }

    private final TextWatcher revalidate = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { validate(); }
    };

    // ------------------------------------------------------------------ wiring
    private void wire() {
        for (TextInputEditText e : new TextInputEditText[]{port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb})
            e.addTextChangedListener(revalidate);
        discovery.setOnCheckedChangeListener((b, checked) -> { applySwitches(true); validate(); });
        direct.setOnCheckedChangeListener((b, checked) -> { applySwitches(true); validate(); });
        // Its own listener, not applySwitches(): that one governs whole cards and excludes them from
        // ChangeBounds by name, and this is one paragraph inside a card that has to keep moving.
        pskRotate.setOnCheckedChangeListener((b, checked) -> showRotateHelp(checked, true));
        Haptics.onClick(pskRandom, this::newPsk);
        threads.setLabelFormatter(v -> String.valueOf(Config.THREAD_STEPS[Math.max(0, Math.min(4, Math.round(v)))]));
        Haptics.bind(threads, (s, v, u) -> threadsLabel.setText(getString(R.string.threads_label, threadsValue())));
        browse.setLabelFormatter(v -> Config.BROWSE_STEPS_MS[Math.max(0, Math.min(6, Math.round(v)))] + " ms");
        Haptics.bind(browse, (s, v, u) -> browseLabel.setText(getString(R.string.browse_label, browseValue())));

        Haptics.onClick(applyFab, this::apply);
        Haptics.onClick(stopFab, () -> {
            setAutoStart(false);                       // watchdog / boot must not bring it back
            stopService(new Intent(this, SyncService.class));
            waitingForStop = true;
            waitingSince = System.currentTimeMillis();
            refreshActions();                          // greys Stop out until the service is gone
            snack(R.string.snack_stopped);
        });
        Haptics.onClick(clearFab, () -> { Logger.clear(); showLog(); });
        Haptics.onClick(copyFab, () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync log", log.getText()));
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

        // Scrolling to the end has to wait until the new text has actually been laid out, which is
        // what the layout listener is for. scrollTo and not fullScroll: fullScroll moves focus into
        // the (selectable) TextView, and the scroll container then scrolls that view's *top* into
        // view — the original bug.
        //
        // The scroll target is computed here rather than delegated, because this took three tries and
        // each failure was a different way of trusting someone else's arithmetic:
        //
        //   log.getBottom(), unposted — the callback runs DURING layout, so the range was computed
        //       from dimensions that were not final and the scroll landed short;
        //   Integer.MAX_VALUE — meant as "clamp me to the end". It does not: NestedScrollView's
        //       clamp tests (viewport + n) > childHeight, and with n = MAX_VALUE that addition
        //       OVERFLOWS to a negative number, the test is false, and the value is returned
        //       unclamped. Every line ends up scrolled off the top — the blank screen.
        //
        // So: wait for layout (post), work out the maximum with the same formula the clamp uses but
        // without the overflow, and refuse to act at all until there is a viewport to act on. A page
        // switched from GONE has height 0 while its bottom padding does not, which makes that
        // formula negative — the other way to end up scrolled past the end.
        log.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!logToBottom) return;
            pageLog.post(() -> {
                if (!logToBottom || pageLog.getVisibility() != View.VISIBLE) return;
                int viewport = pageLog.getHeight() - pageLog.getPaddingTop() - pageLog.getPaddingBottom();
                if (viewport <= 0) return;                  // not laid out yet: keep the flag, try again
                pageLog.scrollTo(0, Math.max(0, log.getHeight() - viewport));
                // Cleared only once it actually reached the end. The flag is re-armed by showLog()
                // solely when the view is already at the bottom, so clearing it after a scroll that
                // fell short used to be permanent — every later line pushed the end further away.
                if (!pageLog.canScrollVertically(1)) logToBottom = false;
            });
        });

        // Shrink to the icons while the page scrolls down, extend again on the way up.
        //
        // This cannot be left to ExtendedFloatingActionButtonBehavior, which is checked here
        // because the reason is not the obvious one. That Behavior has no nested-scroll hooks at
        // all — it reacts only in onDependentViewChanged — and shouldUpdateVisibility() returns
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
        // it is never applied to a view that paints a surface — not the CoordinatorLayout, not the
        // app bar, not the log pane — only to the content inside them. BottomNavigationView already
        // does exactly this for itself, which is why its bar spans the screen while its items sit
        // clear of the camera; everything else here now matches it.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            settingsRoot.setPadding(basePadSettings + bars.left, settingsRoot.getPaddingTop(),
                    basePadSettings + bars.right, settingsRoot.getPaddingBottom());
            log.setPadding(basePadLog + bars.left, log.getPaddingTop(),
                    basePadLog + bars.right, log.getPaddingBottom());
            toolbar.setPadding(bars.left, toolbar.getPaddingTop(), bars.right, toolbar.getPaddingBottom());
            // the expanded title is drawn by the CollapsingToolbarLayout itself and never sees the
            // toolbar's padding, so it needs the same offset stated separately — without it the
            // title jumps sideways between its expanded and collapsed positions beside a cutout
            collapsing.setExpandedTitleMarginStart(baseTitleStart + bars.left);
            collapsing.setExpandedTitleMarginEnd(baseTitleEnd + bars.right);
            // setLayoutParams always requests a layout, and this listener also runs whenever the
            // IME opens or closes — so only when the value really changed, or a keyboard appearing
            // mid-animation would drop a stray measure into a shrink or extend
            int margin = baseFabMargin + bars.right;
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

        // The log keeps the app bar collapsed by never driving it: with nested scrolling off it
        // still scrolls its own content, but it cannot push the bar back open. That is all the
        // "always collapsed" rule needs — no scroll flags to swap, no height to juggle.
        pageLog.setNestedScrollingEnabled(false);

        nav.setOnItemSelectedListener(item -> {
            Haptics.tick(nav);
            showPage(item.getItemId() == R.id.nav_log, true);
            return true;
        });

        Haptics.onClick(statusChip, this::showConnectionDetails);
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
     * start, then free to follow the scroll — and reopened on the way back whenever the page is at
     * the top, since a collapsed bar over un-scrolled content reads as broken. The Log wants every
     * pixel it can get, so it arrives collapsed and stays that way (see the nested-scrolling switch
     * in wire()).
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
        if (toLog) { logToBottom = true; showLog(); }
    }

    /** Keeps {@code second} pinned 12dp to the left of {@code first}, whatever width it animates to. */
    private void pairFabs(ExtendedFloatingActionButton first, ExtendedFloatingActionButton second) {
        first.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                second.setTranslationX(-(first.getWidth() + dp(12))));
    }

    private void shrinkOnScroll(NestedScrollView page, ExtendedFloatingActionButton... fabs) {
        page.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (y > oy + dp(4)) for (ExtendedFloatingActionButton f : fabs) f.shrink();
            else if (y < oy - dp(4) || y <= 0) for (ExtendedFloatingActionButton f : fabs) f.extend();
        });
    }

    void snack(int textRes) {
        Snackbar.make(coordinator, textRes, Snackbar.LENGTH_SHORT).setAnchorView(anchor()).show();
    }

    // ------------------------------------------------------------------ pairing (docs/p2p-plan.md §12)
    /**
     * The welcome screen's answer, brought back to the page whose fields the answer rewrites.
     *
     * <p>Registered as a field, which is not decoration: {@code registerForActivityResult} has to be
     * called before the activity is STARTED, so it cannot live inside a click listener however much
     * it belongs there.
     */
    private final androidx.activity.result.ActivityResultLauncher<Intent> welcome =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), r -> {
                // Whatever happened there, the key may have changed — pairing now runs on that
                // screen rather than handing the job back, so this is a refresh and not a dispatch.
                // MANUAL needs nothing done either: the page behind it IS the manual setup.
                reloadAfterPairing();
            });

    /** What the pairing sheets do to this page when they change the key. */
    private final PairSheet.Host pairHost = new PairSheet.Host() {
        @Override public void keyChanged() {
            reloadAfterPairing();
            // The service is started by the sheet; this is only the part of it this page owns —
            // the buttons have to show that something is expected to come up.
            setAutoStart(true);
            waitingForStart = !Status.read(MainActivity.this).alive();
            waitingSince = System.currentTimeMillis();
            refreshActions();
        }
    };

    /**
     * Pairing is mDNS at both ends, so it cannot run with local discovery off — turn it on.
     *
     * <p>Rather than refusing. The switch is a preference about finding peers; pairing is a thing
     * the user has just asked for explicitly, and the only reading of "Pair" with discovery off is
     * that they want both. Said out loud, because a control quietly changing another one is worse
     * than either refusing or asking.
     *
     * <p>Applied to the form, not the file: Apply is what writes, everywhere on this page, and
     * pairing itself does not need the setting saved to work — {@link PairProvider} advertises on
     * its own. What this buys is the state after pairing being the one the user can see.
     *
     * @return true always, so callers read as "if we may, go" — the false case would be a refusal,
     *         and there is no case in which this refuses
     */
    private boolean needsDiscovery() {
        if (!discovery.isChecked()) {
            discovery.setChecked(true);              // its listener reflows the page and validates
            snack(R.string.pair_turned_discovery_on);
        }
        return true;
    }
    /**
     * A new key has been written to the file, so the form is showing the old one.
     *
     * <p>{@link #loadFields} rather than setting the PSK field directly: pairing turns discovery on
     * as well, and re-reading is the only version of this that cannot fall behind whatever else
     * pairing decides to write next.
     */
    void reloadAfterPairing() {
        loadFields();
        validate();
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
     * Stopped → a single "Start"; running → "Stop" and "Apply". After pressing one of them the
     * button greys out until the service actually reaches the new state (or the wait times out),
     * and a state change made from anywhere else moves the buttons just the same. The Log tab's
     * Copy / Clear pair only follows the tab: both stay enabled at all times.
     * Everything here goes through the components' own animations, and nothing is touched unless
     * it actually changed — scrolling drives shrink()/extend() and must not be measured against.
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

        setTextIfChanged(applyFab, getString(serviceRunning ? R.string.action_apply : R.string.action_start));
        // setIconResource() always requests a layout; this runs on the 1 Hz status poll, so an
        // unconditional call would drop a stray measure into whatever shrink/extend is in flight
        int icon = serviceRunning ? R.drawable.ic_restart : R.drawable.ic_play;
        if (icon != applyIcon) { applyIcon = icon; applyFab.setIconResource(icon); }
        applyFab.setEnabled(configValid && !waitingForStart);
    }

    /**
     * Every peer that is up, grouped by how it is reached, and every configured target that is not,
     * with its reason.
     *
     * <p><b>The second group is the point.</b> Three addresses of which one is failing is invisible
     * in {@code Connected (2)}, and finding out which one is precisely why someone opens this.
     *
     * <p>A BottomSheetDialog: supplementary content rather than a decision, and the only surface
     * here that Material gives predictive back to for free — the gesture walks the sheet back down
     * instead of previewing an exit from the app.
     */
    private void showConnectionDetails() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.sheet_status);
        TextView title = sheet.findViewById(R.id.sheet_title);
        ViewGroup list = sheet.findViewById(R.id.sheet_list);
        if (title == null || list == null) return;
        title.setText(R.string.sheet_title);
        sheetList = list;
        // The scene root is the dialog's CoordinatorLayout, one level above the sheet frame — not the
        // sheet's own content, which is where this started and which is not enough.
        //
        // A bottom sheet is anchored to the bottom edge, so when a card goes the frame gets shorter
        // by moving its TOP edge down. Inside that frame nothing moves: the title is still at y=0 of
        // its parent, and ChangeBounds on the content therefore has nothing to animate for it. The
        // view whose bounds actually change is the frame, and to capture that the scene root has to
        // be its parent. With the coordinator as the root, one transition carries the whole thing —
        // the sheet's top edge, and the cards reflowing inside it, on the same clock.
        //
        // Falls back to the content root if the id ever moves: a sheet that animates its cards and
        // snaps its frame is worse than one that animates both, and better than one that crashes.
        sheetRoot = sheet.findViewById(com.google.android.material.R.id.coordinator);
        if (sheetRoot == null) sheetRoot = sheet.findViewById(R.id.sheet_root);
        // Guarded on identity: a listener fires after its dialog is gone, and one that cleared the
        // fields unconditionally would tear down a *newer* sheet that had already claimed them.
        sheet.setOnDismissListener(d -> {
            if (sheetList == list) { sheetList = null; sheetRoot = null; }
        });
        renderSheet(Status.read(this), false);
        sheet.show();
    }

    /**
     * The open sheet, or null. Held so the once-a-second tick can keep it current.
     *
     * <p>It used to be built once and left to go stale, which is exactly wrong for the thing it
     * exists to show: someone opens it *because* a peer is missing, and then watches for it to come
     * back. A sheet that cannot change is a sheet you have to close and reopen to use.
     */
    private ViewGroup sheetList;
    /** The transition's scene root, which is why it is a ViewGroup and not a View. */
    private ViewGroup sheetRoot;

    /** One entry the sheet shows: a group heading, a connected peer, or a target that is not. */
    private static final class Row {
        final String key;                 // identity across a refresh, not a label
        final int header;                 // a string resource, or 0
        final Status.Peer peer;
        final Status.Target target;

        Row(String key, int header, Status.Peer peer, Status.Target target) {
            this.key = key; this.header = header; this.peer = peer; this.target = target;
        }
    }

    /**
     * What the sheet should contain, in order, for this snapshot.
     *
     * <p>Keys are the point of this list. A heading is keyed by its own string, a peer by its node
     * id, a target by its name — so a refresh can tell "this card is still the same device" from
     * "a different device now occupies that position", which is the difference between reflowing a
     * list and rebuilding it under the reader's eyes.
     */
    private List<Row> rowsFor(Status.Snapshot s) {
        List<Row> rows = new ArrayList<>();
        List<Status.Peer> lan = new ArrayList<>(), wan = new ArrayList<>();
        for (Status.Peer p : s.peers) (p.lan ? lan : wan).add(p);
        group(rows, R.string.sheet_on_lan, lan);
        group(rows, R.string.sheet_over_internet, wan);
        if (!s.targets.isEmpty()) {
            rows.add(new Row("h:down", R.string.sheet_not_connected, null, null));
            for (Status.Target t : s.targets) rows.add(new Row("t:" + t.target, 0, null, t));
        }
        if (rows.isEmpty()) rows.add(new Row("none", 0, null, null));
        return rows;
    }

    private void group(List<Row> rows, int headerRes, List<Status.Peer> peers) {
        if (peers.isEmpty()) return;      // no members, no heading: see renderSheet
        rows.add(new Row("h:" + headerRes, headerRes, null, null));
        // The id, not the name or the address: a peer that moves from Wi-Fi to cellular keeps its
        // card and slides between the two sections instead of vanishing from one and appearing in
        // the other as a different device.
        for (Status.Peer p : peers) rows.add(new Row("p:" + p.id, 0, p, null));
    }

    /**
     * Bring the sheet to this snapshot, animating what changed.
     *
     * <p>A diff and not a rebuild. Rebuilding once a second would cross-fade every card on the screen
     * whether or not anything about it moved, and would throw away the scroll position while it was
     * at it. So views are matched to rows by key: the ones whose key is gone fade out, the ones whose
     * key is new fade in, and every survivor is re-bound in place and slides to wherever the others
     * left it.
     *
     * <p>A section heading is an ordinary keyed row with no members of its own, which is what makes
     * "the heading leaves with its last card" fall out rather than need arranging: {@link #group}
     * emits no heading for an empty section, so the heading's key disappears in the same pass as the
     * card's and the two fade together.
     *
     * <p>The new views are created <b>before</b> the transition begins and while they are still
     * detached, because {@link #visibilityMotion} needs to name the views that fade — and a view that
     * is not in the start scene is one that appears. The ones that are leaving are named from the
     * container as it stands.
     */
    private void renderSheet(Status.Snapshot s, boolean animate) {
        ViewGroup list = sheetList;
        if (list == null) return;
        List<Row> rows = rowsFor(s);

        List<View> fading = new ArrayList<>();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Row r : rows) wanted.add(r.key);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!wanted.contains(String.valueOf(child.getTag()))) fading.add(child);
        }
        // Built here, still unattached: created after beginDelayedTransition they would be part of
        // neither scene, and named as fade targets they are exactly what MaterialFade animates in.
        java.util.Map<String, View> fresh = new java.util.LinkedHashMap<>();
        for (Row r : rows) {
            if (childWithKey(list, r.key) != null) continue;
            View v = r.header != 0 ? makeHeader(list, r) : makeCard(list, r);
            fresh.put(r.key, v);
            fading.add(v);
        }
        if (animate && !fading.isEmpty() && sheetRoot != null) {
            TransitionManager.beginDelayedTransition(sheetRoot, visibilityMotion(fading.toArray(new View[0])));
        }

        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            if (!wanted.contains(String.valueOf(list.getChildAt(i).getTag()))) list.removeViewAt(i);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            View v = childWithKey(list, r.key);
            if (v == null) {
                list.addView(fresh.get(r.key), i);
            } else if (list.indexOfChild(v) != i) {
                // Detached and reinserted, not faded: it is the same device in a new place, so the
                // transition sees a bounds change and slides it there.
                list.removeView(v);
                list.addView(v, i);
            }
            if (r.header == 0) bindCard(list.getChildAt(i), r);
        }
    }

    private static View childWithKey(ViewGroup list, String key) {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (key.equals(list.getChildAt(i).getTag())) return list.getChildAt(i);
        }
        return null;
    }

    private View makeHeader(ViewGroup list, Row r) {
        TextView h = (TextView) getLayoutInflater().inflate(R.layout.item_status_header, list, false);
        h.setTag(r.key);
        h.setText(r.header);
        // A heading, and said so: TalkBack can then jump between the groups instead of reading every
        // peer to find where the next one starts, which in a list like this is the whole navigation.
        h.setAccessibilityHeading(true);
        return h;
    }

    /**
     * An empty card with the fields its kind needs. The values arrive in {@link #bindCard}, which is
     * what lets a refresh change them without replacing the card.
     */
    private View makeCard(ViewGroup list, Row r) {
        View card = getLayoutInflater().inflate(R.layout.item_status_card, list, false);
        card.setTag(r.key);
        if (r.peer != null) {
            // Monospace for the two that are machine-readable strings: hex digits and dotted quads
            // are read character by character, and a proportional font makes 1/l and 0/O work for it.
            field(card, R.string.field_id).setTypeface(android.graphics.Typeface.MONOSPACE);
            field(card, R.string.field_type);
            field(card, R.string.field_address).setTypeface(android.graphics.Typeface.MONOSPACE);
        } else if (r.target != null) {
            field(card, R.string.field_reason);
        }
        return card;
    }

    private void bindCard(View card, Row r) {
        if (r.peer != null) {
            Status.Peer p = r.peer;
            String name = p.name == null || p.name.isEmpty() ? "?" : p.name;
            String addr = p.addr == null ? "?" : p.addr;
            setTextIfChanged(card.findViewById(R.id.card_name), name);
            // A connected peer's own facts are never a verdict on anything, so they take the plain
            // role — the grading in `value` is for the reasons a target is *not* connected.
            value(card, 0, Node.shortId(p.id), Status.Why.WAITING);
            value(card, 1, p.type == null ? "?" : p.type, Status.Why.WAITING);
            value(card, 2, addr, Status.Why.WAITING);
            // The copied text is unchanged: name, then the FULL id, then the address, one per line.
            // The card shows the id's first 8 characters because 36 are unreadable at a glance, and
            // copying is how you get the rest — so the two must not be the same string.
            clickToCopy(card, name + "\n" + (p.id == null ? "" : p.id) + "\n" + addr);
        } else if (r.target != null) {
            setTextIfChanged(card.findViewById(R.id.card_name), r.target.target);
            value(card, 0, r.target.reason, r.target.why);
            clickToCopy(card, r.target.target);
        } else {
            setTextIfChanged(card.findViewById(R.id.card_name), getString(R.string.sheet_none));
            // Not just unclickable: a card with clickable=true carries a ripple and takes focus, so
            // leaving those on gives a placeholder the feedback of a control that does nothing. The
            // card keeps its surface; only the affordance goes.
            card.setClickable(false);
            card.setFocusable(false);
        }
    }

    /**
     * Copy this text when the card is tapped, re-binding only when the text actually changed.
     *
     * <p>{@code bindCard} runs once a second per card for as long as the sheet is open, and a fresh
     * listener each time is a fresh lambda holding a fresh string — garbage produced by a sheet that
     * is simply sitting there. The text doubles as the memo of what is already bound; the tag key is
     * an id from this layout, which is the usual way to keep a view's own bookkeeping on the view.
     */
    private void clickToCopy(View card, String text) {
        if (text.equals(card.getTag(R.id.card_name))) return;
        card.setTag(R.id.card_name, text);
        Haptics.onClick(card, () -> copy(text));
    }

    /** One labelled field, value still empty. Position in the card is the caller's order. */
    private TextView field(View card, int labelRes) {
        ViewGroup fields = card.findViewById(R.id.card_fields);
        // Revealed on the first field rather than always visible: its 8dp top margin would otherwise
        // hang off the bottom of a card that has no fields at all ("No peers connected").
        fields.setVisibility(View.VISIBLE);
        View row = getLayoutInflater().inflate(R.layout.item_status_field, fields, false);
        ((TextView) row.findViewById(R.id.field_label)).setText(labelRes);
        fields.addView(row);
        return row.findViewById(R.id.field_value);
    }

    /**
     * Set one field's value by position, in the colour its kind of reason calls for.
     *
     * <p>Four roles, loudest first, and each one answers "so what do I do?" differently — a colour
     * that does not change what the reader does next is decoration:
     *
     * <ul>
     *   <li>{@code FAULT} → <b>colorError</b>. Something to fix.
     *   <li>{@code ASLEEP} → <b>colorTertiary</b>. The peer said so itself, which makes this the one
     *       row carrying positive knowledge rather than the absence of it. Tertiary is already this
     *       app's colour for the chip's *Connecting…* — a state the system is passing through on
     *       purpose — so the vocabulary is the same in both places.
     *   <li>{@code WAITING} → <b>colorOnSurface</b>. The plain default: wait.
     *   <li>{@code NOTED} → <b>colorOnSurfaceVariant</b>. A footnote about the setup, quietest,
     *       because it explains why a row exists and asks for nothing.
     * </ul>
     *
     * <p>Applied on every call rather than only when it changes kind: a target can stop being a
     * fault, and a colour left behind outlives the condition that justified it.
     */
    private void value(View card, int index, String text, Status.Why why) {
        ViewGroup fields = card.findViewById(R.id.card_fields);
        if (index >= fields.getChildCount()) return;
        TextView v = fields.getChildAt(index).findViewById(R.id.field_value);
        setTextIfChanged(v, text == null || text.isEmpty() ? "?" : text);
        // Compared before it is set, like every other setter on the once-a-second path: setTextColor
        // invalidates whether or not the colour differs.
        int want = MaterialColors.getColor(v, switch (why) {
            case FAULT -> androidx.appcompat.R.attr.colorError;
            case ASLEEP -> com.google.android.material.R.attr.colorTertiary;
            case NOTED -> com.google.android.material.R.attr.colorOnSurfaceVariant;
            case WAITING -> com.google.android.material.R.attr.colorOnSurface;
        });
        if (v.getCurrentTextColor() != want) v.setTextColor(want);
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync", text));
        snack(R.string.snack_copied);
    }

    private static void setTextIfChanged(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_LOG_TAB, onLogTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        logToBottom = true;
        showLog();
        Logger.addListener(logListener);
        wasConnected = false;
        ui.post(tick);
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
        Logger.removeListener(logListener);
        ui.removeCallbacks(tick);
        ui.removeCallbacks(statusPoll);
        ui.removeCallbacks(statusChanged);
        if (statusWatch != null) {
            statusWatch.stopWatching();
            statusWatch = null;
        }
        pulse.cancel();
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
        boolean connected = "connected".equals(s.state);
        boolean busy = "connecting".equals(s.state);
        boolean stopped = "stopped".equals(s.state);

        // this runs once a second: every setter here either invalidates or requests a layout, so
        // none of them is called unless the value actually changed
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
        String titleText = connected
                ? getString(R.string.state_connected, s.count())
                : getString(switch (s.state) {
                    case "connecting" -> R.string.state_connecting;
                    case "no network" -> R.string.state_no_network;
                    case "idle" -> R.string.state_idle;
                    default -> R.string.state_stopped;
                });
        // this runs once a second: only touch the TextView when the text really changed, or every
        // tick would queue a layout pass for the status chip
        setTextIfChanged(statusChip, titleText);
        // Tappable whenever there is anything to list — which now includes "nothing is connected and
        // here is why", the case the sheet is most worth opening for.
        statusChip.setClickable(!s.peers.isEmpty() || !s.targets.isEmpty());
        // The same snapshot the chip was just built from, rather than a second read: two reads a
        // second of a file another process rewrites can disagree, and the chip saying Connected (2)
        // above a sheet listing one peer is the kind of contradiction nobody can explain.
        renderSheet(s, true);

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
            // setTextIfChanged, like every other setter on this once-a-second path: setText always
            // requests a layout, so an unconditional call here queues one every single tick.
            setTextIfChanged(batteryText, getString(exempt ? R.string.battery_still_frozen : R.string.battery_on));
            batteryFix.setVisibility(exempt ? View.GONE : View.VISIBLE);
        }
    }


    // ------------------------------------------------------------------ apply
    private static long longOf(Properties p, String key, long dflt) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(dflt)).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private void apply() {
        if (!validate()) return;
        try {
            Config c = Config.save(this, values());
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
                    + ", " + c.threads + " streams, files -> " + c.filesDir);
            snack(R.string.snack_applied);
        } catch (IllegalArgumentException e) {
            // should not happen (live validation), but map it back to a field anyway
            String msg = e.getMessage() == null ? "invalid value" : e.getMessage();
            String key = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "";
            TextInputLayout target = switch (key) {
                case "peers", "discovery" -> peerList.firstRow() instanceof TextInputLayout t ? t : portL;
                case "own_addresses" -> ownList.firstRow() instanceof TextInputLayout t ? t : portL;
                case "port" -> portL; case "psk" -> pskL;
                case "max_bytes" -> textKbL; case "max_file_bytes" -> fileMbL; case "max_file_bytes_local" -> fileMbLocalL;
                case "files_dir" -> pathL; case "keep_hours" -> keepHoursL; case "keep_max_mb" -> keepMbL;
                default -> portL;
            };
            target.setError(msg.substring(key.length() + 1).trim());
            target.requestFocus();
        } catch (Exception e) {
            Snackbar.make(coordinator, getString(R.string.snack_save_failed, e.toString()), Snackbar.LENGTH_LONG)
                    .setAnchorView(anchor()).show();
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
