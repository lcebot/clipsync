package io.github.lcebot.clipsync

import android.content.Context
import android.os.FileObserver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Service state shared with the UI across processes (the service lives in ":sync"):
 * files/status.json, rewritten by the service on every change and refreshed by the heartbeat. The UI
 * is told about a rewrite rather than looking for one, as described at [watch], and keeps a
 * slow poll only for the things no write can announce.
 *
 * **It carries a list of peers, not a single connection**, because a device can reach more than
 * one peer at once and no flat tuple could describe that. Two lists, because the interesting question
 * is not "am I connected" but "which of the things I was told to reach am I reaching": [Snapshot.peers]
 * is what is up, and [Snapshot.targets] is what is configured and is not, each with the reason.
 */
object Status {
    /** Shared by [write]: the one monitor every writer of the status file takes. */
    private val classLock = Any()

    /** A live link, as the UI needs to show it. */
    class Peer internal constructor(
        val id: String?,
        val name: String?,
        val type: String?,
        val via: String?,
        val addr: String?,
        val lan: Boolean,
    )

    /**
     * What kind of thing a not-connected reason is, namely the four answers to "so what do I do?".
     *
     * Most of what lands in the not-connected section is not an error at all, and the section's
     * real job is to account for every configured target, whatever the account says. Grading it needs
     * a vocabulary rather than a boolean, and the grades are chosen so that each one answers that
     * question differently, since a colour that does not change what the reader does next is decoration.
     *
     * The order below is the order of visual weight, loudest first.
     */
    enum class Why {
        /**
         * Something is wrong and you can act on it: a timeout, a refusal, a name that will not
         * resolve. `colorError`, and the only thing that gets it.
         */
        FAULT,
        /**
         * The peer **told us** it was going idle. The one row in the section carrying positive
         * knowledge rather than the absence of it, so it takes an accent, `colorTertiary`,
         * which this app already uses for the chip's *Connecting…*: a state the system is passing
         * through on purpose. Nothing to do; it will dial back when its screen comes on.
         */
        ASLEEP,
        /**
         * Not connected, no explanation offered, and expected to be connected again, such as
         * *Disconnected* or a discovery browse that has found nothing yet. The default, at plain
         * `colorOnSurface`. Wait.
         */
        WAITING,
        /**
         * A fact about the setup rather than about a connection: *That is this device*, *Same device
         * as …*. Nothing will change it but the configuration, and there is nothing to be done about
         * it now, so it is the quietest, `colorOnSurfaceVariant`. These are footnotes
         * explaining why a row exists at all.
         */
        NOTED;

        companion object {
            internal fun of(name: String?): Why {
                for (w in values()) if (w.name == name) return w
                return WAITING
            }
        }
    }

    /**
     * A peer this device knows about only because a direct peer listed it in its roster (T_PEERS),
     * so it is two hops away rather than connected.
     */
    class IndirectPeer internal constructor(
        val id: String?,
        val name: String?,
        val type: String?,
        val via: String?,
    )

    /** A configured target that is not connected, and why. */
    class Target internal constructor(target: String?, reason: String?, why: Why?) {
        val target: String? = target
        val reason: String? = reason
        val why: Why = why ?: Why.WAITING
    }

    class Snapshot internal constructor(
        /**
         * stopped | no network | idle | connecting | connected | relay. Five answers to "what do I
         * do now", which is the only reason they are distinct: *Stopped* needs a button pressed,
         * *No network* needs the network fixed, *Idle* needs nothing at all.
         */
        val state: String,
        /** Free text for the states that have somewhere to go but nowhere to be. */
        val detail: String?,
        val ts: Long,           // wall-clock ms of the last write
        // the heartbeat caught the process being frozen at least once WHILE THE DEVICE WAS AWAKE.
        // Being suspended along with the device is the intended outcome, not a fault, because the
        // service drops its links and idles with the screen off on purpose, so counting that would
        // advise the user against a power saving that is working.
        val suspended: Boolean,
        val peers: List<Peer>,
        val indirectPeers: List<IndirectPeer>,
        val targets: List<Target>,
        /** Number of files this device is currently fetching on a LAN peer's behalf. */
        val relayCount: Int,
    ) {
        /** The service process wrote recently and is not stopped. */
        fun alive(): Boolean {
            return "stopped" != state && System.currentTimeMillis() - ts < 120_000
        }

        /**
         * Directly connected peers, which is what the status chip counts.
         *
         * Indirect peers are deliberately left out. They are devices this one has only heard
         * about from a neighbour, so counting them would make the chip claim connections that do
         * not exist; they get their own group in the details sheet instead, where the distinction
         * can be stated rather than implied by a number.
         */
        fun count(): Int {
            return peers.size
        }
    }

    /** The snapshot the UI substitutes when the service process has died without saying so. */
    fun stopped(detail: String?, suspended: Boolean): Snapshot {
        return Snapshot("stopped", detail, 0, suspended, ArrayList(), ArrayList(), ArrayList(), 0)
    }

    // Factories rather than public constructors: the service builds these, the UI only reads them.
    fun peer(id: String?, name: String?, type: String?, via: String?, addr: String?, lan: Boolean): Peer {
        return Peer(id, name, type, via, addr, lan)
    }

    fun indirectPeer(id: String?, name: String?, type: String?, via: String?): IndirectPeer {
        return IndirectPeer(id, name, type, via)
    }

    fun target(target: String?, reason: String?, why: Why?): Target {
        return Target(target, reason, why)
    }

    private fun file(ctx: Context): File {
        return File(ctx.applicationContext.filesDir, "status.json")
    }

    /**
     * Fire `onChange` whenever the service replaces the status file.
     *
     * Push instead of poll, across a process boundary that has no other channel: the service
     * writes this file, the UI reads it, and inotify is the one thing both ends already share. What
     * it replaces is a read, a parse and a render once a second for as long as the app is in front,
     * nearly all of which found nothing new, while still being up to a second late when there
     * was.
     *
     * **The directory is watched, not the file**, and that is forced by [write]: it
     * writes a temporary file and renames it over the old one, so the inode a file watch attaches to
     * is precisely the one being discarded. Such a watch fires once and is then bound to nothing.
     * Watching the parent for `MOVED_TO` and filtering by name is the shape an atomic writer
     * demands, and it is also why the two are documented together.
     *
     * `onEvent` arrives on the observer's own thread, so the caller has to marshal. The
     * returned observer must be held in a field and started: an unreferenced FileObserver is
     * collected and stops delivering, silently.
     */
    fun watch(ctx: Context, onChange: () -> Unit): FileObserver {
        val f = file(ctx)
        val name = f.name
        return object : FileObserver(f.parentFile, FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (name == path) onChange()
            }
        }
    }

    /**
     * Synchronized, because the number of writers scales with the number of connections: one per
     * dialer, plus the heartbeat, plus the main thread.
     *
     * All of them share one temporary file and one rename, and two writers truncating that file at
     * the same time interleave their bytes. The reader's catch-all then yields the "stopped" snapshot,
     * so the chip blinks *Stopped*, the button blinks *Start*, and pressing it in that window starts
     * the service instead of reloading it. A per-thread tmp name would also work; serialising is
     * cheaper to be sure of.
     */
    fun write(ctx: Context, state: String?, detail: String?, suspended: Boolean,
              peers: List<Peer>, indirectPeers: List<IndirectPeer>,
              targets: List<Target>, relayCount: Int) {
        synchronized(classLock) {
            try {
                val ps = JSONArray()
                for (p in peers) {
                    ps.put(JSONObject().put("id", str(p.id)).put("name", str(p.name)).put("type", str(p.type))
                            .put("via", str(p.via)).put("addr", str(p.addr)).put("lan", p.lan))
                }
                val ips = JSONArray()
                for (ip in indirectPeers) {
                    ips.put(JSONObject().put("id", str(ip.id)).put("name", str(ip.name))
                            .put("type", str(ip.type)).put("via", str(ip.via)))
                }
                val ts = JSONArray()
                for (t in targets) {
                    ts.put(JSONObject().put("target", str(t.target)).put("reason", str(t.reason))
                            .put("why", t.why.name))
                }
                val s = JSONObject().put("state", state).put("detail", str(detail))
                        .put("ts", System.currentTimeMillis()).put("suspended", suspended)
                        .put("peers", ps).put("indirect_peers", ips)
                        .put("targets", ts).put("relay_count", relayCount).toString()
                Files.atomicWrite(file(ctx)) { out -> out.write(s.toByteArray(StandardCharsets.UTF_8)) }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun str(s: String?): Any {
        return s ?: JSONObject.NULL
    }

    private fun get(o: JSONObject, k: String): String? {
        return if (o.isNull(k)) null else o.optString(k)
    }

    fun read(ctx: Context): Snapshot {
        try {
            FileInputStream(file(ctx)).use { input ->
                val o = JSONObject(String(input.readAllBytes(), StandardCharsets.UTF_8))
                val peers = ArrayList<Peer>()
                val ps = o.optJSONArray("peers")
                var i = 0
                while (ps != null && i < ps.length()) {
                    val p = ps.optJSONObject(i)
                    if (p != null) {
                        peers.add(Peer(get(p, "id"), get(p, "name"), get(p, "type"),
                                get(p, "via"), get(p, "addr"), p.optBoolean("lan")))
                    }
                    i++
                }
                val indirectPeers = ArrayList<IndirectPeer>()
                val ips = o.optJSONArray("indirect_peers")
                i = 0
                while (ips != null && i < ips.length()) {
                    val ip = ips.optJSONObject(i)
                    if (ip != null) {
                        indirectPeers.add(IndirectPeer(get(ip, "id"), get(ip, "name"),
                                get(ip, "type"), get(ip, "via")))
                    }
                    i++
                }
                val targets = ArrayList<Target>()
                val ts = o.optJSONArray("targets")
                i = 0
                while (ts != null && i < ts.length()) {
                    val t = ts.optJSONObject(i)
                    if (t != null) {
                        targets.add(Target(get(t, "target"), get(t, "reason"), Why.of(t.optString("why"))))
                    }
                    i++
                }
                return Snapshot(o.optString("state", "stopped"), get(o, "detail"),
                        o.optLong("ts", 0), o.optBoolean("suspended"), peers, indirectPeers, targets,
                        o.optInt("relay_count", 0))
            }
        } catch (e: Exception) {
            return Snapshot("stopped", null, 0, false, ArrayList(), ArrayList(), ArrayList(), 0)
        }
    }
}
