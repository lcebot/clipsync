package io.github.lcebot.clipsync;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/**
 * Root-side keep-alive.  Battery-optimisation exemption alone does not stop every ROM from
 * freezing a foreground service; with root we can additionally pin the app's app-ops and
 * standby bucket, which is what the vendor "power keepers" consult.  The last line of defence
 * is in system_server: {@code xposed.Entry} refuses to freeze our process at all.
 */
public final class Root {
    private Root() {}

    private static final String[] COMMANDS = {
            "dumpsys deviceidle whitelist +%p",                  // doze / app-standby whitelist
            "cmd appops set %p RUN_IN_BACKGROUND allow",
            "cmd appops set %p RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set %p START_FOREGROUND allow",
            "cmd appops set %p SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow",   // 14+, ignored if unknown
            "am set-standby-bucket %p exempted || am set-standby-bucket %p active",
    };

    /** Runs the keep-alive commands via su; returns a one-line summary for the log. */
    public static String keepAlive(String pkg) {
        StringBuilder script = new StringBuilder();
        for (String c : COMMANDS) script.append(c.replace("%p", pkg)).append(" 2>&1\n");
        script.append("echo __done__\n");
        try {
            Process p = Runtime.getRuntime().exec("su");
            try (Writer w = new OutputStreamWriter(p.getOutputStream())) {
                w.write(script.toString());
                w.write("exit\n");
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.equals("__done__")) break;
                    if (!line.isEmpty() && !line.startsWith("Added") && !line.startsWith("Unknown")) out.append(line).append("; ");
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroy();
                return "root keep-alive: su timed out";
            }
            if (p.exitValue() != 0) return "root keep-alive: su denied (exit " + p.exitValue() + ")";
            return "root keep-alive applied (whitelist, app-ops, standby bucket)" + (out.length() > 0 ? " — " + out : "");
        } catch (Exception e) {
            return "root keep-alive unavailable: " + e.getMessage();
        }
    }
}
