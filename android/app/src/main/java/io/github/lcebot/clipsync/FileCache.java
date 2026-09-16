package io.github.lcebot.clipsync;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sha256 -> MediaStore URI of every file this app has stored (received, or re-used).
 * Answers OFFERs with HAVE when the content is already here, and is the ground truth for
 * housekeeping ("last used" is tracked here, since MediaStore dates are not ours to set).
 * Persisted as files/cache.json so it survives process death.
 */
public final class FileCache {
    private static final String FILE = "cache.json";

    private static final class Entry {
        String uri, name, mime;
        long size, used;
    }

    private final Context ctx;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FileCache(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.file = new File(this.ctx.getFilesDir(), FILE);
        load();
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

    private synchronized void store() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                Entry e = me.getValue();
                root.put(me.getKey(), new JSONObject().put("uri", e.uri).put("name", e.name)
                        .put("mime", e.mime).put("size", e.size).put("used", e.used));
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.w("cache: cannot save index: " + e);
        }
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
        store();
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
     */
    public synchronized void prune(int keepHours, long keepMaxBytes, Uri keep) {
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
        prunePartials(keepHours);
    }

    /** Interrupted transfers (files/partial/*.json + their pending rows) older than keepHours. */
    private void prunePartials(int keepHours) {
        if (keepHours <= 0) return;
        File dir = new File(ctx.getFilesDir(), "partial");
        File[] maps = dir.listFiles();
        if (maps == null) return;
        long cutoff = System.currentTimeMillis() - keepHours * 3600_000L;
        for (File m : maps) {
            if (m.lastModified() > cutoff) continue;
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
