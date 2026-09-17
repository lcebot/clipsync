package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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

    /**
     * Entries kept in memory, oldest dropped first. One entry is normally one line, but a message
     * logged with a throwable is a single entry carrying its whole stack trace. This is also what
     * bounds the cost of the Log page, which renders the buffer as one selectable TextView.
     */
    static final int MAX_ENTRIES = 512;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    // DateTimeFormatter is immutable/thread-safe (SimpleDateFormat is not; write() is called from
    // the net, ping, push and UI threads concurrently)
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US);

    /**
     * How many evicted entries a reader tolerates before it is told to rebuild instead of append.
     * Without it, a full buffer would evict one entry per new line and every update would be a
     * rebuild — exactly what appending exists to avoid. With it, a reader's view may hold up to
     * MAX_ENTRIES + this many entries between rebuilds.
     */
    private static final int REBUILD_SLACK = 64;

    private static final ArrayDeque<String> lines = new ArrayDeque<>(MAX_ENTRIES);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static File file;

    private Logger() {}

    private static long loadedLen = -1;
    private static long added;          // entries ever pushed; never reset except by a rebuild
    private static long evicted;        // entries dropped off the head
    private static int epoch;           // bumped whenever the buffer is thrown away and rebuilt

    /** A reader's place in the buffer. Single-threaded use (the UI thread). */
    public static final class Cursor {
        private int epoch = -1;
        private long seen;
        private long evicted;
    }

    /** What a reader must do to catch up: either append these lines, or replace everything. */
    public static final class Tail {
        public final boolean replace;
        public final List<String> lines;

        Tail(boolean replace, List<String> lines) {
            this.replace = replace;
            this.lines = lines;
        }

        public boolean isEmpty() {
            return !replace && lines.isEmpty();
        }
    }

    /**
     * Advances {@code c} to the present and returns the work needed to match it. Appending is the
     * normal case; a replace is only asked for when the buffer was rebuilt or has dropped more
     * than {@link #REBUILD_SLACK} entries since this reader last rebuilt.
     */
    public static synchronized Tail read(Cursor c) {
        boolean rebuild = c.epoch != epoch || evicted - c.evicted >= REBUILD_SLACK || c.seen > added;
        if (rebuild) {
            c.epoch = epoch;
            c.evicted = evicted;
            c.seen = added;
            return new Tail(true, new ArrayList<>(lines));
        }
        long fresh = added - c.seen;
        c.seen = added;
        if (fresh <= 0) return new Tail(false, List.of());
        if (fresh >= lines.size()) return new Tail(true, new ArrayList<>(lines));
        List<String> out = new ArrayList<>((int) fresh);
        int skip = lines.size() - (int) fresh;
        int i = 0;
        for (String l : lines) {
            if (i++ >= skip) out.add(l);
        }
        return new Tail(false, out);
    }

    /** Idempotent; loads the tail of the on-disk log into the buffer the first time. */
    public static synchronized void init(Context ctx) {
        if (file != null) return;
        file = new File(ctx.getApplicationContext().getFilesDir(), "clipsync.log");
        reload();
    }

    private static void reload() {
        lines.clear();
        epoch++;
        added = 0;
        evicted = 0;
        loadedLen = file.exists() ? file.length() : 0;
        if (loadedLen == 0) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) push(l);
        } catch (IOException ignored) {
        }
    }

    /**
     * The service writes the log from its own process (":sync"); the UI polls this to pick up new
     * lines. Only the bytes appended since the last call are read, so a new line costs one small
     * read and one entry — readers can then append rather than rebuild. A file that shrank was
     * rotated or cleared, and is the one case that still reloads wholesale.
     *
     * <p>Returns true when anything was added.
     */
    public static synchronized boolean refresh() {
        if (file == null) return false;
        long len = file.exists() ? file.length() : 0;
        if (len == loadedLen) return false;
        if (len < loadedLen) {
            reload();
            return true;
        }
        byte[] buf = new byte[(int) Math.min(len - loadedLen, MAX_FILE_BYTES)];
        try (FileInputStream in = new FileInputStream(file)) {
            long skipped = in.skip(loadedLen);
            if (skipped != loadedLen || in.read(buf) != buf.length) return false;
        } catch (IOException e) {
            return false;
        }
        // stop at the last newline: the writer appends the text and the '\n' separately, so the
        // tail can be half a line, and that half must be left for the next call
        int end = buf.length;
        while (end > 0 && buf[end - 1] != '\n') end--;
        if (end == 0) return false;
        for (String l : new String(buf, 0, end, StandardCharsets.UTF_8).split("\n")) {
            if (!l.isEmpty()) push(l);
        }
        loadedLen += end;
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
        if (lines.size() >= MAX_ENTRIES) {
            lines.pollFirst();
            evicted++;
        }
        lines.addLast(line);
        added++;
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

    public static synchronized void clear() {
        lines.clear();
        epoch++;                        // readers rebuild rather than try to append onto nothing
        added = 0;
        evicted = 0;
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
