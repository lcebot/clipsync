package io.github.lcebot.clipsync;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Properties;

/**
 * Two pages behind a bottom navigation bar — Settings (writes files/clipsync.conf, restarts the
 * service) and Log — plus a status pill floating bottom-left on both: pulsing dot while connected,
 * then "mDNS" / "DDNS (LAN|Internet)", the peer name and the address in use.
 */
public class MainActivity extends AppCompatActivity {
    private TextInputLayout hostL, portL, pskL, timeoutL, textKbL, fileMbL, fileMbLocalL, pathL, keepHoursL, keepMbL;
    private TextInputEditText host, port, psk, timeout, textKb, fileMb, fileMbLocal, path, keepHours, keepMb;
    private MaterialSwitch mdns;
    private Slider threads;
    private TextView threadsLabel, log, statusTitle, statusDetail, batteryText;
    private View statusDot, statusHalo, batteryRow, batteryFix, pageSettings, pageLog;
    private MaterialCardView statusPill;
    private NestedScrollView logScroll;
    private ObjectAnimator pulse;
    private String lastState = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Logger.Listener logListener = line -> ui.post(() -> {
        log.append("\n" + line);
        refreshStatus();
        if (pageLog.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
    });
    // the service runs in its own process: its log and state reach us through files, polled 1/s
    private final Runnable statusTick = new Runnable() {
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

    /**
     * "Auto-start" = the BootReceiver component being enabled. Stop disables it so neither
     * BOOT_COMPLETED nor the system_server watchdog bring the service back; Apply re-enables it.
     */
    private boolean autoStartEnabled() {
        int s = getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(this, BootReceiver.class));
        return s != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setAutoStart(boolean on) {
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, BootReceiver.class),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                   : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
    }

    private void bind() {
        hostL = findViewById(R.id.host_layout);       host = findViewById(R.id.host);
        portL = findViewById(R.id.port_layout);       port = findViewById(R.id.port);
        pskL = findViewById(R.id.psk_layout);         psk = findViewById(R.id.psk);
        timeoutL = findViewById(R.id.timeout_layout); timeout = findViewById(R.id.timeout);
        textKbL = findViewById(R.id.text_kb_layout);  textKb = findViewById(R.id.text_kb);
        fileMbL = findViewById(R.id.file_mb_layout);  fileMb = findViewById(R.id.file_mb);
        fileMbLocalL = findViewById(R.id.file_mb_local_layout); fileMbLocal = findViewById(R.id.file_mb_local);
        pathL = findViewById(R.id.path_layout);       path = findViewById(R.id.path);
        keepHoursL = findViewById(R.id.keep_hours_layout); keepHours = findViewById(R.id.keep_hours);
        keepMbL = findViewById(R.id.keep_mb_layout);  keepMb = findViewById(R.id.keep_mb);
        mdns = findViewById(R.id.mdns);
        threads = findViewById(R.id.threads);
        threadsLabel = findViewById(R.id.threads_label);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        statusPill = findViewById(R.id.status_pill);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        statusDot = findViewById(R.id.status_dot);
        statusHalo = findViewById(R.id.status_halo);
        batteryRow = findViewById(R.id.battery_row);
        batteryText = findViewById(R.id.battery_text);
        batteryFix = findViewById(R.id.battery_fix);
        pageSettings = findViewById(R.id.page_settings);
        pageLog = findViewById(R.id.page_log);
    }

    private void loadFields() {
        Properties p = Config.raw(this);
        host.setText(p.getProperty("host", ""));
        port.setText(p.getProperty("port", "47521"));
        psk.setText(p.getProperty("psk", ""));
        timeout.setText(p.getProperty("mdns_timeout_ms", "4000"));
        textKb.setText(String.valueOf(longOf(p, "max_bytes", 1048576) / 1024));
        fileMb.setText(String.valueOf(longOf(p, "max_file_bytes", 10485760) / (1024 * 1024)));
        fileMbLocal.setText(String.valueOf(longOf(p, "max_file_bytes_local", 104857600) / (1024 * 1024)));
        path.setText(p.getProperty("files_dir", Config.DEFAULT_FILES_DIR));
        keepHours.setText(String.valueOf(longOf(p, "keep_hours", 2)));
        keepMb.setText(String.valueOf(longOf(p, "keep_max_mb", 256)));
        String m = p.getProperty("mdns", "true").trim().toLowerCase();
        mdns.setChecked(m.equals("true") || m.equals("1") || m.equals("yes") || m.equals("on"));
        timeoutL.setEnabled(mdns.isChecked());
        int t = Config.snapThreads((int) longOf(p, "threads", 8));
        threads.setValue(stepOf(t));
        threadsLabel.setText("Parallel connections per file: " + t);
    }

    private static int stepOf(int threadsValue) {
        for (int i = 0; i < Config.THREAD_STEPS.length; i++) if (Config.THREAD_STEPS[i] == threadsValue) return i;
        return 3;
    }

    private int threadsValue() {
        return Config.THREAD_STEPS[Math.max(0, Math.min(Config.THREAD_STEPS.length - 1, Math.round(threads.getValue())))];
    }

    private void wire() {
        mdns.setOnCheckedChangeListener((b, on) -> timeoutL.setEnabled(on));
        threads.setLabelFormatter(v -> String.valueOf(Config.THREAD_STEPS[Math.max(0, Math.min(4, Math.round(v)))]));
        threads.addOnChangeListener((s, v, fromUser) -> threadsLabel.setText("Parallel connections per file: " + threadsValue()));

        findViewById(R.id.apply).setOnClickListener(v -> apply());
        findViewById(R.id.stop).setOnClickListener(v -> {
            setAutoStart(false);                       // watchdog / boot must not bring it back
            stopService(new Intent(this, SyncService.class));
            Snackbar.make(v, "Service stopped (auto-start off until Apply)", Snackbar.LENGTH_SHORT).show();
            ui.postDelayed(this::refreshStatus, 300);
        });
        findViewById(R.id.log_clear).setOnClickListener(v -> {
            Logger.clear();
            log.setText("");
        });
        findViewById(R.id.log_copy).setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("clipsync log", log.getText()));
            Snackbar.make(v, "Log copied", Snackbar.LENGTH_SHORT).show();
        });
        batteryFix.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            boolean showLog = item.getItemId() == R.id.nav_log;
            pageSettings.setVisibility(showLog ? View.GONE : View.VISIBLE);
            pageLog.setVisibility(showLog ? View.VISIBLE : View.GONE);
            if (showLog) logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
            return true;
        });
        statusPill.setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_log));

        pulse = ObjectAnimator.ofFloat(statusHalo, View.ALPHA, 0.45f, 0f);
        pulse.setDuration(1400);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.RESTART);
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<String> lines = Logger.snapshot();
        log.setText(TextUtils.join("\n", lines));
        logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
        Logger.addListener(logListener);
        lastState = "";
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.removeListener(logListener);
        ui.removeCallbacks(statusTick);
        pulse.cancel();
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
        int color = MaterialColors.getColor(statusDot, connected ? com.google.android.material.R.attr.colorPrimary
                : busy ? com.google.android.material.R.attr.colorTertiary : com.google.android.material.R.attr.colorOutline);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        statusHalo.setBackgroundTintList(ColorStateList.valueOf(color));

        if (connected) {
            String kind = "mdns".equals(s.via) ? "mDNS" : "DDNS (" + (s.lan ? "LAN" : "Internet") + ")";
            statusTitle.setText(kind + " · " + s.host);
            statusDetail.setText(s.addr);
            statusDetail.setVisibility(View.VISIBLE);
        } else {
            String title = switch (s.state) {
                case "connecting" -> "Connecting…";
                case "disconnected" -> "Disconnected";
                case "no network" -> "No network";
                case "idle" -> "Idle";
                default -> "Stopped";
            };
            statusTitle.setText(title);
            statusDetail.setText(s.detail == null ? "" : s.detail);
            statusDetail.setVisibility(s.detail == null ? View.GONE : View.VISIBLE);
        }
        String key = s.state + "|" + s.via;
        if (!key.equals(lastState)) {
            lastState = key;
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
            batteryText.setText(exempt
                    ? "The system still froze ClipSync in the background. Check the log for \"root keep-alive\" and \"keep-alive hooks\" (LSPosed); on this ROM also allow autostart / no background restrictions and lock ClipSync in Recents."
                    : "Battery optimisation is on: the system may freeze ClipSync in the background (nothing is copied or received until you reopen it).");
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
        for (TextInputLayout l : new TextInputLayout[]{hostL, portL, pskL, timeoutL, textKbL, fileMbL, fileMbLocalL, pathL, keepHoursL, keepMbL})
            l.setError(null);
        try {
            Config c = Config.save(this,
                    text(host), text(port), text(psk), mdns.isChecked(), text(timeout), threadsValue(),
                    text(textKb), text(fileMb), text(fileMbLocal), text(path), text(keepHours), text(keepMb));
            setAutoStart(true);
            stopService(new Intent(this, SyncService.class));
            // give onDestroy a moment to release the clipboard listener and socket
            ui.postDelayed(() -> startForegroundService(new Intent(this, SyncService.class)), 300);
            Logger.i("config applied: " + (c.host.isEmpty() ? "(no host)" : c.host + ":" + c.port)
                    + (c.mdns ? " + mdns " + c.mdnsTimeoutMs + "ms" : "") + ", " + c.threads + " streams, files -> " + c.filesDir);
            Snackbar.make(statusPill, "Saved, service restarting", Snackbar.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "invalid value" : e.getMessage();
            TextInputLayout target = msg.startsWith("PSK") ? pskL
                    : msg.startsWith("port") ? portL
                    : msg.startsWith("mDNS") ? timeoutL
                    : msg.startsWith("text limit") ? textKbL
                    : msg.startsWith("LAN file limit") ? fileMbLocalL
                    : msg.startsWith("file limit") ? fileMbL
                    : msg.startsWith("path") ? pathL
                    : msg.startsWith("keep hours") ? keepHoursL
                    : msg.startsWith("keep size") ? keepMbL : hostL;
            target.setError(msg);
            target.requestFocus();
        } catch (Exception e) {
            Snackbar.make(statusPill, "Save failed: " + e, Snackbar.LENGTH_LONG).show();
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
