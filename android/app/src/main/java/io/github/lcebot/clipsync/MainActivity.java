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
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Properties;

/**
 * Two pages behind a bottom navigation bar — Settings and Log — under a header that shrinks on
 * scroll (title smaller, Stop / Apply reduced to icons), plus a status pill floating bottom-left
 * on both pages: pulsing dot while connected, "mDNS" / "DDNS (LAN|Internet)", peer and address;
 * it folds to just the dot 10 s after the last change and unfolds on any change.
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
    // header
    private View title;
    private ViewGroup headerRow;
    private MaterialButton stopBtn, applyBtn;
    // pages / log
    private View pageSettings, pageLog, batteryRow, batteryFix;
    private TextView log, batteryText;
    private NestedScrollView logScroll;
    // status pill
    private MaterialCardView statusPill;
    private ViewGroup statusInner;
    private View statusText, statusDot, statusHalo;
    private TextView statusTitle, statusHost, statusDetail;
    private ObjectAnimator pulse;
    private String lastPillContent = "";
    private boolean pillFolded;
    private static final long FOLD_AFTER_MS = 10_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable fold = () -> setPillFolded(true);
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
        headerRow = findViewById(R.id.header_row);
        title = findViewById(R.id.title);
        stopBtn = findViewById(R.id.stop);
        applyBtn = findViewById(R.id.apply);
        pageSettings = findViewById(R.id.page_settings);
        pageLog = findViewById(R.id.page_log);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        batteryRow = findViewById(R.id.battery_row);
        batteryText = findViewById(R.id.battery_text);
        batteryFix = findViewById(R.id.battery_fix);
        statusPill = findViewById(R.id.status_pill);
        statusInner = findViewById(R.id.status_inner);
        statusText = findViewById(R.id.status_text);
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
        threadsLabel.setText("Parallel connections per file: " + t);
        int b = Config.snapBrowse((int) longOf(p, "mdns_timeout_ms", 4000));
        browse.setValue(indexOf(Config.BROWSE_STEPS_MS, b));
        browseLabel.setText("mDNS browse: " + b + " ms");
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
        applyBtn.setEnabled(ok);
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
        threads.addOnChangeListener((s, v, u) -> threadsLabel.setText("Parallel connections per file: " + threadsValue()));
        browse.setLabelFormatter(v -> Config.BROWSE_STEPS_MS[Math.max(0, Math.min(6, Math.round(v)))] + " ms");
        browse.addOnChangeListener((s, v, u) -> browseLabel.setText("mDNS browse: " + browseValue() + " ms"));

        applyBtn.setOnClickListener(v -> apply());
        stopBtn.setOnClickListener(v -> {
            setAutoStart(false);                       // watchdog / boot must not bring it back
            stopService(new Intent(this, SyncService.class));
            Snackbar.make(statusPill, "Service stopped (auto-start off until Apply)", Snackbar.LENGTH_SHORT).show();
            ui.postDelayed(this::refreshStatus, 300);
        });
        findViewById(R.id.log_clear).setOnClickListener(v -> { Logger.clear(); log.setText(""); });
        findViewById(R.id.log_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync log", log.getText()));
            Snackbar.make(statusPill, "Log copied", Snackbar.LENGTH_SHORT).show();
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
            if (showLog) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
            return true;
        });
        statusPill.setOnClickListener(v -> { if (pillFolded) setPillFolded(false); else nav.setSelectedItemId(R.id.nav_log); });

        // header: shrink title, drop button labels once more than half collapsed
        AppBarLayout appbar = findViewById(R.id.appbar);
        appbar.addOnOffsetChangedListener((bar, offset) -> {
            int range = Math.max(1, bar.getTotalScrollRange());
            float f = Math.min(1f, -offset / (float) range);
            float scale = 1f - 0.25f * f;                 // headline (28sp) -> ~title (21sp)
            title.setScaleX(scale);
            title.setScaleY(scale);
            morphButtons(f);
        });

        pulse = ObjectAnimator.ofFloat(statusHalo, View.ALPHA, 0.45f, 0f);
        pulse.setDuration(1400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.RESTART);
    }

    private int btnMinWidth = -1;
    private int stopTextColor, applyTextColor;

    /**
     * Continuous morph driven by the scroll fraction (like the title): the label is squeezed
     * horizontally and faded out, paddings and minimum width shrink in step, until at f = 1 the
     * button is 24dp icon + 2 × 8dp — as wide as it is tall, which on the full-pill M3 shape is
     * a circle. No discrete jump anywhere.
     */
    private void morphButtons(float f) {
        if (btnMinWidth < 0) {
            btnMinWidth = applyBtn.getMinWidth();
            stopTextColor = stopBtn.getCurrentTextColor();
            applyTextColor = applyBtn.getCurrentTextColor();
        }
        float t = Math.max(0f, Math.min(1f, (f - 0.15f) / 0.7f));     // morph between 15 % and 85 %
        for (MaterialButton b : new MaterialButton[]{stopBtn, applyBtn}) {
            int base = b == applyBtn ? applyTextColor : stopTextColor;
            b.setTextScaleX(Math.max(0.001f, 1f - t));                 // 0 would be ignored by TextView
            b.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(base, Math.round(255 * (1f - t))));
            b.setIconPadding(Math.round(dp(8) * (1f - t)));
            int mw = Math.round(btnMinWidth * (1f - t));
            b.setMinWidth(mw);
            b.setMinimumWidth(mw);
            int pad = Math.round(dp(16) + (dp(8) - dp(16)) * t);
            b.setPadding(pad, b.getPaddingTop(), pad, b.getPaddingBottom());
        }
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
        lastPillContent = "";
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.removeListener(logListener);
        ui.removeCallbacks(tick);
        ui.removeCallbacks(fold);
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

    // ------------------------------------------------------------------ status pill
    private void refreshStatus() {
        Status.Snapshot s = Status.read(this);
        if (!s.alive() && !"stopped".equals(s.state)) {
            // the service process is gone without saying goodbye (killed); the watchdog restarts it
            s = new Status.Snapshot("stopped", autoStartEnabled() ? "not running — restarting" : "auto-start off",
                    null, false, null, null, 0, s.suspended);
        }
        boolean connected = "connected".equals(s.state);
        boolean busy = "connecting".equals(s.state);
        boolean stopped = "stopped".equals(s.state);

        int dot = MaterialColors.getColor(statusDot, connected ? androidx.appcompat.R.attr.colorPrimary
                : busy ? com.google.android.material.R.attr.colorTertiary : com.google.android.material.R.attr.colorOutline);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(dot));
        statusHalo.setBackgroundTintList(ColorStateList.valueOf(dot));
        // stopped: the whole pill goes grey, not just the dot
        int bg = MaterialColors.getColor(statusPill, stopped ? com.google.android.material.R.attr.colorSurfaceContainerHighest
                : com.google.android.material.R.attr.colorSecondaryContainer);
        int fg = MaterialColors.getColor(statusPill, stopped ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSecondaryContainer);
        statusPill.setCardBackgroundColor(bg);
        statusTitle.setTextColor(fg);
        statusHost.setTextColor(fg);
        statusDetail.setTextColor(fg);

        // line 1: kind / state · line 2: peer name · line 3: address (or state detail)
        String titleText, hostText, detailText;
        if (connected) {
            titleText = "mdns".equals(s.via) ? "mDNS" : "DDNS (" + (s.lan ? "LAN" : "Internet") + ")";
            hostText = s.host;
            detailText = s.addr;
        } else {
            titleText = switch (s.state) {
                case "connecting" -> "Connecting…";
                case "disconnected" -> "Disconnected";
                case "no network" -> "No network";
                case "idle" -> "Idle";
                default -> "Stopped";
            };
            hostText = null;
            detailText = s.detail;
        }
        statusTitle.setText(titleText);
        statusHost.setText(hostText == null ? "" : hostText);
        statusHost.setVisibility(hostText == null ? View.GONE : View.VISIBLE);
        statusDetail.setText(detailText == null ? "" : detailText);
        statusDetail.setVisibility(detailText == null ? View.GONE : View.VISIBLE);

        String content = s.state + "|" + titleText + "|" + hostText + "|" + detailText;
        if (!content.equals(lastPillContent)) {
            lastPillContent = content;
            if (connected) { statusHalo.setVisibility(View.VISIBLE); if (!pulse.isRunning()) pulse.start(); }
            else { pulse.cancel(); statusHalo.setVisibility(View.INVISIBLE); }
            setPillFolded(false);                      // any change unfolds; it folds again 10 s later
            ui.removeCallbacks(fold);
            ui.postDelayed(fold, FOLD_AFTER_MS);
        }

        // background-permission card
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (exempt && !s.suspended) {
            batteryRow.setVisibility(View.GONE);
        } else {
            batteryRow.setVisibility(View.VISIBLE);
            batteryText.setText(exempt
                    ? "The system still froze ClipSync in the background. Check the log for \"root keep-alive\" and \"hooked\" (LSPosed); on this ROM also allow autostart / no background restrictions and lock ClipSync in Recents."
                    : "Battery optimisation is on: the system may freeze ClipSync in the background (nothing is copied or received until you reopen it).");
            batteryFix.setVisibility(exempt ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Fold: the card shrinks to an 84dp rounded square with the dot in the middle; unfold: the dot
     * slides left and the text appears. Height never changes (fixed in the layout).
     */
    private void setPillFolded(boolean folded) {
        if (folded == pillFolded) return;
        pillFolded = folded;
        TransitionManager.beginDelayedTransition(statusPill, new TransitionSet()
                .addTransition(new ChangeBounds()).addTransition(new Fade()).setDuration(260));
        statusText.setVisibility(folded ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams lp = statusInner.getLayoutParams();
        lp.width = folded ? dp(84) : ViewGroup.LayoutParams.WRAP_CONTENT;
        statusInner.setLayoutParams(lp);
        statusInner.setPadding(folded ? 0 : dp(20), 0, folded ? 0 : dp(20), 0);
        ((android.widget.LinearLayout) statusInner).setGravity(folded
                ? android.view.Gravity.CENTER : android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        if (!folded) {
            ui.removeCallbacks(fold);                  // a manual unfold folds again after the same delay
            ui.postDelayed(fold, FOLD_AFTER_MS);
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
            Snackbar.make(statusPill, "Saved and applied", Snackbar.LENGTH_SHORT).show();
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
            Snackbar.make(statusPill, "Save failed: " + e, Snackbar.LENGTH_LONG).show();
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
