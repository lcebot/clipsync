package io.github.lcebot.clipsync;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps sha256 to the MediaStore URI of every file this app has stored (received, or re-used).
 * Answers OFFERs with HAVE when the content is already here, and is the ground truth for
 * housekeeping ("last used" is tracked here, since MediaStore dates are not ours to set).
 * Persisted as files/cache.json so it survives process death.
 *
 * <p>The digests are only ever computed once, when the content passes through, and are read back
 * from this file afterwards; nothing is ever re-hashed at start-up. (The Windows side keeps a
 * separate digest index in %TMP% to reach the same position: it owns a folder rather than a set of
 * MediaStore rows, so it cannot assume the folder only changes through it.)
 */
public final class FileCache {
    private static final String FILE = "cache.json";
    /** Shortest gap between two disk writes made only to refresh a "last used" stamp. */
    private static final long FLUSH_MS = 10_000;

    private static final class Entry {
        String uri, name, mime;
        long size, used;
    }

    private final Context ctx;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long lastStore;

    public FileCache(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.file = new File(this.ctx.getFilesDir(), FILE);
        load();
    }

    /**
     * What this cache is holding, for the startup line: {@code {bytes, count}}.
     *
     * <p>From the index rather than from the directory, deliberately: the index is what the budget
     * in {@link #prune} is spent against, so this is the number that explains a prune, and a
     * disagreement with the folder's real size is itself worth seeing.
     */
    public synchronized long[] usage() {
        long bytes = 0;
        for (Entry e : entries.values()) bytes += e.size;
        return new long[]{bytes, entries.size()};
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = in.readAllBytes();
            JSONObject root = new JSONObject(new String(b, StandardCharsets.UTF_8));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String sha = it.next();
                JSONObject o = root.getJSONObject(sha);
                Entry e = new Entry();
                e.uri = o.getString("uri");
                e.name = o.optString("name", "clip");
                e.mime = o.optString("mime", "application/octet-stream");
                e.size = o.optLong("size", 0);
                e.used = o.optLong("used", 0);
                entries.put(sha, e);
            }
        } catch (Exception e) {
            Logger.w("cache: cannot load index: " + e);
        }
    }

    /**
     * Writes the index. Never straight onto the live file: this process can be killed at any
     * moment, and a half-written cache.json is an index lost in full, since load() can only start
     * over from empty. {@link Files#atomicWrite} is that rule, shared with the configuration and the
     * status file rather than spelled out here a third time.
     */
    private synchronized void store() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                Entry e = me.getValue();
                root.put(me.getKey(), new JSONObject().put("uri", e.uri).put("name", e.name)
                        .put("mime", e.mime).put("size", e.size).put("used", e.used));
            }
            Files.atomicWrite(file,
                    out -> out.write(root.toString().getBytes(StandardCharsets.UTF_8)));
            lastStore = System.currentTimeMillis();
        } catch (Exception e) {
            Logger.w("cache: cannot save index: " + e);
        }
    }

    /**
     * For changes that are not worth a disk write of their own, currently only the "last used"
     * stamp, which get() bumps on every OFFER we can answer from the cache. Rewriting the whole
     * index each time put a synchronous file write on the network path to save a timestamp whose
     * only consumer is the ordering inside prune(). Losing the last few seconds of it to a kill
     * costs nothing: entries are still there, just fractionally staler in the LRU order.
     */
    private synchronized void storeSoon() {
        if (System.currentTimeMillis() - lastStore >= FLUSH_MS) store();
    }

    public synchronized void put(String sha, Uri uri, String name, String mime, long size) {
        Entry e = new Entry();
        e.uri = uri.toString();
        e.name = name;
        e.mime = mime;
        e.size = size;
        e.used = System.currentTimeMillis();
        entries.put(sha, e);
        store();
    }

    /** URI if we still have that content (verified readable), else null; marks it as used. */
    public synchronized Uri get(String sha) {
        Entry e = entries.get(sha);
        if (e == null) return null;
        Uri u = Uri.parse(e.uri);
        if (!readable(u)) {
            entries.remove(sha);
            store();
            return null;
        }
        e.used = System.currentTimeMillis();
        storeSoon();
        return u;
    }

    public synchronized String nameOf(String sha) {
        Entry e = entries.get(sha);
        return e == null ? null : e.name;
    }

    private boolean readable(Uri u) {
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete files unused for keepHours, then the least recently used until the total is under
     * keepMaxBytes. {@code keep} (the URI on the clipboard right now) is never removed.
     *
     * @param inFlight hashes currently being received or served, which {@link #prunePartials} must
     *                 leave alone. Prune runs on the completion of <em>some other</em> file, so
     *                 "old enough to delete" is a statement about the chunk map's mtime and says
     *                 nothing about whether a transfer is live; a large file arriving slowly has an
     *                 old map and an open stream, and deleting its pending row mid-transfer failed
     *                 the transfer that was going perfectly well.
     */
    public synchronized void prune(int keepHours, long keepMaxBytes, Uri keep, java.util.Set<String> inFlight) {
        if (keepHours <= 0 && keepMaxBytes <= 0) return;
        ContentResolver r = ctx.getContentResolver();
        List<Map.Entry<String, Entry>> rows = new ArrayList<>(entries.entrySet());
        rows.sort((a, b) -> Long.compare(a.getValue().used, b.getValue().used));      // LRU first
        long total = 0;
        for (Map.Entry<String, Entry> me : rows) total += me.getValue().size;
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Entry> me : rows) {
            Entry e = me.getValue();
            if (keep != null && keep.toString().equals(e.uri)) continue;
            boolean tooOld = keepHours > 0 && now - e.used > keepHours * 3600_000L;
            boolean tooBig = keepMaxBytes > 0 && total > keepMaxBytes;
            if (!(tooOld || tooBig)) continue;
            try {
                r.delete(Uri.parse(e.uri), null, null);       // 0 rows = user already removed it
            } catch (Exception ex) {
                Logger.i("prune: cannot delete " + e.uri + ": " + ex);
            }
            entries.remove(me.getKey());
            total -= e.size;
            removed++;
        }
        if (removed > 0) {
            store();
            Logger.i("prune: removed " + removed + " old file(s) from ClipSync folders");
        }
        prunePartials(keepHours, inFlight);
    }

    /** Interrupted transfers (files/partial/*.json + their pending rows) older than keepHours. */
    private void prunePartials(int keepHours, java.util.Set<String> inFlight) {
        if (keepHours <= 0) return;
        File dir = new File(ctx.getFilesDir(), "partial");
        File[] maps = dir.listFiles();
        if (maps == null) return;
        long cutoff = System.currentTimeMillis() - keepHours * 3600_000L;
        for (File m : maps) {
            if (m.lastModified() > cutoff) continue;
            // The map is named for the hash it belongs to, which is the only handle this class has
            // on "is somebody still using it".
            String sha = m.getName().endsWith(".json")
                    ? m.getName().substring(0, m.getName().length() - 5) : m.getName();
            if (inFlight != null && inFlight.contains(sha)) continue;
            try (InputStream in = new FileInputStream(m)) {
                JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                ctx.getContentResolver().delete(Uri.parse(o.getString("uri")), null, null);
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            m.delete();
            Logger.i("prune: dropped stale partial " + m.getName());
        }
    }
}
