package io.github.lcebot.clipsync

import android.content.Context
import android.net.Uri

import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Maps sha256 to the MediaStore URI of every file this app has stored (received, or re-used).
 * Answers OFFERs with HAVE when the content is already here, and is the ground truth for
 * housekeeping ("last used" is tracked here, since MediaStore dates are not ours to set).
 * Persisted as files/cache.json so it survives process death.
 *
 * The digests are only ever computed once, when the content passes through, and are read back
 * from this file afterwards; nothing is ever re-hashed at start-up. (The Windows side keeps a
 * separate digest index in %TMP% to reach the same position: it owns a folder rather than a set of
 * MediaStore rows, so it cannot assume the folder only changes through it.)
 */
class FileCache(ctx: Context) {
    companion object {
        private const val FILE = "cache.json"
        /** Shortest gap between two disk writes made only to refresh a "last used" stamp. */
        private const val FLUSH_MS = 10_000L
    }

    private class Entry {
        lateinit var uri: String
        lateinit var name: String
        lateinit var mime: String
        var size = 0L
        var used = 0L
    }

    private val ctx: Context = ctx.applicationContext
    private val file: File = File(this.ctx.filesDir, FILE)
    private val entries: MutableMap<String, Entry> = LinkedHashMap()
    private var lastStore = 0L

    init {
        load()
    }

    /**
     * What this cache is holding, for the startup line: `{bytes, count}`.
     *
     * From the index rather than from the directory, deliberately: the index is what the budget
     * in [prune] is spent against, so this is the number that explains a prune, and a
     * disagreement with the folder's real size is itself worth seeing.
     */
    @Synchronized
    fun usage(): LongArray {
        var bytes = 0L
        for (e in entries.values) bytes += e.size
        return longArrayOf(bytes, entries.size.toLong())
    }

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        try {
            FileInputStream(file).use { inp ->
                val b = inp.readAllBytes()
                val root = JSONObject(String(b, StandardCharsets.UTF_8))
                val it = root.keys()
                while (it.hasNext()) {
                    val sha = it.next()
                    val o = root.getJSONObject(sha)
                    val e = Entry()
                    e.uri = o.getString("uri")
                    e.name = o.optString("name", "clip")
                    e.mime = o.optString("mime", "application/octet-stream")
                    e.size = o.optLong("size", 0)
                    e.used = o.optLong("used", 0)
                    entries[sha] = e
                }
            }
        } catch (e: Exception) {
            Logger.w("cache: cannot load index: $e")
        }
    }

    /**
     * Writes the index. Never straight onto the live file: this process can be killed at any
     * moment, and a half-written cache.json is an index lost in full, since load() can only start
     * over from empty. [Files.atomicWrite] is that rule, shared with the configuration and the
     * status file rather than spelled out here a third time.
     */
    @Synchronized
    private fun store() {
        try {
            val root = JSONObject()
            for (me in entries.entries) {
                val e = me.value
                root.put(
                    me.key,
                    JSONObject().put("uri", e.uri).put("name", e.name)
                        .put("mime", e.mime).put("size", e.size).put("used", e.used),
                )
            }
            Files.atomicWrite(file) { out -> out.write(root.toString().toByteArray(StandardCharsets.UTF_8)) }
            lastStore = System.currentTimeMillis()
        } catch (e: Exception) {
            Logger.w("cache: cannot save index: $e")
        }
    }

    /**
     * For changes that are not worth a disk write of their own, currently only the "last used"
     * stamp, which get() bumps on every OFFER we can answer from the cache. Rewriting the whole
     * index each time would put a synchronous file write on the network path to save a timestamp
     * whose only consumer is the ordering inside prune(). Losing the last few seconds of it to a
     * kill costs nothing: entries are still there, just fractionally staler in the LRU order.
     */
    @Synchronized
    private fun storeSoon() {
        if (System.currentTimeMillis() - lastStore >= FLUSH_MS) store()
    }

    @Synchronized
    fun put(sha: String, uri: Uri, name: String, mime: String, size: Long) {
        val e = Entry()
        e.uri = uri.toString()
        e.name = name
        e.mime = mime
        e.size = size
        e.used = System.currentTimeMillis()
        entries[sha] = e
        store()
    }

    /** URI if we still have that content (verified readable), else null; marks it as used. */
    @Synchronized
    fun get(sha: String): Uri? {
        val e = entries[sha] ?: return null
        val u = Uri.parse(e.uri)
        if (!readable(u)) {
            entries.remove(sha)
            store()
            return null
        }
        e.used = System.currentTimeMillis()
        storeSoon()
        return u
    }

    @Synchronized
    fun nameOf(sha: String): String? {
        val e = entries[sha]
        return e?.name
    }

    private fun readable(u: Uri): Boolean {
        return try {
            ctx.contentResolver.openInputStream(u).use { inp -> inp != null }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete files unused for keepHours, then the least recently used until the total is under
     * keepMaxBytes. `keep` (the URI on the clipboard right now) is never removed.
     *
     * @param inFlight hashes currently being received or served, which [prunePartials] must
     *                 leave alone. Prune runs on the completion of *some other* file, so
     *                 "old enough to delete" is a statement about the chunk map's mtime and says
     *                 nothing about whether a transfer is live; a large file arriving slowly has an
     *                 old map and an open stream, and deleting its pending row mid-transfer would
     *                 fail a transfer that is going perfectly well.
     */
    @Synchronized
    fun prune(keepHours: Int, keepMaxBytes: Long, keep: Uri?, inFlight: Set<String>?) {
        if (keepHours <= 0 && keepMaxBytes <= 0) return
        val r = ctx.contentResolver
        val rows = ArrayList(entries.entries)
        rows.sortWith { a, b -> java.lang.Long.compare(a.value.used, b.value.used) }      // LRU first
        var total = 0L
        for (me in rows) total += me.value.size
        val now = System.currentTimeMillis()
        var removed = 0
        for (me in rows) {
            val e = me.value
            if (keep != null && keep.toString() == e.uri) continue
            val tooOld = keepHours > 0 && now - e.used > keepHours * 3600_000L
            val tooBig = keepMaxBytes > 0 && total > keepMaxBytes
            if (!(tooOld || tooBig)) continue
            try {
                r.delete(Uri.parse(e.uri), null, null)       // 0 rows = user already removed it
            } catch (ex: Exception) {
                Logger.i("prune: cannot delete " + e.uri + ": " + ex)
            }
            entries.remove(me.key)
            total -= e.size
            removed++
        }
        if (removed > 0) {
            store()
            Logger.i("prune: removed $removed old file(s) from ClipSync folders")
        }
        prunePartials(keepHours, inFlight)
    }

    /** Interrupted transfers (the .json chunk maps in files/partial + their pending rows) older than keepHours. */
    private fun prunePartials(keepHours: Int, inFlight: Set<String>?) {
        if (keepHours <= 0) return
        val dir = File(ctx.filesDir, "partial")
        val maps = dir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - keepHours * 3600_000L
        for (m in maps) {
            if (m.lastModified() > cutoff) continue
            // The map is named for the hash it belongs to, which is the only handle this class has
            // on "is somebody still using it".
            val sha = if (m.name.endsWith(".json")) m.name.substring(0, m.name.length - 5) else m.name
            if (inFlight != null && inFlight.contains(sha)) continue
            try {
                FileInputStream(m).use { inp ->
                    val o = JSONObject(String(inp.readAllBytes(), StandardCharsets.UTF_8))
                    ctx.contentResolver.delete(Uri.parse(o.getString("uri")), null, null)
                }
            } catch (ignored: Exception) {
            }
            m.delete()
            Logger.i("prune: dropped stale partial " + m.name)
        }
    }
}
