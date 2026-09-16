package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-process log: in-memory ring buffer (shown by MainActivity) + files/clipsync.log
 * (survives process death, rotated at 256 KB). Nothing goes to logcat.
 * The Xposed hook runs in system_server and is a different process; it keeps using the
 * framework logger (visible in LSPosed's log viewer).
 */
public final class Logger {
    public interface Listener { void onLine(String line); }

    private static final int MAX_LINES = 500;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    // DateTimeFormatter is immutable/thread-safe (SimpleDateFormat is not; write() is called from
    // the net, ping, push and UI threads concurrently)
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US);

    private static final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static File file;

    private Logger() {}

    private static long loadedLen = -1;

    /** Idempotent; loads the tail of the on-disk log into the buffer the first time. */
    public static synchronized void init(Context ctx) {
        if (file != null) return;
        file = new File(ctx.getApplicationContext().getFilesDir(), "clipsync.log");
        reload();
    }

    private static void reload() {
        lines.clear();
        loadedLen = file.exists() ? file.length() : 0;
        if (loadedLen == 0) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String l;
            while ((l = r.readLine()) != null) push(l);
        } catch (IOException ignored) {
        }
    }

    /**
     * The service writes the log from its own process (":sync"); the UI polls this to pick up
     * new lines. Returns true when the file changed and the buffer was reloaded.
     */
    public static synchronized boolean refresh() {
        if (file == null) return false;
        long len = file.exists() ? file.length() : 0;
        if (len == loadedLen) return false;
        reload();
        return true;
    }

    public static void i(String msg) { write("I", msg, null); }
    public static void w(String msg) { write("W", msg, null); }
    public static void w(String msg, Throwable t) { write("W", msg, t); }

    private static void write(String level, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(LocalDateTime.now().format(TS)).append(' ').append(level).append(' ').append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw.toString().trim());
        }
        String line = sb.toString();
        synchronized (Logger.class) {
            push(line);
            appendFile(line);
        }
        for (Listener l : listeners) l.onLine(line);
    }

    private static void push(String line) {
        if (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(line);
    }

    private static void appendFile(String line) {
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE_BYTES) {
                File old = new File(file.getPath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(line);
                w.write('\n');
            }
            loadedLen = file.length();          // our own append is already in the buffer
        } catch (IOException ignored) {
        }
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    public static synchronized void clear() {
        lines.clear();
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(file.getPath() + ".1").delete();
        }
        loadedLen = 0;
    }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }
}
