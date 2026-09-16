package io.github.lcebot.clipsync;

import android.animation.ObjectAnimator;
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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Properties;

/**
 * Two pages behind a bottom navigation bar — Settings and Log — under an M3 collapsing top app bar.
 * The connection status lives in the bar's top-right corner (pulsing dot, connection kind, peer and
 * address; the lower two lines fade out as the bar collapses, tapping it returns to the top), the
 * two actions are extended FABs bottom-right that shrink on scroll and only exist on Settings.
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
    private View coordinator, fabs;
    private ExtendedFloatingActionButton stopFab, applyFab;
    // pages / log
    private NestedScrollView pageSettings;
    private View pageLog, batteryRow, batteryFix;
    private TextView log, batteryText;
    private NestedScrollView logScroll;
    // status
    private ViewGroup statusBlock;
    private View statusDot, statusHalo;
    private TextView statusTitle, statusHost, statusDetail;
    private boolean hasHost, hasDetail, wasConnected;
    private ObjectAnimator pulse;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Logger.Listener logListener = line -> ui.post(() -> {
        log.append("\n" + line);
        if (pageLog.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
    });
    // the service runs in its own process: its log and state reach us through files, polled 1/s
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Logger.refresh()) {
                log.setText(TextUtils.join("\n", Logger.snapshot()));
                if (pageLog.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
            }
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

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
        mode = findViewById(R.id.mode);
        hostL = findViewById(R.id.host_layout);       host = findViewById(R.id.host);
        portL = findViewById(R.id.port_layout);       port = findViewById(R.id.port);
        pskL = findViewById(R.id.psk_layout);         psk = findViewById(R.id.psk);
        textKbL = findViewById(R.id.text_kb_layout);  textKb = findViewById(R.id.text_kb);
        fileMbL = findViewById(R.id.file_mb_layout);  fileMb = findViewById(R.id.file_mb);
        fileMbLocalL = findViewById(R.id.file_mb_local_layout); fileMbLocal = findViewById(R.id.file_mb_local);
        pathL = findViewById(R.id.path_layout);       path = findViewById(R.id.path);
        keepHoursL = findViewById(R.id.keep_hours_layout); keepHours = findViewById(R.id.keep_hours);
        keepMbL = findViewById(R.id.keep_mb_layout);  keepMb = findViewById(R.id.keep_mb);
        browseRow = findViewById(R.id.browse_row);
        browse = findViewById(R.id.browse);
        browseLabel = findViewById(R.id.browse_label);
        threads = findViewById(R.id.threads);
        threadsLabel = findViewById(R.id.threads_label);
        settingsRoot = findViewById(R.id.settings_root);
        appbar = findViewById(R.id.appbar);
        coordinator = findViewById(R.id.coordinator);
        fabs = findViewById(R.id.fabs);
        stopFab = findViewById(R.id.stop);
        applyFab = findViewById(R.id.apply);
        pageSettings = findViewById(R.id.page_settings);
        pageLog = findViewById(R.id.page_log);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        batteryRow = findViewById(R.id.battery_row);
        batteryText = findViewById(R.id.battery_text);
        batteryFix = findViewById(R.id.battery_fix);
        statusBlock = findViewById(R.id.status_block);
        statusTitle = findViewById(R.id.status_title);
        statusHost = findViewById(R.id.status_host);
        statusDetail = findViewById(R.id.status_detail);
        statusDot = findViewById(R.id.status_dot);
        statusHalo = findViewById(R.id.status_halo);
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
        applyFab.setEnabled(ok);
        applyFab.setAlpha(ok ? 1f : 0.5f);
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
            snack(R.string.snack_stopped);
            ui.postDelayed(this::refreshStatus, 300);
        });
        // the FABs shrink to their icons while the settings page scrolls down, and extend again
        // on the way back up — ExtendedFloatingActionButton's own animation
        pageSettings.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (y > oy + dp(4)) { stopFab.shrink(); applyFab.shrink(); }
            else if (y < oy - dp(4) || y <= 0) { stopFab.extend(); applyFab.extend(); }
        });
        findViewById(R.id.log_clear).setOnClickListener(v -> { Logger.clear(); log.setText(""); });
        findViewById(R.id.log_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync log", log.getText()));
            snack(R.string.snack_log_copied);
        });
        batteryFix.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        });

        BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            boolean showLog = item.getItemId() == R.id.nav_log;
            pageSettings.setVisibility(showLog ? View.GONE : View.VISIBLE);
            pageLog.setVisibility(showLog ? View.VISIBLE : View.GONE);
            appbar.setLiftOnScrollTargetViewId(showLog ? R.id.log_scroll : R.id.page_settings);   // lift follows the visible page
            if (showLog) { stopFab.hide(); applyFab.hide(); } else { stopFab.show(); applyFab.show(); }
            if (showLog) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
            return true;
        });
        // tapping the status: back to the top, bar re-expanded
        statusBlock.setOnClickListener(v -> {
            appbar.setExpanded(true, true);
            if (pageLog.getVisibility() == View.VISIBLE) logScroll.smoothScrollTo(0, 0);
            else pageSettings.smoothScrollTo(0, 0);
        });

        // the CollapsingToolbarLayout scales the title itself; we only fade / place the status
        appbar.addOnOffsetChangedListener((bar, offset) -> {
            int range = Math.max(1, bar.getTotalScrollRange());
            positionStatus(Math.min(1f, -offset / (float) range));
        });
        // the first offset callback arrives before anything is measured, and hiding a line
        // re-measures the block: re-place it afterwards (translation only, so this cannot loop)
        statusBlock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeStatus());

        pulse = ObjectAnimator.ofFloat(statusHalo, View.ALPHA, 0.45f, 0f);
        pulse.setDuration(1400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.RESTART);
    }

    private void snack(int textRes) {
        Snackbar.make(coordinator, textRes, Snackbar.LENGTH_SHORT).setAnchorView(fabs).show();
    }

    private float lastF;

    /**
     * Expanded: all three lines. Collapsing: peer and address fade out over the first half, so by
     * the time the bar is closed only the connection kind (or "Stopped") is left beside the dot.
     */
    private void positionStatus(float f) {
        lastF = f;
        float fade = clamp01(1f - 2f * f);
        statusHost.setAlpha(fade);
        statusDetail.setAlpha(fade);
        boolean show = fade > 0f;
        statusHost.setVisibility(hasHost && show ? View.VISIBLE : View.GONE);
        statusDetail.setVisibility(hasDetail && show ? View.VISIBLE : View.GONE);
        placeStatus();
    }

    /**
     * The block keeps its centre on the collapsed bar's centre line in every state, so it grows and
     * shrinks in place in the top-right corner instead of drifting. Translation only: no layout,
     * nothing that could disturb the app bar.
     */
    private void placeStatus() {
        int h = statusBlock.getHeight(), bar = appbar.getHeight();
        if (h == 0 || bar == 0) return;                                // not laid out yet
        int collapsed = bar - appbar.getTotalScrollRange();
        statusBlock.setTranslationY((collapsed - h) / 2f);
    }

    private static void setTextIfChanged(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        log.setText(TextUtils.join("\n", Logger.snapshot()));
        logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
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

        int dot = MaterialColors.getColor(statusDot, connected ? androidx.appcompat.R.attr.colorPrimary
                : busy ? com.google.android.material.R.attr.colorTertiary : com.google.android.material.R.attr.colorOutline);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(dot));
        statusHalo.setBackgroundTintList(ColorStateList.valueOf(dot));
        statusTitle.setTextColor(MaterialColors.getColor(statusTitle, stopped
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));

        // line 1: kind / state · line 2: peer name · line 3: address (or state detail)
        String titleText, hostText, detailText;
        if (connected) {
            titleText = "mdns".equals(s.via) ? getString(R.string.kind_mdns)
                    : getString(R.string.kind_ddns, getString(s.lan ? R.string.link_lan : R.string.link_internet));
            hostText = s.host;
            detailText = s.addr;
        } else {
            titleText = getString(switch (s.state) {
                case "connecting" -> R.string.state_connecting;
                case "disconnected" -> R.string.state_disconnected;
                case "no network" -> R.string.state_no_network;
                case "idle" -> R.string.state_idle;
                default -> R.string.state_stopped;
            });
            hostText = null;
            detailText = s.detail;
        }
        // this runs once a second: only touch the TextViews when the text really changed, or every
        // tick would queue a layout pass for the status block
        setTextIfChanged(statusTitle, titleText);
        hasHost = hostText != null;
        hasDetail = detailText != null;
        setTextIfChanged(statusHost, hasHost ? hostText : "");
        setTextIfChanged(statusDetail, hasDetail ? detailText : "");
        positionStatus(lastF);                         // applies the fade and the visibilities

        if (connected != wasConnected) {
            wasConnected = connected;
            if (connected) { statusHalo.setVisibility(View.VISIBLE); if (!pulse.isRunning()) pulse.start(); }
            else { pulse.cancel(); statusHalo.setVisibility(View.INVISIBLE); }
        }

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
                startForegroundService(svc);
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
                    .setAnchorView(fabs).show();
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
