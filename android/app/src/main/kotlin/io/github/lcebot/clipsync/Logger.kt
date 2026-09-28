package io.github.lcebot.clipsync

import android.content.Context

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * App-process log: in-memory ring buffer (shown by MainActivity) + files/clipsync.log
 * (survives process death, rotated at 256 KB). Nothing goes to logcat.
 *
 * The file is written through one writer that stays open and is flushed at most every
 * [FLUSH_MS] ms, so a line can be up to that far behind the disk, which is the price of not paying
 * four syscalls per line on whichever thread happened to log. A crash can therefore lose the last
 * fraction of a second; everything older is there.
 * The Xposed hook runs in system_server and is a different process; it keeps using the
 * framework logger (visible in LSPosed's log viewer).
 */
object Logger {
    fun interface Listener { fun onLine(line: String) }

    /**
     * Entries kept in memory, oldest dropped first. One entry is normally one line, but a message
     * logged with a throwable is a single entry carrying its whole stack trace. This is also what
     * bounds the cost of the Log page, which renders the buffer as one selectable TextView.
     */
    internal const val MAX_ENTRIES = 512
    private const val MAX_FILE_BYTES = 256L * 1024
    // DateTimeFormatter is immutable/thread-safe (SimpleDateFormat is not; write() is called from
    // the net, ping, push and UI threads concurrently)
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)

    /**
     * How many evicted entries a reader tolerates before it is told to rebuild instead of append.
     * Without it, a full buffer would evict one entry per new line and every update would be a
     * rebuild, exactly what appending exists to avoid. With it, a reader's view may hold up to
     * MAX_ENTRIES + this many entries between rebuilds.
     */
    private const val REBUILD_SLACK = 64

    /**
     * The class lock: the one monitor behind every `synchronized` method of this object and every
     * block that locks the class as a whole. Covers the in-memory ring and its counters.
     */
    private val classLock = Any()

    private val lines = ArrayDeque<String>(MAX_ENTRIES)
    private val listeners: MutableList<Listener> = CopyOnWriteArrayList()
    /** Volatile: set under the class lock by [init], read under [FILE_LOCK]. */
    @Volatile
    private var file: File? = null

    // ------------------------------------------------------------------ the open writer
    /**
     * Guards [out], [writtenLen] and [flushedLen]; the file side of this
     * class, and nothing else.
     *
     * A second lock rather than reusing the class lock, because they protect different things and
     * one of them touches the disk. Holding the class lock across the whole append would block every
     * other thread, including the UI's [read], for the duration of a file open, a stat, a
     * write and a close. So the class lock covers only the in-memory ring, and this one covers the
     * writer.
     *
     * **Lock order is always class lock first, then this one** ([flushNow] and [clear] are the
     * places that hold both). Nothing under this lock ever asks for the class lock.
     */
    private val FILE_LOCK = Any()
    /**
     * Held open, rather than opened per line: a fresh open, stat, write and close for every log call
     * is four syscalls and a path walk on whichever thread happened to log, and on the screen-on
     * broadcast, that is the main thread.
     */
    private var out: BufferedWriter? = null
    /** Bytes handed to [out] since the file was opened or rotated. */
    private var writtenLen = 0L
    /** Of those, how many have reached the disk. [loadedLen] may only follow this. */
    private var flushedLen = 0L
    /**
     * How long a line may sit in the writer's buffer.
     *
     * Not "never" and not "immediately". Never is wrong because this log's whole purpose is to
     * survive the process, and because the UI process reads the :sync process's lines out of this
     * very file, so an unflushed line is a line that never appears on the Log page. Immediately is
     * wrong because it is a syscall per line again. A third of a second is below what anybody
     * notices on a log tail and still batches a burst, such as a reload or a network change, into one
     * write.
     */
    private const val FLUSH_MS = 300L
    private var flusher: ScheduledExecutorService? = null
    /** A deferred flush is already queued; no need for another. */
    private var flushPending = false
    private var lastFlushAt = 0L

    private var loadedLen = -1L
    private var added = 0L          // entries ever pushed; never reset except by a rebuild
    private var evicted = 0L        // entries dropped off the head
    private var epoch = 0           // bumped whenever the buffer is thrown away and rebuilt

    /** A reader's place in the buffer. Single-threaded use (the UI thread). */
    class Cursor {
        internal var epoch = -1
        internal var seen = 0L
        internal var evicted = 0L
    }

    /** What a reader must do to catch up: either append these lines, or replace everything. */
    class Tail internal constructor(val replace: Boolean, val lines: List<String>) {
        fun isEmpty(): Boolean {
            return !replace && lines.isEmpty()
        }
    }

    /**
     * Advances `c` to the present and returns the work needed to match it. Appending is the
     * normal case; a replace is only asked for when the buffer was rebuilt or has dropped more
     * than [REBUILD_SLACK] entries since this reader last rebuilt.
     */
    fun read(c: Cursor): Tail {
        synchronized(classLock) {
            val rebuild = c.epoch != epoch || evicted - c.evicted >= REBUILD_SLACK || c.seen > added
            if (rebuild) {
                c.epoch = epoch
                c.evicted = evicted
                c.seen = added
                return Tail(true, ArrayList(lines))
            }
            val fresh = added - c.seen
            c.seen = added
            if (fresh <= 0) return Tail(false, java.util.List.of())
            if (fresh >= lines.size) return Tail(true, ArrayList(lines))
            val out = ArrayList<String>(fresh.toInt())
            val skip = lines.size - fresh.toInt()
            var i = 0
            for (l in lines) {
                if (i++ >= skip) out.add(l)
            }
            return Tail(false, out)
        }
    }

    /** Idempotent; loads the tail of the on-disk log into the buffer the first time. */
    fun init(ctx: Context) {
        synchronized(classLock) {
            if (file != null) return
            file = File(ctx.applicationContext.filesDir, "clipsync.log")
            reload()
        }
    }

    private fun reload() {
        lines.clear()
        epoch++
        added = 0
        evicted = 0
        val f = file ?: throw NullPointerException("log file not initialised")
        loadedLen = if (f.exists()) f.length() else 0L
        if (loadedLen == 0L) return
        try {
            BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { r ->
                while (true) {
                    val l = r.readLine() ?: break
                    push(l)
                }
            }
        } catch (ignored: IOException) {
        }
    }

    /**
     * The service writes the log from its own process (":sync"); the UI polls this to pick up new
     * lines. Only the bytes appended since the last call are read, so a new line costs one small
     * read and one entry, so readers can then append rather than rebuild. A file that shrank was
     * rotated or cleared, and is the one case that still reloads wholesale.
     *
     * Returns true when anything was added.
     */
    fun refresh(): Boolean {
        synchronized(classLock) {
            val f = file ?: return false
            val len = if (f.exists()) f.length() else 0L
            if (len == loadedLen) return false
            if (len < loadedLen) {
                reload()
                return true
            }
            val buf = ByteArray(Math.min(len - loadedLen, MAX_FILE_BYTES).toInt())
            try {
                FileInputStream(f).use { inp ->
                    val skipped = inp.skip(loadedLen)
                    if (skipped != loadedLen || inp.read(buf) != buf.size) return false
                }
            } catch (e: IOException) {
                return false
            }
            // stop at the last newline: the writer appends the text and the '\n' separately, so the
            // tail can be half a line, and that half must be left for the next call
            var end = buf.size
            while (end > 0 && buf[end - 1] != '\n'.code.toByte()) end--
            if (end == 0) return false
            for (l in String(buf, 0, end, StandardCharsets.UTF_8).split("\n")) {
                if (!l.isEmpty()) push(l)
            }
            loadedLen += end
            return true
        }
    }

    fun i(msg: String) { write("I", msg, null) }
    fun w(msg: String) { write("W", msg, null) }
    fun w(msg: String, t: Throwable) { write("W", msg, t) }

    private fun write(level: String, msg: String, t: Throwable?) {
        val sb = StringBuilder(LocalDateTime.now().format(TS)).append(' ').append(level).append(' ').append(msg)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            // trimmed with Java's rule (every character up to U+0020), as String.trim() defines it
            sb.append('\n').append(sw.toString().trim { it <= ' ' })
        }
        val line = sb.toString()
        // The ring first, and on its own lock. A reader that is only after the in-memory tail is
        // then never held up by the disk, which matters because one of the callers of this method
        // is the screen-on broadcast, on the main thread.
        synchronized(classLock) {
            push(line)
        }
        appendFile(line)
        for (l in listeners) l.onLine(line)
    }

    private fun push(line: String) {
        if (lines.size >= MAX_ENTRIES) {
            lines.pollFirst()
            evicted++
        }
        lines.addLast(line)
        added++
    }

    private fun appendFile(line: String) {
        var flushDue = false
        synchronized(FILE_LOCK) {
            if (file == null) return
            try {
                // Against our own counter, not file.length(): a stat per line is exactly the kind of
                // syscall the open writer exists to stop making, and the counter is authoritative
                // anyway, since we are the only writer of this file in this process.
                if (out == null || writtenLen > MAX_FILE_BYTES) openWriter()
                val w = out ?: return
                w.write(line)
                w.write('\n'.code)
                // UTF-8 is variable width, so this over- or under-counts for non-ASCII. It is a
                // rotation threshold and a "has anything new been flushed" marker, not an offset
                // into anything, and both tolerate being approximate. length rather than an
                // encode is the trade being made knowingly.
                writtenLen += (line.length + 1).toLong()
            } catch (e: IOException) {
                closeWriter()                  // a broken writer stays broken; reopen on the next line
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastFlushAt >= FLUSH_MS) {
                flushDue = true
            } else if (!flushPending) {
                flushPending = true
                scheduleFlush(FLUSH_MS - (now - lastFlushAt))
            }
        }
        // Outside the lock it needs, because it takes the class lock first and this method must
        // never be holding FILE_LOCK when that happens. See FILE_LOCK's note on lock order.
        if (flushDue) flushNow()
    }

    /**
     * Get the buffered lines onto the disk, and let [refresh] know they are ours.
     *
     * The second half is the subtle one. `refresh()` reads whatever the file has grown by
     * and pushes it into the ring, which is right for the :sync process's lines and wrong for our
     * own, since [write] has already pushed those. Advancing `loadedLen` past the bytes
     * we just flushed is what stops every line this process logs from appearing twice.
     *
     * Only as far as `flushedLen`, never as far as `writtenLen`: a `loadedLen`
     * ahead of the real file length reads as "the file shrank", which `refresh()` correctly
     * treats as a rotation and answers with a full reload.
     */
    private fun flushNow() {
        synchronized(classLock) {
            synchronized(FILE_LOCK) {
                flushPending = false
                lastFlushAt = System.currentTimeMillis()
                val w = out ?: return
                try {
                    w.flush()
                    flushedLen = writtenLen
                } catch (e: IOException) {
                    closeWriter()
                    return
                }
                if (loadedLen < flushedLen) loadedLen = flushedLen
            }
        }
    }

    /** One daemon thread, created on the first deferred flush and shared from then on. */
    private fun scheduleFlush(delayMs: Long) {
        val f: ScheduledExecutorService = flusher
            ?: Executors.newSingleThreadScheduledExecutor(ThreadFactory { r ->
                val t = Thread(r, "clipsync-log-flush")
                t.isDaemon = true
                t
            }).also { flusher = it }
        f.schedule(Runnable { flushNow() }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
    }

    /**
     * Caller holds [FILE_LOCK]. Closes any writer, rotates if the file is full, and opens a
     * new writer positioned at the end.
     *
     * Closing first is what makes the size test honest: a buffered writer's bytes are not in
     * `length()` until they are, and `close()` flushes. So the file on disk is complete
     * before it is measured, and complete before it is renamed, so a rotation cannot lose the tail.
     *
     * `loadedLen` is deliberately left alone across a rotation: leaving it above the new
     * file's length is exactly how [refresh] notices, and a reload is the right answer
     * there: the buffer's oldest entries are now in a file nothing reads any more.
     */
    @Throws(IOException::class)
    private fun openWriter() {
        closeWriter()
        val f = file ?: throw NullPointerException("log file not initialised")
        if (f.length() > MAX_FILE_BYTES) {
            val old = File(f.path + ".1")
            old.delete()
            f.renameTo(old)
        }
        out = BufferedWriter(FileWriter(f, true))
        writtenLen = f.length()
        flushedLen = writtenLen
    }

    private fun closeWriter() {
        val w = out
        out = null
        if (w == null) return
        try {
            w.close()
        } catch (ignored: IOException) {
        }
    }

    fun clear() {
        synchronized(classLock) {
            synchronized(FILE_LOCK) {
                lines.clear()
                epoch++                // readers rebuild rather than try to append onto nothing
                added = 0
                evicted = 0
                closeWriter()          // before the truncation, so no buffered line lands after it
                val f = file
                if (f != null) {
                    // Truncated, never deleted. The :sync process keeps its own append-mode writer
                    // on this file; a deleted file goes on receiving that writer's lines in an inode
                    // nothing can open, so the log would stay empty on screen until that process
                    // restarts. An append-mode writer always writes at the current end, so after a
                    // truncation its next line lands at the start of the emptied file, where
                    // refresh() finds it.
                    try {
                        java.io.FileOutputStream(f, false).use {
                            // opening without append is the truncation
                        }
                    } catch (e: IOException) {
                        // nothing to clear, or nothing that can be: the next write reopens it
                    }
                    File(f.path + ".1").delete()
                }
                loadedLen = 0
                writtenLen = 0
                flushedLen = 0
            }
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
}
