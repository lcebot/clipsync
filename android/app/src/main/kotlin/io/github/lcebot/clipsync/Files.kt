package io.github.lcebot.clipsync

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet
import java.util.Locale

/**
 * Converts between clipboard URIs and chunks.  Nothing here holds a whole file in memory.
 *
 * Sending: a clip item may carry a `content://` URI (image copied from the gallery, a
 * browser, a file manager, a screenshot's "copy"), rarely a `file://` one. We ask the
 * provider for name / size / MIME through the [OpenableColumns] contract, refuse anything
 * over the limit before reading it, hash it in one streaming pass, and later serve individual
 * chunks through [ChunkSource] (positional reads when the provider gives us a seekable
 * descriptor, a sequential skip otherwise).
 *
 * Receiving: [Partial] assembles chunks, which arrive on several connections in any
 * order, into a pending MediaStore row under `Download/ClipSync` (clipboard content is
 * transient and must not litter Pictures/), with a persisted chunk map in files/partial/ so an
 * interrupted transfer resumes with only the missing chunks. Housekeeping lives in [FileCache].
 */
object Files {
    private const val CHUNK = Connection.CHUNK

    /** What [atomicWrite] hands a caller: the stream to fill, nothing else. */
    fun interface Sink {
        @Throws(Exception::class)
        fun writeTo(out: FileOutputStream)
    }

    /**
     * Replace a file's contents, or leave the old contents untouched. Never anything in between.
     *
     * Used by every file that must not be left half-written if the process dies mid-save:
     * `cache.json`, `status.json`, and `clipsync.conf`, which carries the PSK and
     * is rewritten most often: a truncated key file would leave the service unable to start until
     * the user re-paired every device.
     *
     * The `sync()` is not optional. A rename is atomic with respect to the directory, but
     * it says nothing about whether the new file's *blocks* have reached the disk; without
     * the flush a power loss can leave the rename durable and the contents not, which is the
     * failure the rename is meant to prevent.
     *
     * @param dst the file to replace; the temporary lives beside it, so both are on one filesystem
     *            and the rename cannot degrade into a copy
     */
    @Throws(IOException::class)
    fun atomicWrite(dst: File, body: Sink) {
        val tmp = File(dst.path + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                body.writeTo(out)
                out.flush()
                out.fd.sync()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw IOException(e)
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("could not replace " + dst.name)
        }
    }

    /** A file we can send: where it is, what it is called, and its size + SHA-256. */
    class Ref internal constructor(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
    ) {
        override fun toString(): String {
            return "$mime $name $size bytes"
        }
    }

    /** Describe and hash a clip URI. Null (and a log line) if unreadable or over `maxBytes`. */
    fun stat(ctx: Context, uri: Uri, maxBytes: Long): Ref? {
        val scheme = uri.scheme ?: ""
        try {
            var name: String? = null
            var mime: String? = null
            var size = -1L
            if (ContentResolver.SCHEME_FILE == scheme) {
                val f = File(uri.path ?: "")
                if (!f.isFile) { Logger.i("uri: not a file: $uri"); return null }
                name = f.name
                size = f.length()
            } else if (ContentResolver.SCHEME_CONTENT == scheme) {
                val r = ctx.contentResolver
                try {
                    r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        .use { c ->
                            if (c != null && c.moveToFirst()) {
                                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val si = c.getColumnIndex(OpenableColumns.SIZE)
                                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                            }
                        }
                } catch (e: Exception) {
                    // some providers don't support query(); fall through and try to open anyway
                }
                mime = r.getType(uri)
                if (name.isNullOrEmpty()) name = uri.lastPathSegment
            } else {
                Logger.i("uri: unsupported scheme $scheme")
                return null
            }
            if (size > maxBytes) { Logger.i("uri: $size bytes > limit: $name"); return null }
            val named = name
            var n0: String = if (named.isNullOrEmpty()) "clip" else named
            n0 = File(n0).name
            if (!n0.contains(".") && mime != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (ext != null) n0 = "$n0.$ext"
            }
            val finalMime = mimeFor(mime, n0)
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open(ctx, uri).use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) { Logger.i("uri: more than $maxBytes bytes, skipped: $n0"); return null }
                    md.update(buf, 0, n)
                }
            }
            return Ref(uri, n0, finalMime, total, hex(md.digest()))
        } catch (e: SecurityException) {
            Logger.i("uri: no permission (" + e.message + "): " + uri)
        } catch (e: Exception) {
            Logger.i("uri: cannot read $uri: $e")
        }
        return null
    }

    @Throws(IOException::class)
    fun open(ctx: Context, uri: Uri): InputStream {
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            return FileInputStream(uri.path ?: throw NullPointerException("uri has no path"))
        }
        val inp = ctx.contentResolver.openInputStream(uri)
        if (inp == null) throw IOException("provider returned no stream for $uri")
        return inp
    }

    /** Random access to the chunks of a file we are sending. One per worker thread. */
    class ChunkSource(ctx: Context, ref: Ref) : AutoCloseable {
        private val ctx: Context
        private val uri: Uri
        private val size: Long
        private var pfd: ParcelFileDescriptor? = null
        private var ch: FileChannel? = null
        private var seq: InputStream? = null   // fallback: sequential stream
        private var seqPos = 0L

        init {
            this.ctx = ctx
            this.uri = ref.uri
            this.size = ref.size
            try {
                pfd = if (ContentResolver.SCHEME_FILE == uri.scheme)
                    ParcelFileDescriptor.open(
                        File(uri.path ?: throw NullPointerException("uri has no path")),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                else ctx.contentResolver.openFileDescriptor(uri, "r")
                val p = pfd
                if (p != null) ch = FileInputStream(p.fileDescriptor).channel
            } catch (e: Exception) {
                pfd = null                       // pipe-backed provider: fall back to skipping
            }
        }

        /** Reads chunk `idx` into `buf`; returns its length. */
        @Throws(IOException::class)
        fun read(idx: Int, buf: ByteArray): Int {
            val pos = idx.toLong() * CHUNK
            val want = Math.min(CHUNK.toLong(), size - pos).toInt()
            val c = ch
            if (c != null) {
                val bb = ByteBuffer.wrap(buf, 0, want)
                while (bb.hasRemaining()) {
                    if (c.read(bb, pos + bb.position()) < 0) throw IOException("short read at chunk $idx")
                }
                return want
            }
            // The sequential stream is only trusted while its position is known. Any failure below,
            // opening included, drops it, so the next read reopens from the start instead of reading
            // a closed stream or one that stopped at an unknown offset.
            try {
                val cur = seq
                val s: InputStream = if (cur == null || seqPos > pos) {
                    seq = null
                    seqPos = 0
                    cur?.close()
                    val opened = open(ctx, uri)
                    seq = opened
                    opened
                } else cur
                while (seqPos < pos) {
                    var k = s.skip(pos - seqPos)
                    if (k <= 0) { if (s.read() < 0) throw IOException("short read"); k = 1 }
                    seqPos += k
                }
                var got = 0
                while (got < want) {
                    val n = s.read(buf, got, want - got)
                    if (n < 0) throw IOException("short read at chunk $idx")
                    got += n
                }
                seqPos += got
                return want
            } catch (e: Exception) {
                val broken = seq
                seq = null
                seqPos = 0
                try {
                    broken?.close()
                } catch (ignored: IOException) {
                }
                throw e
            }
        }

        override fun close() {
            try { ch?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
            try { seq?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * An inbound file being assembled from CHUNKs.  Backed by a pending MediaStore row (written
     * positionally through its file descriptor) and a map file listing the chunks received, so
     * the transfer survives a lost connection, an ABORT or a process restart.
     */
    class Partial private constructor(
        private val ctx: Context,
        private val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        sha: String,
        val seq: Long,
        private val map: File,
    ) {
        val sha256: String = sha
        val n: Int = Connection.chunks(size)
        private val have: BitSet = BitSet(n)
        private var pfd: ParcelFileDescriptor? = null
        private var ch: FileChannel? = null
        private var unsaved = 0
        private var finalized = false
        /** See [claimFirstChunk]. */
        private var firstChunkClaimed = false
        /** See [claimReask]. */
        private var reasks = 0

        companion object {
            private fun mapFile(ctx: Context, sha: String): File {
                val dir = File(ctx.filesDir, "partial")
                dir.mkdirs()
                return File(dir, "$sha.json")
            }

            /** Resume an earlier transfer of this hash if its map and MediaStore row still exist. */
            fun resume(ctx: Context, sha: String): Partial? {
                val map = mapFile(ctx, sha)
                if (!map.exists()) return null
                return try {
                    FileInputStream(map).use { inp ->
                        val o = JSONObject(String(inp.readAllBytes(), StandardCharsets.UTF_8))
                        val p = Partial(
                            ctx, Uri.parse(o.getString("uri")), o.getString("name"), o.getString("mime"),
                            o.getLong("size"), sha, o.optLong("seq", 0), map,
                        )
                        val a = o.optJSONArray("have")
                        if (a != null) for (i in 0 until a.length()) p.have.set(a.getInt(i))
                        p.openChannel()                                    // throws if the row is gone
                        p
                    }
                } catch (e: Exception) {
                    map.delete()
                    null
                }
            }

            /**
             * Start a new transfer: create the pending row under `relativePath` ("Download/ClipSync",
             * "Documents/…") and pre-size it. The generic Files collection accepts any MIME type there.
             */
            @Throws(Exception::class)
            fun create(
                ctx: Context, relativePath: String, name: String, mime: String, size: Long, sha: String, seq: Long,
            ): Partial {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                v.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val coll = if (relativePath.startsWith("Download"))
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(coll, v) ?: throw IOException("MediaStore insert failed")
                val p = Partial(ctx, uri, name, mime, size, sha, seq, mapFile(ctx, sha))
                p.openChannel()
                val c = p.ch ?: throw NullPointerException("channel not open")
                if (size > 0 && c.size() < size) c.write(ByteBuffer.wrap(ByteArray(1)), size - 1)   // pre-size
                p.saveMap()
                return p
            }
        }

        @Throws(IOException::class)
        private fun openChannel() {
            if (ch != null) return
            val p = ctx.contentResolver.openFileDescriptor(uri, "rw")
            pfd = p
            if (p == null) throw IOException("cannot open $uri")
            ch = FileOutputStream(p.fileDescriptor).channel
        }

        @Synchronized
        fun missing(): MutableList<IntArray> {
            val out = ArrayList<IntArray>()
            var i = have.nextClearBit(0)
            while (i < n) {
                var j = have.nextSetBit(i)
                if (j < 0 || j > n) j = n
                out.add(intArrayOf(i, j))
                i = have.nextClearBit(j)
            }
            return out
        }

        @Synchronized
        fun haveCount(): Int = have.cardinality()

        @Synchronized
        fun complete(): Boolean = have.cardinality() == n

        /** Has this file already been verified and published? `finalizeFile` is idempotent, but
         *  the re-ask path has to be able to ask without side effects. */
        @Synchronized
        fun isFinalized(): Boolean = finalized

        /**
         * "I may send one more WANT for this file": the re-ask budget, spent one call at a time.
         *
         * Named and counted here rather than in [FileExchange] because the budget belongs to
         * the file, not to the connection that happens to be carrying it: a transfer that is picked
         * up by a second peer offering the same hash must not get a fresh three re-asks for the same
         * stall. Python keeps it in the same place and for the same reason: `Partial.retries`,
         * tested against `WANT_RETRIES` inside `SyncState._reask`.
         *
         * Test and increment are one operation on the Partial's own monitor for the same reason
         * [claimFirstChunk] is: several streams of one file end together, and two re-ask
         * timers that both read "retries == 2" would both send.
         *
         * @param limit `FileExchange.WANT_RETRIES`, passed in so the constant has one home.
         * @return true if the caller may send the WANT; false when the budget is spent.
         */
        @Synchronized
        fun claimReask(limit: Int): Boolean {
            if (reasks >= limit) return false
            reasks++
            return true
        }

        /**
         * A fresh OFFER for this file arrived and was answered with a WANT: the peer is driving it
         * again, so the budget starts over. Mirrors `pt.retries = 0` in Python's
         * `_want_from_peer`; without it a file that stalls once an hour is unresumable after
         * the third hour, because the counter is in the Partial and the Partial survives on disk.
         */
        @Synchronized
        fun resetReasks() { reasks = 0 }

        /**
         * "Nothing had landed yet, and I am the one who gets to say so": asked once, by whoever
         * writes the first chunk of this file, and answered true to exactly one caller.
         *
         * It exists because `haveCount() == 0` followed by [write] is *two*
         * operations: several streams carry one file, they all reach the first write at once, and
         * every one of them can see an empty BitSet before any of them has filled it. Whoever it is
         * answered true then sends the relay's early OFFER, so "two threads both saw zero" means one
         * waiter gets N identical OFFERs for one file. Folding the test and the flag into one
         * method on the monitor that `write` already holds makes that unrepresentable.
         *
         * Both the push and pull paths call this one method, the pull side from [Transfer]'s
         * chunk callback, so the guard is enforced identically regardless of which direction the
         * file is moving. Python's `Partial.claim_first_chunk` is the same method for the same
         * reason.
         *
         * Cleared by [keep]: a transfer that stopped and is resumed later must be able
         * to announce its first chunk again, or a file whose first attempt died before a single
         * chunk landed would never be offered to a waiter at all.
         */
        @Synchronized
        fun claimFirstChunk(): Boolean {
            if (firstChunkClaimed || !have.isEmpty) return false
            firstChunkClaimed = true
            return true
        }

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        @Synchronized
        @Throws(IOException::class)
        fun write(idx: Int, data: ByteArray, off: Int, len: Int) {
            if (idx < 0 || idx >= n) throw IOException("chunk $idx out of range")
            val expect: Long = if (idx < n - 1) CHUNK.toLong() else size - idx.toLong() * CHUNK
            if (len.toLong() != expect) throw IOException("chunk $idx: $len bytes, expected $expect")
            if (have.get(idx)) return
            openChannel()
            val c = ch ?: throw NullPointerException("channel not open")
            val bb = ByteBuffer.wrap(data, off, len)
            var pos = idx.toLong() * CHUNK
            while (bb.hasRemaining()) pos += c.write(bb, pos)
            have.set(idx)
            // wake the threads forwarding this file to a LAN peer, which block per chunk until the
            // chunk they owe it has landed here
            (this as java.lang.Object).notifyAll()
            if (++unsaved >= 8) saveMap()
        }

        /** Whether chunk `idx` has been received. Thread-safe. */
        @Synchronized
        fun hasChunk(idx: Int): Boolean {
            return idx >= 0 && idx < n && have.get(idx)
        }

        /**
         * Read chunk `idx` into `buf`; returns its length.
         *
         * For forwarding a file while it is still arriving: it is being written by another set of
         * threads, but
         * chunks occupy disjoint ranges, so a chunk that [hasChunk] reports as present is safe
         * to read.  Opens a separate read descriptor each call; not the fastest path, but correct
         * without sharing the write channel's fd ownership, and the overhead is negligible next to
         * the 512 KiB chunk on the wire.
         */
        @Synchronized
        @Throws(IOException::class)
        fun readChunk(idx: Int, buf: ByteArray): Int {
            if (!have.get(idx)) throw IOException("chunk $idx not received yet")
            val pos = idx.toLong() * CHUNK
            val want = Math.min(CHUNK.toLong(), size - pos).toInt()
            ctx.contentResolver.openFileDescriptor(uri, "r").use { rpfd ->
                if (rpfd == null) throw IOException("cannot open $uri for reading")
                val rch: FileChannel = FileInputStream(rpfd.fileDescriptor).channel
                val bb = ByteBuffer.wrap(buf, 0, want)
                while (bb.hasRemaining()) {
                    if (rch.read(bb, pos + bb.position()) < 0) throw IOException("short read at chunk $idx")
                }
            }
            return want
        }

        @Throws(IOException::class)
        private fun saveMap() {
            val a = JSONArray()
            var i = have.nextSetBit(0)
            while (i >= 0) {
                a.put(i)
                i = have.nextSetBit(i + 1)
            }
            val s: String = try {
                JSONObject().put("uri", uri.toString()).put("name", name).put("mime", mime)
                    .put("size", size).put("seq", seq).put("have", a).toString()
            } catch (e: Exception) {
                throw IOException(e)
            }
            val tmp = File(map.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(s.toByteArray(StandardCharsets.UTF_8))
            }
            if (!tmp.renameTo(map)) throw IOException("cannot save chunk map")
            unsaved = 0
        }

        /** Verify the whole file, publish the row, drop the map. Returns the final URI. */
        @Synchronized
        @Throws(Exception::class)
        fun finalizeFile(): Uri {
            if (finalized) return uri
            closeChannel()
            val md = MessageDigest.getInstance("SHA-256")
            ctx.contentResolver.openInputStream(uri).use { inp ->
                if (inp == null) throw IOException("cannot re-open $uri")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val k = inp.read(buf)
                    if (k <= 0) break
                    md.update(buf, 0, k)
                }
            }
            if (hex(md.digest()) != sha256) {
                discard()
                throw IOException("hash mismatch after reassembly")
            }
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, v, null, null)
            map.delete()
            finalized = true
            return uri
        }

        /** Stop for now; everything stays on disk for a later resume. */
        @Synchronized
        fun keep() {
            try { if (!finalized) saveMap() } catch (ignored: IOException) {}
            closeChannel()
            firstChunkClaimed = false      // a resumed transfer may announce its first chunk again
        }

        @Synchronized
        fun discard() {
            closeChannel()
            try { ctx.contentResolver.delete(uri, null, null) } catch (ignored: Exception) {}
            map.delete()
        }

        private fun closeChannel() {
            try { ch?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
            ch = null
            pfd = null
        }

        override fun toString(): String {
            return name + " " + haveCount() + "/" + n + " chunks"
        }
    }

    private fun mimeFor(declared: String?, name: String): String {
        if (declared != null && !declared.isEmpty() && declared != "*/*") return declared
        val dot = name.lastIndexOf('.')
        if (dot >= 0) {
            val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).lowercase(Locale.ROOT))
            if (m != null) return m
        }
        return "application/octet-stream"
    }

    internal fun hex(d: ByteArray): String {
        val sb = StringBuilder(d.size * 2)
        for (x in d) sb.append(String.format("%02x", x))
        return sb.toString()
    }
}
