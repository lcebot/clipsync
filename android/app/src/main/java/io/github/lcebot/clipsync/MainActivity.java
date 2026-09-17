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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Properties;

/**
 * Two pages in a ViewPager2 — Settings and Log — reachable by swipe or by the bottom navigation
 * bar, under an M3 collapsing top app bar. The connection status is the bar's only menu action, a
 * Chip whose icon is the pulsing dot; tapping it opens the peer and address in full. The actions
 * are extended FABs bottom-right, a different pair per page: Settings gets "Start", or "Stop" plus
 * "Apply" once the service runs, and Log gets "Copy" and "Clear".
 * Every field is validated live; Apply is enabled only when all of them are valid and sends a
 * RELOAD to the running service (no restart).
 */
public class MainActivity extends AppCompatActivity {
    // fields
    private MaterialButtonToggleGroup mode;
    private TextInputLayout hostL, portL, pskL, textKbL, fileMbL, fileMbLocalL, pathL, keepHoursL, keepMbL;
    private TextInputEditText host, port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb;
    private View browseRow;
    private Slider browse, threads;
    private TextView browseLabel, threadsLabel;
    private ViewGroup settingsRoot;
    // app bar / actions
    private AppBarLayout appbar;
    private View coordinator;
    private BottomNavigationView nav;
    private ExtendedFloatingActionButton stopFab, applyFab;      // Settings tab
    private ExtendedFloatingActionButton copyFab, clearFab;      // Log tab
    // pages / log
    private ViewPager2 pager;
    private View settingsPage, batteryRow, batteryFix;
    private TextView batteryText;
    private RecyclerView logList;
    private final LogAdapter logAdapter = new LogAdapter();
    // status
    private Chip statusChip;
    private String peerName, peerAddr, connectionKind;      // shown in the details dialog
    private boolean wasConnected;
    private ValueAnimator pulse;
    // action state: which of Start / Stop + Apply is shown, and what we are waiting for
    private boolean serviceRunning, waitingForStop, waitingForStart;
    private boolean onLogTab, configValid;
    // mirrors of the FABs' own shown/hidden state, so we only drive show()/hide() on a real change
    private boolean stopShown, applyShown = true, logFabsShown;
    private int applyIcon;                                  // 0 = never set, so the first pass applies
    private long waitingSince;
    private static final long WAIT_TIMEOUT_MS = 12_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    // lines written in this process arrive here; lines written by the :sync process arrive through
    // the file, picked up by the poll below
    private final Logger.Listener logListener = line -> ui.post(this::showLog);
    // the service runs in its own process: its log and state reach us through files, polled 1/s
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Logger.refresh()) showLog();
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * Replaces the visible log. Following the tail is only automatic while the list is already at
     * the bottom, so reading further up is not yanked away by the next line.
     */
    private void showLog() {
        boolean atBottom = !logList.canScrollVertically(1);
        logAdapter.submit(Logger.snapshot());
        if (atBottom && logAdapter.getItemCount() > 0) {
            logList.scrollToPosition(logAdapter.getItemCount() - 1);
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
    }

    private void bind() {
        // The pages are inflated here rather than by the pager's adapter, so that everything on
        // them can be found in this one pass and held for the activity's whole life.
        settingsPage = getLayoutInflater().inflate(R.layout.page_settings, null);
        logList = (RecyclerView) getLayoutInflater().inflate(R.layout.page_log, null);

        mode = settingsPage.findViewById(R.id.mode);
        hostL = settingsPage.findViewById(R.id.host_layout);       host = settingsPage.findViewById(R.id.host);
        portL = settingsPage.findViewById(R.id.port_layout);       port = settingsPage.findViewById(R.id.port);
        pskL = settingsPage.findViewById(R.id.psk_layout);         psk = settingsPage.findViewById(R.id.psk);
        textKbL = settingsPage.findViewById(R.id.text_kb_layout);  textKb = settingsPage.findViewById(R.id.text_kb);
        fileMbL = settingsPage.findViewById(R.id.file_mb_layout);  fileMb = settingsPage.findViewById(R.id.file_mb);
        fileMbLocalL = settingsPage.findViewById(R.id.file_mb_local_layout);
        fileMbLocal = settingsPage.findViewById(R.id.file_mb_local);
        pathL = settingsPage.findViewById(R.id.path_layout);       path = settingsPage.findViewById(R.id.path);
        keepHoursL = settingsPage.findViewById(R.id.keep_hours_layout);
        keepHours = settingsPage.findViewById(R.id.keep_hours);
        keepMbL = settingsPage.findViewById(R.id.keep_mb_layout);  keepMb = settingsPage.findViewById(R.id.keep_mb);
        browseRow = settingsPage.findViewById(R.id.browse_row);
        browse = settingsPage.findViewById(R.id.browse);
        browseLabel = settingsPage.findViewById(R.id.browse_label);
        threads = settingsPage.findViewById(R.id.threads);
        threadsLabel = settingsPage.findViewById(R.id.threads_label);
        settingsRoot = settingsPage.findViewById(R.id.settings_root);
        batteryRow = settingsPage.findViewById(R.id.battery_row);
        batteryText = settingsPage.findViewById(R.id.battery_text);
        batteryFix = settingsPage.findViewById(R.id.battery_fix);

        appbar = findViewById(R.id.appbar);
        coordinator = findViewById(R.id.coordinator);
        nav = findViewById(R.id.nav);
        pager = findViewById(R.id.pager);
        stopFab = findViewById(R.id.stop);
        applyFab = findViewById(R.id.apply);
        copyFab = findViewById(R.id.log_copy);
        clearFab = findViewById(R.id.log_clear);

        // the chip is the toolbar's single menu action; app:menu inflates it with the layout
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        statusChip = (Chip) toolbar.getMenu().findItem(R.id.action_status).getActionView();
    }

    // ------------------------------------------------------------------ values <-> fields
    private void loadFields() {
        Properties p = Config.raw(this);
        String m = p.getProperty("mode", Config.MODE_BOTH).trim();
        mode.check(Config.MODE_DDNS.equals(m) ? R.id.mode_ddns : Config.MODE_MDNS.equals(m) ? R.id.mode_mdns : R.id.mode_both);
        host.setText(p.getProperty("host", ""));
        port.setText(p.getProperty("port", "47521"));
        psk.setText(p.getProperty("psk", ""));
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
        applyMode(false);
    }

    private String modeValue() {
        int id = mode.getCheckedButtonId();
        return id == R.id.mode_ddns ? Config.MODE_DDNS : id == R.id.mode_mdns ? Config.MODE_MDNS : Config.MODE_BOTH;
    }

    private boolean modeHasDdns() { return !Config.MODE_MDNS.equals(modeValue()); }
    private boolean modeHasMdns() { return !Config.MODE_DDNS.equals(modeValue()); }

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

    /** Show / hide the DDNS name and the browse slider according to the segmented choice. */
    private void applyMode(boolean animate) {
        if (animate) TransitionManager.beginDelayedTransition(settingsRoot, new AutoTransition().setDuration(220));
        hostL.setVisibility(modeHasDdns() ? View.VISIBLE : View.GONE);
        browseRow.setVisibility(modeHasMdns() ? View.VISIBLE : View.GONE);
    }

    private Properties values() {
        Properties v = new Properties();
        v.setProperty("mode", modeValue());
        v.setProperty("host", text(host));
        v.setProperty("port", text(port));
        v.setProperty("psk", text(psk));
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
        boolean ok = true;
        ok &= show(hostL, modeHasDdns() ? Config.checkHost(text(host)) : null);
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
        for (TextInputEditText e : new TextInputEditText[]{host, port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb})
            e.addTextChangedListener(revalidate);
        mode.addOnButtonCheckedListener((g, id, checked) -> { if (checked) { applyMode(true); validate(); } });
        threads.setLabelFormatter(v -> String.valueOf(Config.THREAD_STEPS[Math.max(0, Math.min(4, Math.round(v)))]));
        threads.addOnChangeListener((s, v, u) -> threadsLabel.setText(getString(R.string.threads_label, threadsValue())));
        browse.setLabelFormatter(v -> Config.BROWSE_STEPS_MS[Math.max(0, Math.min(6, Math.round(v)))] + " ms");
        browse.addOnChangeListener((s, v, u) -> browseLabel.setText(getString(R.string.browse_label, browseValue())));

        applyFab.setOnClickListener(v -> apply());
        stopFab.setOnClickListener(v -> {
            setAutoStart(false);                       // watchdog / boot must not bring it back
            stopService(new Intent(this, SyncService.class));
            waitingForStop = true;
            waitingSince = System.currentTimeMillis();
            refreshActions();                          // greys Stop out until the service is gone
            snack(R.string.snack_stopped);
        });
        clearFab.setOnClickListener(v -> { Logger.clear(); showLog(); });
        copyFab.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync log", logAdapter.text()));
            snack(R.string.snack_log_copied);
        });
        // The secondary FAB rides to the left of the primary one; following the primary's animated
        // width by translation keeps the pairing out of the layout pass entirely
        pairFabs(applyFab, stopFab);
        pairFabs(copyFab, clearFab);
        batteryFix.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        });

        logList.setAdapter(logAdapter);
        pager.setAdapter(new PageAdapter(settingsPage, logList));
        // A horizontal drag that starts on a slider belongs to the slider, not to the pager.
        View.OnTouchListener sliderOwnsTheGesture = (v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;                                   // the slider still handles it
        };
        threads.setOnTouchListener(sliderOwnsTheGesture);
        browse.setOnTouchListener(sliderOwnsTheGesture);

        // the bottom bar and the swipe are two ways to move the same pager
        nav.setOnItemSelectedListener(item -> {
            pager.setCurrentItem(item.getItemId() == R.id.nav_log ? 1 : 0, true);
            return true;
        });
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                onLogTab = position == 1;
                nav.getMenu().findItem(onLogTab ? R.id.nav_log : R.id.nav_settings).setChecked(true);
                appbar.setLiftOnScrollTargetViewId(onLogTab ? R.id.page_log : R.id.page_settings);
                refreshActions();
            }
        });

        statusChip.setOnClickListener(v -> showConnectionDetails());
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

    /** Keeps {@code second} pinned 12dp to the left of {@code first}, whatever width it animates to. */
    private void pairFabs(ExtendedFloatingActionButton first, ExtendedFloatingActionButton second) {
        first.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                second.setTranslationX(-(first.getWidth() + dp(12))));
    }

    private void snack(int textRes) {
        Snackbar.make(coordinator, textRes, Snackbar.LENGTH_SHORT).setAnchorView(anchor()).show();
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

    /** Peer name and address in full, wrapped, each copied by tapping it. */
    private void showConnectionDetails() {
        if (peerName == null && peerAddr == null) return;              // nothing to show when stopped
        View body = getLayoutInflater().inflate(R.layout.dialog_status, null);
        TextView peer = body.findViewById(R.id.dialog_peer), addr = body.findViewById(R.id.dialog_address);
        peer.setText(peerName == null ? "—" : peerName);
        addr.setText(peerAddr == null ? "—" : peerAddr);
        peer.setOnClickListener(v -> copy(peerName));
        addr.setOnClickListener(v -> copy(peerAddr));
        new MaterialAlertDialogBuilder(this)
                .setTitle(connectionKind == null ? getString(R.string.state_stopped) : connectionKind)
                .setView(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
    protected void onResume() {
        super.onResume();
        logAdapter.submit(Logger.snapshot());
        if (logAdapter.getItemCount() > 0) logList.scrollToPosition(logAdapter.getItemCount() - 1);
        Logger.addListener(logListener);
        wasConnected = false;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.removeListener(logListener);
        ui.removeCallbacks(tick);
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
            s = new Status.Snapshot("stopped", getString(autoStartEnabled() ? R.string.state_not_running : R.string.state_autostart_off),
                    null, false, null, null, 0, s.suspended);
        }
        boolean connected = "connected".equals(s.state);
        boolean busy = "connecting".equals(s.state);
        boolean stopped = "stopped".equals(s.state);

        int dot = MaterialColors.getColor(statusChip, connected ? androidx.appcompat.R.attr.colorPrimary
                : busy ? com.google.android.material.R.attr.colorTertiary : com.google.android.material.R.attr.colorOutline);
        statusChip.setChipIconTint(ColorStateList.valueOf(dot));
        statusChip.setTextColor(MaterialColors.getColor(statusChip, stopped
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));

        // the chip carries the connection kind (or the state) only; peer and address go to the dialog
        String titleText;
        if (connected) {
            connectionKind = "mdns".equals(s.via) ? getString(R.string.kind_mdns)
                    : getString(R.string.kind_ddns, getString(s.lan ? R.string.link_lan : R.string.link_internet));
            titleText = connectionKind;
            peerName = s.host;
            peerAddr = s.addr;
        } else {
            titleText = getString(switch (s.state) {
                case "connecting" -> R.string.state_connecting;
                case "disconnected" -> R.string.state_disconnected;
                case "no network" -> R.string.state_no_network;
                case "idle" -> R.string.state_idle;
                default -> R.string.state_stopped;
            });
            connectionKind = null;
            peerName = null;
            peerAddr = s.detail;                       // e.g. "retry in 5 s" — still worth showing
        }
        // this runs once a second: only touch the TextView when the text really changed, or every
        // tick would queue a layout pass for the status chip
        setTextIfChanged(statusChip, titleText);
        statusChip.setClickable(peerName != null || peerAddr != null);

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
            batteryText.setText(exempt ? R.string.battery_still_frozen : R.string.battery_on);
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
            Logger.i("config applied: mode " + c.mode + (c.host.isEmpty() ? "" : ", " + c.host) + ":" + c.port
                    + (c.mdns ? ", browse " + c.mdnsTimeoutMs + " ms" : "") + ", " + c.threads + " streams, files -> " + c.filesDir);
            snack(R.string.snack_applied);
        } catch (IllegalArgumentException e) {
            // should not happen (live validation), but map it back to a field anyway
            String msg = e.getMessage() == null ? "invalid value" : e.getMessage();
            String key = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "";
            TextInputLayout target = switch (key) {
                case "host" -> hostL; case "port" -> portL; case "psk" -> pskL;
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
