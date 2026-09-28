package io.github.lcebot.clipsync

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Root-side keep-alive.  Battery-optimisation exemption alone does not stop every ROM from
 * freezing a foreground service; with root we can additionally pin the app's app-ops and
 * standby bucket, which is what the vendor "power keepers" consult.  The last line of defence
 * is in system_server: `xposed.Entry` refuses to freeze our process at all.
 */
object Root {
    private val COMMANDS = arrayOf(
        "dumpsys deviceidle whitelist +%p",                  // doze / app-standby whitelist
        "cmd appops set %p RUN_IN_BACKGROUND allow",
        "cmd appops set %p RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set %p START_FOREGROUND allow",
        "cmd appops set %p SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow",   // 14+, ignored if unknown
        "am set-standby-bucket %p exempted || am set-standby-bucket %p active",
    )

    /** How long su gets, in total, before it is killed. */
    private const val TIMEOUT_S: Long = 10

    /**
     * Runs the keep-alive commands via su; returns a one-line summary for the log.
     *
     * **The output is read on another thread, and that is the whole shape of this method.**
     * Reading the pipe to EOF on the calling thread before calling `waitFor(10, SECONDS)`
     * would defeat the timeout it is paired with: a su that prompts for confirmation and gets none,
     * or a Magisk daemon that is wedged, never closes the pipe and never writes `__done__`, so
     * `readLine()` would block for as long as it took, and the timeout would only ever be
     * reached by a process that had already finished talking. Caller side: this runs on the
     * service's start path.
     *
     * So the reader is a daemon thread, the calling thread waits on the process with a deadline,
     * and a timeout means `destroyForcibly()`, because plain `destroy()` sends SIGTERM, which a
     * shell sitting in a read may ignore, and the point of reaching this line is that asking nicely
     * has not worked.
     */
    fun keepAlive(pkg: String): String {
        val script = StringBuilder()
        for (c in COMMANDS) script.append(c.replace("%p", pkg)).append(" 2>&1\n")
        script.append("echo __done__\n")
        var p: Process? = null
        try {
            val proc: Process = Runtime.getRuntime().exec("su")
            p = proc
            OutputStreamWriter(proc.outputStream).use { w ->
                w.write(script.toString())
                w.write("exit\n")
            }
            // StringBuffer, not StringBuilder: written by the reader thread and read by this one
            // after the join/timeout. The handover is the join in the ordinary case, but a timed-out
            // reader is still running when we format the summary, so the buffer has to tolerate it.
            val out = StringBuffer()
            val reader = Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line == "__done__") break
                            if (line.isNotEmpty() && !line.startsWith("Added") && !line.startsWith("Unknown")) {
                                out.append(line).append("; ")
                            }
                        }
                    }
                } catch (ignored: Exception) {
                    // The pipe dies when the process is destroyed below. That is the expected way
                    // out of a timeout, not a condition to report twice.
                }
            }, "clipsync-su-read")
            reader.isDaemon = true
            reader.start()

            if (!proc.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "root keep-alive: su timed out after " + TIMEOUT_S + "s"
            }
            // It has exited, so the pipe is closed and the reader is about to finish; a short join
            // is only to let the last lines land in the summary, never to wait on anything.
            reader.join(500)
            if (proc.exitValue() != 0) return "root keep-alive: su denied (exit " + proc.exitValue() + ")"
            return "root keep-alive applied (whitelist, app-ops, standby bucket)" +
                    (if (out.length > 0) ": $out" else "")
        } catch (e: Exception) {
            p?.destroyForcibly()
            return "root keep-alive unavailable: " + e.message
        }
    }
}
