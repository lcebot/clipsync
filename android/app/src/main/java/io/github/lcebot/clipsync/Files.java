package io.github.lcebot.clipsync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Clipboard URIs <-> chunks.  Nothing here holds a whole file in memory.
 *
 * <p>Sending: a clip item may carry a {@code content://} URI (image copied from the gallery, a
 * browser, a file manager, a screenshot's "copy"), rarely a {@code file://} one. We ask the
 * provider for name / size / MIME through the {@link OpenableColumns} contract, refuse anything
 * over the limit before reading it, hash it in one streaming pass, and later serve individual
 * chunks through {@link ChunkSource} (positional reads when the provider gives us a seekable
 * descriptor, a sequential skip otherwise).
 *
 * <p>Receiving: {@link Partial} assembles chunks — arriving on several connections, in any
 * order — into a pending MediaStore row under {@code Download/ClipSync} (clipboard content is
 * transient and must not litter Pictures/), with a persisted chunk map in files/partial/ so an
 * interrupted transfer resumes with only the missing chunks. Housekeeping lives in {@link FileCache}.
 */
public final class Files {
    private Files() {}

    private static final int CHUNK = Connection.CHUNK;

    /** What {@link #atomicWrite} hands a caller: the stream to fill, nothing else. */
    public interface Sink {
        void writeTo(FileOutputStream out) throws Exception;
    }

    /**
     * Replace a file's contents, or leave the old contents untouched. Never anything in between.
     *
     * <p>Three files needed this and only two of them had it, which is exactly the kind of drift a
     * rule written in a comment invites: {@code cache.json} and {@code status.json} wrote beside and
     * renamed, while {@code clipsync.conf} — the one that carries the PSK, and the one rewritten
     * most often — wrote in place. A process killed mid-write left a truncated key file, and the
     * service then refused to start until the user re-paired every device.
     *
     * <p>The {@code sync()} is not optional. A rename is atomic with respect to the directory, but
     * it says nothing about whether the new file's <em>blocks</em> have reached the disk; without
     * the flush a power loss can leave the rename durable and the contents not, which is the
     * failure the rename was supposed to make impossible.
     *
     * @param dst the file to replace; the temporary lives beside it, so both are on one filesystem
     *            and the rename cannot degrade into a copy
     */
    public static void atomicWrite(File dst, Sink body) throws IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            body.writeTo(out);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException(e);
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not replace " + dst.getName());
        }
    }

    /** A file we can send: where it is, what it is called, and its size + SHA-256. */
    public static final class Ref {
        public final Uri uri;
        public final String name, mime, sha256;
        public final long size;

        Ref(Uri uri, String name, String mime, long size, String sha256) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.sha256 = sha256;
        }

        @Override
        public String toString() {
            return mime + " " + name + " " + size + " bytes";
        }
    }

    /** Describe and hash a clip URI. Null (and a log line) if unreadable or over {@code maxBytes}. */
    public static Ref stat(Context ctx, Uri uri, long maxBytes) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        try {
            String name = null, mime = null;
            long size = -1;
            if (ContentResolver.SCHEME_FILE.equals(scheme)) {
                File f = new File(uri.getPath() == null ? "" : uri.getPath());
                if (!f.isFile()) { Logger.i("uri: not a file: " + uri); return null; }
                name = f.getName();
                size = f.length();
            } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                ContentResolver r = ctx.getContentResolver();
                try (Cursor c = r.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                    }
                } catch (Exception e) {
                    // some providers don't support query(); fall through and try to open anyway
                }
                mime = r.getType(uri);
                if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
            } else {
                Logger.i("uri: unsupported scheme " + scheme);
                return null;
            }
            if (size > maxBytes) { Logger.i("uri: " + size + " bytes > limit: " + name); return null; }
            if (name == null || name.isEmpty()) name = "clip";
            name = new File(name).getName();
            if (!name.contains(".") && mime != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext != null) name = name + "." + ext;
            }
            mime = mimeFor(mime, name);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = open(ctx, uri)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) { Logger.i("uri: more than " + maxBytes + " bytes, skipped: " + name); return null; }
                    md.update(buf, 0, n);
                }
            }
            return new Ref(uri, name, mime, total, hex(md.digest()));
        } catch (SecurityException e) {
            Logger.i("uri: no permission (" + e.getMessage() + "): " + uri);
        } catch (Exception e) {
            Logger.i("uri: cannot read " + uri + ": " + e);
        }
        return null;
    }

    public static InputStream open(Context ctx, Uri uri) throws IOException {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("provider returned no stream for " + uri);
        return in;
    }

    /** Random access to the chunks of a file we are sending. One per worker thread. */
    public static final class ChunkSource implements AutoCloseable {
        private final Context ctx;
        private final Uri uri;
        private final long size;
        private ParcelFileDescriptor pfd;
        private FileChannel ch;
        private InputStream seq;         // fallback: sequential stream
        private long seqPos;

        public ChunkSource(Context ctx, Ref ref) {
            this.ctx = ctx;
            this.uri = ref.uri;
            this.size = ref.size;
            try {
                pfd = ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                        ? ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                        : ctx.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            } catch (Exception e) {
                pfd = null;                       // pipe-backed provider: fall back to skipping
            }
        }

        /** Reads chunk {@code idx} into {@code buf}; returns its length. */
        public int read(int idx, byte[] buf) throws IOException {
            long pos = (long) idx * CHUNK;
            int want = (int) Math.min(CHUNK, size - pos);
            if (ch != null) {
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, want);
                while (bb.hasRemaining()) {
                    if (ch.read(bb, pos + bb.position()) < 0) throw new IOException("short read at chunk " + idx);
                }
                return want;
            }
            if (seq == null || seqPos > pos) {
                if (seq != null) seq.close();
                seq = open(ctx, uri);
                seqPos = 0;
            }
            while (seqPos < pos) {
                long s = seq.skip(pos - seqPos);
                if (s <= 0) { if (seq.read() < 0) throw new IOException("short read"); s = 1; }
                seqPos += s;
            }
            int got = 0;
            while (got < want) {
                int n = seq.read(buf, got, want - got);
                if (n < 0) throw new IOException("short read at chunk " + idx);
                got += n;
            }
            seqPos += got;
            return want;
        }

        @Override
        public void close() {
            try { if (ch != null) ch.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            try { if (seq != null) seq.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * An inbound file being assembled from CHUNKs.  Backed by a pending MediaStore row (written
     * positionally through its file descriptor) and a map file listing the chunks received, so
     * the transfer survives a lost connection, an ABORT or a process restart.
     */
    public static final class Partial {
        public final String sha256, name, mime;
        public final long size, seq;
        public final int n;
        private final Context ctx;
        private final Uri uri;
        private final File map;
        private final BitSet have;
        private ParcelFileDescriptor pfd;
        private FileChannel ch;
        private int unsaved;
        private boolean finalized;
        /** @see #claimFirstChunk() */
        private boolean firstChunkClaimed;
        /** @see #claimReask(int) */
        private int reasks;

        private static File mapFile(Context ctx, String sha) {
            File dir = new File(ctx.getFilesDir(), "partial");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            return new File(dir, sha + ".json");
        }

        /** Resume an earlier transfer of this hash if its map and MediaStore row still exist. */
        public static Partial resume(Context ctx, String sha) {
            File map = mapFile(ctx, sha);
            if (!map.exists()) return null;
            try (InputStream in = new FileInputStream(map)) {
                JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                Partial p = new Partial(ctx, Uri.parse(o.getString("uri")), o.getString("name"), o.getString("mime"),
                        o.getLong("size"), sha, o.optLong("seq", 0), map);
                JSONArray a = o.optJSONArray("have");
                if (a != null) for (int i = 0; i < a.length(); i++) p.have.set(a.getInt(i));
                p.openChannel();                                    // throws if the row is gone
                return p;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                map.delete();
                return null;
            }
        }

        /**
         * Start a new transfer: create the pending row under {@code relativePath} ("Download/ClipSync",
         * "Documents/…") and pre-size it. The generic Files collection accepts any MIME type there.
         */
        public static Partial create(Context ctx, String relativePath, String name, String mime, long size, String sha, long seq) throws Exception {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri coll = relativePath.startsWith("Download")
                    ? MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = ctx.getContentResolver().insert(coll, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            Partial p = new Partial(ctx, uri, name, mime, size, sha, seq, mapFile(ctx, sha));
            p.openChannel();
            if (size > 0 && p.ch.size() < size) p.ch.write(ByteBuffer.wrap(new byte[1]), size - 1);   // pre-size
            p.saveMap();
            return p;
        }

        private Partial(Context ctx, Uri uri, String name, String mime, long size, String sha, long seq, File map) {
            this.ctx = ctx;
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.sha256 = sha;
            this.seq = seq;
            this.map = map;
            this.n = Connection.chunks(size);
            this.have = new BitSet(n);
        }

        private void openChannel() throws IOException {
            if (ch != null) return;
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IOException("cannot open " + uri);
            ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
        }

        public synchronized List<int[]> missing() {
            List<int[]> out = new ArrayList<>();
            int i = have.nextClearBit(0);
            while (i < n) {
                int j = have.nextSetBit(i);
                if (j < 0 || j > n) j = n;
                out.add(new int[]{i, j});
                i = have.nextClearBit(j);
            }
            return out;
        }

        public synchronized int haveCount() { return have.cardinality(); }
        public synchronized boolean complete() { return have.cardinality() == n; }

        /** Has this file already been verified and published? {@code finalizeFile} is idempotent, but
         *  the re-ask path has to be able to ask without side effects. */
        public synchronized boolean isFinalized() { return finalized; }

        /**
         * "I may send one more WANT for this file" — the re-ask budget, spent one call at a time.
         *
         * <p>Named and counted here rather than in {@link FileExchange} because the budget belongs to
         * the file, not to the connection that happens to be carrying it: a transfer that is picked
         * up by a second peer offering the same hash must not get a fresh three re-asks for the same
         * stall. Python keeps it in the same place and for the same reason — {@code Partial.retries},
         * tested against {@code WANT_RETRIES} inside {@code SyncState._reask}.
         *
         * <p>Test and increment are one operation on the Partial's own monitor for the same reason
         * {@link #claimFirstChunk()} is: several streams of one file end together, and two re-ask
         * timers that both read "retries == 2" would both send.
         *
         * @param limit {@code FileExchange.WANT_RETRIES}, passed in so the constant has one home.
         * @return true if the caller may send the WANT; false when the budget is spent.
         */
        public synchronized boolean claimReask(int limit) {
            if (reasks >= limit) return false;
            reasks++;
            return true;
        }

        /**
         * A fresh OFFER for this file arrived and was answered with a WANT: the peer is driving it
         * again, so the budget starts over. Mirrors {@code pt.retries = 0} in Python's
         * {@code _want_from_peer} — without it a file that stalls once an hour is unresumable after
         * the third hour, because the counter is in the Partial and the Partial survives on disk.
         */
        public synchronized void resetReasks() { reasks = 0; }

        /**
         * "Nothing had landed yet, and I am the one who gets to say so" — asked once, by whoever
         * writes the first chunk of this file, and answered true to exactly one caller.
         *
         * <p>It exists because {@code haveCount() == 0} followed by {@link #write} is <em>two</em>
         * operations: several streams carry one file, they all reach the first write at once, and
         * every one of them can see an empty BitSet before any of them has filled it. Whoever it is
         * answered true then sends the relay's early OFFER, so "two threads both saw zero" means one
         * waiter gets N identical OFFERs for one file. Folding the test and the flag into one
         * method on the monitor that {@code write} already holds makes that unrepresentable.
         *
         * <p>The pull side has always enforced this, as {@code Transfer.firstChunkFired} — which is
         * now this method, called from {@link Transfer}'s chunk callback. One rule with one
         * implementation, because the last time it had two only one of them was right: the push
         * path in {@code FileExchange.serveData} had no guard at all. Python's
         * {@code Partial.claim_first_chunk} is the same method for the same reason.
         *
         * <p>Cleared by {@link #keep()}: a transfer that stopped and is resumed later must be able
         * to announce its first chunk again, or a file whose first attempt died before a single
         * chunk landed would never be offered to a waiter at all.
         */
        public synchronized boolean claimFirstChunk() {
            if (firstChunkClaimed || !have.isEmpty()) return false;
            firstChunkClaimed = true;
            return true;
        }

        public synchronized void write(int idx, byte[] data, int off, int len) throws IOException {
            if (idx < 0 || idx >= n) throw new IOException("chunk " + idx + " out of range");
            long expect = idx < n - 1 ? CHUNK : size - (long) idx * CHUNK;
            if (len != expect) throw new IOException("chunk " + idx + ": " + len + " bytes, expected " + expect);
            if (have.get(idx)) return;
            openChannel();
            ByteBuffer bb = ByteBuffer.wrap(data, off, len);
            long pos = (long) idx * CHUNK;
            while (bb.hasRemaining()) pos += ch.write(bb, pos);
            have.set(idx);
            // wake the threads forwarding this file to a LAN peer, which block per chunk until the
            // chunk they owe it has landed here
            notifyAll();
            if (++unsaved >= 8) saveMap();
        }

        /** Whether chunk {@code idx} has been received. Thread-safe. */
        public synchronized boolean hasChunk(int idx) {
            return idx >= 0 && idx < n && have.get(idx);
        }

        /**
         * Read chunk {@code idx} into {@code buf}; returns its length.
         *
         * <p>For forwarding a file while it is still arriving: it is being written by another set of
         * threads, but
         * chunks occupy disjoint ranges, so a chunk that {@link #hasChunk} reports as present is safe
         * to read.  Opens a separate read descriptor each call — not the fastest path, but correct
         * without sharing the write channel's fd ownership, and the overhead is negligible next to
         * the 512 KiB chunk on the wire.
         */
        public synchronized int readChunk(int idx, byte[] buf) throws IOException {
            if (!have.get(idx)) throw new IOException("chunk " + idx + " not received yet");
            long pos = (long) idx * CHUNK;
            int want = (int) Math.min(CHUNK, size - pos);
            try (ParcelFileDescriptor rpfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                if (rpfd == null) throw new IOException("cannot open " + uri + " for reading");
                java.nio.channels.FileChannel rch = new java.io.FileInputStream(rpfd.getFileDescriptor()).getChannel();
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, want);
                while (bb.hasRemaining()) {
                    if (rch.read(bb, pos + bb.position()) < 0) throw new IOException("short read at chunk " + idx);
                }
            }
            return want;
        }

        private void saveMap() throws IOException {
            JSONArray a = new JSONArray();
            for (int i = have.nextSetBit(0); i >= 0; i = have.nextSetBit(i + 1)) a.put(i);
            String s;
            try {
                s = new JSONObject().put("uri", uri.toString()).put("name", name).put("mime", mime)
                        .put("size", size).put("seq", seq).put("have", a).toString();
            } catch (Exception e) {
                throw new IOException(e);
            }
            File tmp = new File(map.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(map)) throw new IOException("cannot save chunk map");
            unsaved = 0;
        }

        /** Verify the whole file, publish the row, drop the map. Returns the final URI. */
        public synchronized Uri finalizeFile() throws Exception {
            if (finalized) return uri;
            closeChannel();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot re-open " + uri);
                byte[] buf = new byte[64 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
            }
            if (!hex(md.digest()).equals(sha256)) {
                discard();
                throw new IOException("hash mismatch after reassembly");
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            //noinspection ResultOfMethodCallIgnored
            map.delete();
            finalized = true;
            return uri;
        }

        /** Stop for now; everything stays on disk for a later resume. */
        public synchronized void keep() {
            try { if (!finalized) saveMap(); } catch (IOException ignored) {}
            closeChannel();
            firstChunkClaimed = false;      // a resumed transfer may announce its first chunk again
        }

        public synchronized void discard() {
            closeChannel();
            try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            //noinspection ResultOfMethodCallIgnored
            map.delete();
        }

        private void closeChannel() {
            try { if (ch != null) ch.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            ch = null;
            pfd = null;
        }

        @Override
        public String toString() {
            return name + " " + haveCount() + "/" + n + " chunks";
        }
    }

    private static String mimeFor(String declared, String name) {
        if (declared != null && !declared.isEmpty() && !declared.equals("*/*")) return declared;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
