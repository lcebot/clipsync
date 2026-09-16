package io.github.lcebot.clipsync;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Properties;

/**
 * Settings on top (overrides the compile-time defaults, stored in files/clipsync.conf),
 * live log below. "Apply" validates, saves and restarts the service.
 */
public class MainActivity extends AppCompatActivity {
    private TextInputLayout hostL, portL, pskL, timeoutL, threadsL, textKbL, fileMbL, fileMbLocalL, keepHoursL, keepMbL;
    private TextInputEditText host, port, psk, timeout, threads, textKb, fileMb, fileMbLocal, keepHours, keepMb;
    private MaterialSwitch mdns;
    private TextView status, log;
    private NestedScrollView logScroll;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Logger.Listener logListener = line -> ui.post(() -> {
        log.append("\n" + line);
        refreshStatus();
        logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
    });
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(this);
        setContentView(R.layout.activity_main);

        hostL = findViewById(R.id.host_layout);
        portL = findViewById(R.id.port_layout);
        pskL = findViewById(R.id.psk_layout);
        timeoutL = findViewById(R.id.timeout_layout);
        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        psk = findViewById(R.id.psk);
        timeout = findViewById(R.id.timeout);
        threadsL = findViewById(R.id.threads_layout);
        threads = findViewById(R.id.threads);
        textKbL = findViewById(R.id.text_kb_layout);
        fileMbL = findViewById(R.id.file_mb_layout);
        fileMbLocalL = findViewById(R.id.file_mb_local_layout);
        keepHoursL = findViewById(R.id.keep_hours_layout);
        keepMbL = findViewById(R.id.keep_mb_layout);
        textKb = findViewById(R.id.text_kb);
        fileMb = findViewById(R.id.file_mb);
        fileMbLocal = findViewById(R.id.file_mb_local);
        keepHours = findViewById(R.id.keep_hours);
        keepMb = findViewById(R.id.keep_mb);
        mdns = findViewById(R.id.mdns);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);

        Properties p = Config.raw(this);
        host.setText(p.getProperty("host", ""));
        port.setText(p.getProperty("port", "47521"));
        psk.setText(p.getProperty("psk", ""));
        timeout.setText(p.getProperty("mdns_timeout_ms", "4000"));
        threads.setText(p.getProperty("threads", "8"));
        textKb.setText(String.valueOf(longOf(p, "max_bytes", 1048576) / 1024));
        fileMb.setText(String.valueOf(longOf(p, "max_file_bytes", 10485760) / (1024 * 1024)));
        fileMbLocal.setText(String.valueOf(longOf(p, "max_file_bytes_local", 104857600) / (1024 * 1024)));
        keepHours.setText(String.valueOf(longOf(p, "keep_hours", 2)));
        keepMb.setText(String.valueOf(longOf(p, "keep_max_mb", 256)));
        String m = p.getProperty("mdns", "true").trim().toLowerCase();
        mdns.setChecked(m.equals("true") || m.equals("1") || m.equals("yes") || m.equals("on"));
        mdns.setOnCheckedChangeListener((b, on) -> timeoutL.setEnabled(on));
        timeoutL.setEnabled(mdns.isChecked());

        MaterialButton apply = findViewById(R.id.apply);
        apply.setOnClickListener(v -> apply());
        MaterialButton stop = findViewById(R.id.stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SyncService.class));
            Snackbar.make(v, "Service stopped", Snackbar.LENGTH_SHORT).show();
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

        // an installed-but-never-started app is in the "stopped" state and gets no BOOT_COMPLETED;
        // opening the activity once (and starting the service) clears that.
        if (!SyncService.isAlive()) {
            try {
                Config.load(this);
                startForegroundService(new Intent(this, SyncService.class));
            } catch (RuntimeException ignored) {
                // invalid config: user will fix it and press Apply
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<String> lines = Logger.snapshot();
        log.setText(TextUtils.join("\n", lines));
        logScroll.post(() -> logScroll.fullScroll(NestedScrollView.FOCUS_DOWN));
        Logger.addListener(logListener);
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.removeListener(logListener);
        ui.removeCallbacks(statusTick);
    }

    private void refreshStatus() {
        status.setText("Service: " + SyncService.status());
    }

    private static long longOf(Properties p, String key, long dflt) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(dflt)).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private void apply() {
        for (TextInputLayout l : new TextInputLayout[]{hostL, portL, pskL, timeoutL, threadsL, textKbL, fileMbL, fileMbLocalL, keepHoursL, keepMbL})
            l.setError(null);
        try {
            Config c = Config.save(this,
                    text(host), text(port), text(psk), mdns.isChecked(), text(timeout), text(threads),
                    text(textKb), text(fileMb), text(fileMbLocal), text(keepHours), text(keepMb));
            stopService(new Intent(this, SyncService.class));
            // give onDestroy a moment to release the clipboard listener and socket
            ui.postDelayed(() -> startForegroundService(new Intent(this, SyncService.class)), 300);
            Logger.i("config applied: " + (c.host.isEmpty() ? "(no host)" : c.host + ":" + c.port)
                    + (c.mdns ? " + mdns " + c.mdnsTimeoutMs + "ms" : ""));
            Snackbar.make(status, "Saved, service restarting", Snackbar.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "invalid value" : e.getMessage();
            TextInputLayout target = msg.startsWith("PSK") ? pskL
                    : msg.startsWith("port") ? portL
                    : msg.startsWith("mDNS") ? timeoutL
                    : msg.startsWith("streams") ? threadsL
                    : msg.startsWith("text limit") ? textKbL
                    : msg.startsWith("LAN file limit") ? fileMbLocalL
                    : msg.startsWith("file limit") ? fileMbL
                    : msg.startsWith("keep hours") ? keepHoursL
                    : msg.startsWith("keep size") ? keepMbL : hostL;
            target.setError(msg);
            target.requestFocus();
        } catch (Exception e) {
            Snackbar.make(status, "Save failed: " + e, Snackbar.LENGTH_LONG).show();
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
