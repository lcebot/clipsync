package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
 * <p>The file is written through one writer that stays open and is flushed at most every
 * {@link #FLUSH_MS} ms, so a line can be up to that far behind the disk, which is the price of not paying
 * four syscalls per line on whichever thread happened to log. A crash can therefore lose the last
 * fraction of a second; everything older is there.
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
     * rebuild, exactly what appending exists to avoid. With it, a reader's view may hold up to
     * MAX_ENTRIES + this many entries between rebuilds.
     */
    private static final int REBUILD_SLACK = 64;

    private static final ArrayDeque<String> lines = new ArrayDeque<>(MAX_ENTRIES);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Volatile: set under the class lock by {@link #init}, read under {@link #FILE_LOCK}. */
    private static volatile File file;

    // ------------------------------------------------------------------ the open writer
    /**
     * Guards {@link #out}, {@link #writtenLen} and {@link #flushedLen}; the file side of this
     * class, and nothing else.
     *
     * <p>A second lock rather than reusing the class lock, because they protect different things and
     * one of them touches the disk. Holding the class lock across the whole append would block every
     * other thread, including the UI's {@link #read}, for the duration of a file open, a stat, a
     * write and a close. So the class lock covers only the in-memory ring, and this one covers the
     * writer.
     *
     * <p><b>Lock order is always class lock first, then this one</b> ({@link #flushNow} is the only
     * place that holds both). Nothing under this lock ever asks for the class lock.
     */
    private static final Object FILE_LOCK = new Object();
    /**
     * Held open, rather than opened per line: a fresh open, stat, write and close for every log call
     * is four syscalls and a path walk on whichever thread happened to log, and on the screen-on
     * broadcast, that is the main thread.
     */
    private static BufferedWriter out;
    /** Bytes handed to {@link #out} since the file was opened or rotated. */
    private static long writtenLen;
    /** Of those, how many have reached the disk. {@link #loadedLen} may only follow this. */
    private static long flushedLen;
    /**
     * How long a line may sit in the writer's buffer.
     *
     * <p>Not "never" and not "immediately". Never is wrong because this log's whole purpose is to
     * survive the process, and because the UI process reads the :sync process's lines out of this
     * very file, so an unflushed line is a line that never appears on the Log page. Immediately is
     * wrong because it is a syscall per line again. A third of a second is below what anybody
     * notices on a log tail and still batches a burst, such as a reload or a network change, into one write.
     */
    private static final long FLUSH_MS = 300;
    private static java.util.concurrent.ScheduledExecutorService flusher;
    /** A deferred flush is already queued; no need for another. */
    private static boolean flushPending;
    private static long lastFlushAt;

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
     * read and one entry, so readers can then append rather than rebuild. A file that shrank was
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
        // The ring first, and on its own lock. A reader that is only after the in-memory tail is
        // then never held up by the disk, which matters because one of the callers of this method
        // is the screen-on broadcast, on the main thread.
        synchronized (Logger.class) {
            push(line);
        }
        appendFile(line);
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
        boolean flushDue = false;
        synchronized (FILE_LOCK) {
            if (file == null) return;
            try {
                // Against our own counter, not file.length(): a stat per line is exactly the kind of
                // syscall the open writer exists to stop making, and the counter is authoritative
                // anyway, since we are the only writer of this file in this process.
                if (out == null || writtenLen > MAX_FILE_BYTES) openWriter();
                if (out == null) return;
                out.write(line);
                out.write('\n');
                // UTF-8 is variable width, so this over- or under-counts for non-ASCII. It is a
                // rotation threshold and a "has anything new been flushed" marker, not an offset
                // into anything, and both tolerate being approximate. length() rather than an
                // encode is the trade being made knowingly.
                writtenLen += line.length() + 1;
            } catch (IOException e) {
                closeWriter();                  // a broken writer stays broken; reopen on the next line
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFlushAt >= FLUSH_MS) {
                flushDue = true;
            } else if (!flushPending) {
                flushPending = true;
                scheduleFlush(FLUSH_MS - (now - lastFlushAt));
            }
        }
        // Outside the lock it needs, because it takes the class lock first and this method must
        // never be holding FILE_LOCK when that happens. See FILE_LOCK's note on lock order.
        if (flushDue) flushNow();
    }

    /**
     * Get the buffered lines onto the disk, and let {@link #refresh()} know they are ours.
     *
     * <p>The second half is the subtle one. {@code refresh()} reads whatever the file has grown by
     * and pushes it into the ring, which is right for the :sync process's lines and wrong for our
     * own, since {@link #write} has already pushed those. Advancing {@code loadedLen} past the bytes
     * we just flushed is what stops every line this process logs from appearing twice.
     *
     * <p>Only as far as {@code flushedLen}, never as far as {@code writtenLen}: a {@code loadedLen}
     * ahead of the real file length reads as "the file shrank", which {@code refresh()} correctly
     * treats as a rotation and answers with a full reload.
     */
    private static void flushNow() {
        synchronized (Logger.class) {
            synchronized (FILE_LOCK) {
                flushPending = false;
                lastFlushAt = System.currentTimeMillis();
                if (out == null) return;
                try {
                    out.flush();
                    flushedLen = writtenLen;
                } catch (IOException e) {
                    closeWriter();
                    return;
                }
                if (loadedLen < flushedLen) loadedLen = flushedLen;
            }
        }
    }

    /** One daemon thread, created on the first deferred flush and shared from then on. */
    private static void scheduleFlush(long delayMs) {
        if (flusher == null) {
            flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "clipsync-log-flush");
                t.setDaemon(true);
                return t;
            });
        }
        flusher.schedule(Logger::flushNow, Math.max(0, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Caller holds {@link #FILE_LOCK}. Closes any writer, rotates if the file is full, and opens a
     * new writer positioned at the end.
     *
     * <p>Closing first is what makes the size test honest: a buffered writer's bytes are not in
     * {@code length()} until they are, and {@code close()} flushes. So the file on disk is complete
     * before it is measured, and complete before it is renamed, so a rotation cannot lose the tail.
     *
     * <p>{@code loadedLen} is deliberately left alone across a rotation: leaving it above the new
     * file's length is exactly how {@link #refresh()} notices, and a reload is the right answer
     * there: the buffer's oldest entries are now in a file nothing reads any more.
     */
    private static void openWriter() throws IOException {
        closeWriter();
        if (file.length() > MAX_FILE_BYTES) {
            File old = new File(file.getPath() + ".1");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(old);
        }
        out = new BufferedWriter(new FileWriter(file, true));
        writtenLen = file.length();
        flushedLen = writtenLen;
    }

    private static void closeWriter() {
        BufferedWriter w = out;
        out = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException ignored) {
        }
    }

    public static void clear() {
        synchronized (Logger.class) {
            synchronized (FILE_LOCK) {
                lines.clear();
                epoch++;                // readers rebuild rather than try to append onto nothing
                added = 0;
                evicted = 0;
                closeWriter();          // before the delete, or the next write resurrects the file
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    //noinspection ResultOfMethodCallIgnored
                    new File(file.getPath() + ".1").delete();
                }
                loadedLen = 0;
                writtenLen = 0;
                flushedLen = 0;
            }
        }
    }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }
}
