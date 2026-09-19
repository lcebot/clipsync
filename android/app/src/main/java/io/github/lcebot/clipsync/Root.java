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

    /** How long su gets, in total, before it is killed. */
    private static final long TIMEOUT_S = 10;

    /**
     * Runs the keep-alive commands via su; returns a one-line summary for the log.
     *
     * <p><b>The output is read on another thread, and that is the whole shape of this method.</b>
     * It used to read the pipe to EOF on the calling thread and only then call
     * {@code waitFor(10, SECONDS)} — which meant the timeout could not fire in the case it existed
     * for. A su that prompts for confirmation and gets none, or a Magisk daemon that is wedged,
     * never closes the pipe and never writes {@code __done__}, so {@code readLine()} blocked for as
     * long as it took, and the timeout below it was only ever reached by a process that had already
     * finished talking. Caller side: this runs on the service's start path.
     *
     * <p>So the reader is a daemon thread, the calling thread waits on the process with a deadline,
     * and a timeout means {@code destroyForcibly()} — plain {@code destroy()} sends SIGTERM, which a
     * shell sitting in a read may ignore, and the point of reaching this line is that asking nicely
     * has not worked.
     */
    public static String keepAlive(String pkg) {
        StringBuilder script = new StringBuilder();
        for (String c : COMMANDS) script.append(c.replace("%p", pkg)).append(" 2>&1\n");
        script.append("echo __done__\n");
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            try (Writer w = new OutputStreamWriter(p.getOutputStream())) {
                w.write(script.toString());
                w.write("exit\n");
            }
            // StringBuffer, not StringBuilder: written by the reader thread and read by this one
            // after the join/timeout. The handover is the join in the ordinary case, but a timed-out
            // reader is still running when we format the summary, so the buffer has to tolerate it.
            StringBuffer out = new StringBuffer();
            Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.equals("__done__")) break;
                        if (!line.isEmpty() && !line.startsWith("Added") && !line.startsWith("Unknown")) {
                            out.append(line).append("; ");
                        }
                    }
                } catch (Exception ignored) {
                    // The pipe dies when the process is destroyed below. That is the expected way
                    // out of a timeout, not a condition to report twice.
                }
            }, "clipsync-su-read");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "root keep-alive: su timed out after " + TIMEOUT_S + "s";
            }
            // It has exited, so the pipe is closed and the reader is about to finish; a short join
            // is only to let the last lines land in the summary, never to wait on anything.
            reader.join(500);
            if (p.exitValue() != 0) return "root keep-alive: su denied (exit " + p.exitValue() + ")";
            return "root keep-alive applied (whitelist, app-ops, standby bucket)" + (out.length() > 0 ? " — " + out : "");
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return "root keep-alive unavailable: " + e.getMessage();
        }
    }
}
