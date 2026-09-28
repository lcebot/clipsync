package io.github.lcebot.clipsync

import android.content.Context

import org.json.JSONArray
import org.json.JSONObject

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Who fetches a file for whom, when several nodes on one LAN all want the same thing from one node
 * that is not on it.
 *
 * The problem it exists for: a PC on the internet offers a file to a phone and a tablet that sit
 * beside each other on Wi-Fi. Without this, both pull the whole file over the slow link and the PC
 * uploads it twice. With it, both ends of the LAN compute the *same* priority order from
 * fields that were already in HELLO: mains power beats battery, a PC beats a tablet beats a phone,
 * a node that can hold a connection while idle beats one that cannot, and the node id breaks ties,
 * so the winner pulls from the origin and the losers ask the winner to pass it on. The order is
 * total and computed identically everywhere, which is what lets them agree without an election
 * message.
 *
 * Three things follow, and this class owns all three:
 *
 * - **asking** (`relayWaits`): a walk down the candidate list, one RELAY_ASK at a time,
 *   with a timeout per step and one retry for a candidate that says "busy", falling back to the
 *   origin when the list runs out;
 * - **accepting** (`relayAccepted`): a set of waiting links per hash, which this device
 *   will OFFER the file to, as soon as the *first chunk* lands, not when the file is
 *   complete, so a relayed file moves through rather than being stored and forwarded;
 * - **saying so**: [relayingCount] is what the UI shows as "Relay (n)", and
 *   [busy] is what stops a screen-off disconnect dropping a relay mid-flight.
 *
 * **What it does not do.** It never touches a chunk, a Partial or the cache directly, it never
 * decides whether a file is wanted, and it does not own the pull that streams a relayed file out;
 * all of that is [FileExchange], which it reaches through [Host]. It also holds no clip
 * state at all: a relay is about bytes, and whether those bytes end up on the clipboard is somebody
 * else's decision.
 *
 * **Locking.** It does not take the device lock and must not: every map here is a concurrent
 * one, and nothing in here is part of an invariant that spans another class. A [RelayWait] is
 * stepped from several threads (the control-frame thread of each link that answers, and the
 * `clipsync-relay` timer thread), so its fields are read and written only while holding the wait
 * itself. That lock never spans a call back into [Host] that could take another class's lock: the
 * fallback to the origin runs after it is released. The one invariant that does span, that a hash with an
 * empty waiter set is a finished relay and not a busy one, is stated in [busy] and in
 * [onLinkGone] and is maintained entirely inside this file.
 */
internal class RelayCoordinator internal constructor(private val ctx: Context, private val host: Host) {

    /** What the relay needs from the file exchange it is part of. */
    internal interface Host {
        fun config(): Config

        /** Every live link. */
        fun links(): Iterable<Link>

        /** The live link to one peer id, or null. */
        fun linkTo(peerId: String?): Link?

        /** A file we can serve, wherever it is: still offered, recently sent, or in the cache. */
        fun refFor(sha: String): Files.Ref?

        /** Remember this file as offered, so a WANT for it can be answered. */
        fun rememberOffered(sha: String, f: Files.Ref)

        /** The OFFER header this file arrived with, or null, for its originator's seq and from. */
        fun inboundOffer(sha: String): JSONObject?

        /** Is the complete file already in the cache? */
        fun cached(sha: String): Boolean

        /** The in-progress download of this hash, or null. */
        fun partial(sha: String): Files.Partial?

        /** Finalise a complete Partial: it lands in the cache and the waiters are offered it. */
        fun finishDownload(l: Link?, p: Files.Partial)

        /** The ordinary WANT path, for when the walk gives up and falls back to the origin. */
        fun wantFromPeer(l: Link, sha: String, name: String, hdr: JSONObject, size: Long, seq: Long)
    }

    companion object {
        // ------------------------------------------------------------------ election

        /**
         * Priority key for relay election.  Lower array = higher priority; elements are compared
         * left to right, and the node id breaks ties (lower id wins).  The order is total, so every
         * node computes the same answer from the same HELLO fields without exchanging an election
         * message.
         */
        private fun priorityKey(persistent: Boolean, type: String, battery: String): IntArray {
            val typeRank = if ("pc" == type) 2 else if ("tablet" == type) 1 else 0
            val battRank = if ("mains" == battery) 3 else if ("high" == battery) 2
                else if ("medium" == battery) 1 else 0
            return intArrayOf(if (persistent) 0 else 1, -typeRank, -battRank)
        }

        private fun comparePriority(a: IntArray, idA: String, b: IntArray, idB: String): Int {
            for (i in 0 until Math.min(a.size, b.size)) {
                val c = Integer.compare(a[i], b[i])
                if (c != 0) return c
            }
            return idA.compareTo(idB)
        }

        /**
         * Timeout for a single step of the relay fallback walk (generous, because it is a backstop,
         * not a scheduler).
         */
        private const val RELAY_ASK_TIMEOUT_MS = 30_000L

        /**
         * A RELAY_ASK: the hash, and how big the thing is.
         *
         * The size is what lets a candidate say no *now*. Without it a relay that could never
         * accept the file, because it is over its own limit, has nothing to answer until it has seen the file, so
         * the asker waits out the full [RELAY_ASK_TIMEOUT_MS] before moving to the next
         * candidate: thirty seconds of silence per candidate, ninety for a list of three, all of it
         * avoidable by sending a number that is already in the OFFER header.
         */
        private fun relayAsk(rw: RelayWait): JSONObject {
            return Frames.shaMsg(rw.sha256).put("size", rw.offerHdr.optLong("size", -1))
        }
    }

    /** Compute the candidate list for relay election, sorted by priority (best first). */
    private fun buildCandidateList(offerFrom: String, originConn: Connection, recipients: Set<String>): List<String> {
        // Each candidate: [id, persistent, type, battery].
        val cands = ArrayList<Array<String>>()
        // Me.
        cands.add(arrayOf(Node.id(), Node.persistent(ctx).toString(), Node.type(ctx), Node.battery(ctx)))
        // Origin, if on LAN.
        if (originConn.lanPeer) {
            cands.add(arrayOf(offerFrom, originConn.peerPersistent.toString(), originConn.peerType,
                originConn.peerBattery))
        }
        // LAN peers that are in the recipient list.
        for (peer in host.links()) {
            val peerId = peer.peerId()
            if (!peer.isOpen() || peerId == null || peerId == offerFrom) continue
            val pc = peer.connection()
            if (pc.lanPeer && recipients.contains(peerId)) {
                cands.add(arrayOf(peerId, pc.peerPersistent.toString(), pc.peerType, pc.peerBattery))
            }
        }
        cands.sortWith { a, b ->
            comparePriority(
                priorityKey(java.lang.Boolean.parseBoolean(a[1]), a[2], a[3]), a[0],
                priorityKey(java.lang.Boolean.parseBoolean(b[1]), b[2], b[3]), b[0])
        }
        val ids = ArrayList<String>()
        for (c in cands) ids.add(c[0])
        return ids
    }

    /** @return the best relay candidate id, or null if the candidate set is empty. */
    private fun electRelay(offerFrom: String, originConn: Connection, recipients: Set<String>): String? {
        val ids = buildCandidateList(offerFrom, originConn, recipients)
        return if (ids.isEmpty()) null else ids[0]
    }

    // ------------------------------------------------------------------ waiting

    /** State for a file we are waiting on a relay to provide. */
    private class RelayWait(
        val sha256: String,
        val offerHdr: JSONObject,           // the original OFFER header, for a later WANT to the origin
        val origin: Link?,                  // who sent the OFFER
        val candidates: List<String>,       // priority-sorted node ids (walk order)
    ) {
        var nextIdx = 0                     // current position in the fallback walk
        var askTime = 0L                    // SystemClock.elapsedRealtime when RELAY_ASK was sent
        val failed: MutableSet<String?> = HashSet()
        var retried = false                 // whether the current busy candidate was retried once
    }

    /** Files we are waiting on a relay to provide, keyed by sha256. */
    private val relayWaits: MutableMap<String, RelayWait> = ConcurrentHashMap()

    /** Relay requests we accepted: sha mapped to the set of waiter Links that will receive the OFFER. */
    private val relayAccepted: MutableMap<String, MutableSet<Link>> = ConcurrentHashMap()

    /**
     * Where the relay's delayed work runs. **Not the main looper.**
     *
     * Both the step timeout and the busy retry end in a socket write, and a socket write on the
     * main thread throws NetworkOnMainThreadException. Every candidate in the walk after the first,
     * and the origin fallback itself, depends on this timer running off the main looper, because a relay
     * that had to retry or fall back at all would otherwise fail silently right when the fallback
     * path is what it needs most.
     *
     * It is also the phone's only background timer for file work, which is why
     * [FileExchange]'s debounced re-ask runs on it through [schedule] rather than
     * starting a second one: one thread, one shutdown, and the same "never the main looper" rule
     * enforced in one place instead of two. Both kinds of work end in a short socket write and are
     * rare (a relay that had to fall back, a stream that ended with the file incomplete), so sharing
     * the thread costs nothing that a second thread would buy.
     */
    private val relayTimer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, "clipsync-relay")
            t.isDaemon = true
            t
        }

    /**
     * Run `r` on the background timer after `delayMs`.
     *
     * @return false when the timer is gone (the service is being destroyed), so a caller that keeps
     *         a "one timer armed for this file" flag can drop it again instead of leaving a flag
     *         behind that nothing will ever clear.
     */
    internal fun schedule(r: Runnable, delayMs: Long): Boolean {
        try {
            relayTimer.schedule(r, delayMs, TimeUnit.MILLISECONDS)
            return true
        } catch (e: RejectedExecutionException) {
            return false       // shutdown() ran; nothing left to re-ask on
        }
    }

    internal fun shutdown() {
        relayTimer.shutdownNow()
    }

    /** How many files this device is actually relaying: hashes that still have a waiter. */
    internal fun relayingCount(): Int {
        var n = 0
        for (w in relayAccepted.values) if (!w.isEmpty()) n++
        return n
    }

    /**
     * Is a relay this device accepted still outstanding?
     *
     * A non-empty *set*, not a non-empty map, because a key whose waiters have all gone is a
     * finished relay, not an outstanding one, and treating it as outstanding would hold the device
     * "busy" indefinitely. [onLinkGone] removes those keys; this is the belt to that pair of
     * braces.
     */
    internal fun busy(): Boolean {
        for (w in relayAccepted.values) if (!w.isEmpty()) return true
        return false
    }

    /** Are there waiters for this hash, i.e. is this device relaying it? */
    internal fun hasWaiters(sha: String): Boolean {
        return relayAccepted.containsKey(sha)
    }

    /**
     * An OFFER has arrived and the file is worth having. Should a relay fetch it instead of us?
     *
     * This is the whole of the election as `FileExchange.onOffer` sees it.
     *
     * @return true when a RELAY_ASK has gone out and the caller must do nothing further; false when
     *         the ordinary WANT path applies, either because no election is called for, or because
     *         this device won it, or because this OFFER *is* the relay answering.
     */
    internal fun intercept(l: Link, sha: String, name: String, hdr: JSONObject): Boolean {
        val c = l.connection()
        val rw = relayWaits[sha]
        val lid = l.peerId()
        if (rw != null && lid != null && lid == currentRelayFor(rw)) {
            // This OFFER is from the relay we asked, so skip election and WANT directly.
            relayWaits.remove(sha)
            Logger.i("offer: " + name + " from relay " + Node.shortId(l.peerId()) + ", want directly")
            return false
        }
        val offerFrom: String = hdr.optString("from", "")
        val toArr: JSONArray? = hdr.optJSONArray("to")
        if (toArr == null || toArr.length() == 0 || offerFrom.isEmpty()) return false
        val recipients = HashSet<String>()
        for (i in 0 until toArr.length()) recipients.add(toArr.optString(i))
        val best = electRelay(offerFrom, c, recipients)
        if (best == null || best == Node.id() || best == offerFrom) return false
        // A higher-priority LAN peer should relay; ask it.
        val candidateIds = buildCandidateList(offerFrom, c, recipients)
        val wait = RelayWait(sha, hdr, l, candidateIds)
        relayWaits[sha] = wait
        askRelay(wait)
        return true
    }

    /** @return the node id of the relay we are currently asking, or null. */
    private fun currentRelayFor(rw: RelayWait): String? {
        if (rw.nextIdx < rw.candidates.size) {
            val id = rw.candidates[rw.nextIdx]
            if (id != Node.id() && (rw.origin == null || id != rw.origin.peerId())) return id
        }
        return null
    }

    /**
     * Walk the candidate list: send RELAY_ASK to the next viable candidate, or fall back to the
     * origin when the list is exhausted.
     */
    private fun askRelay(rw: RelayWait) {
        val exhausted = synchronized(rw) { stepWalk(rw) }
        if (exhausted) fallBackToOrigin(rw)
    }

    /**
     * One step of the walk, under the wait's lock: ask the next viable candidate and return false,
     * or return true when the list is exhausted and the origin is the only source left.
     */
    private fun stepWalk(rw: RelayWait): Boolean {
        while (rw.nextIdx < rw.candidates.size) {
            val cid = rw.candidates[rw.nextIdx]
            if (cid == Node.id() || (rw.origin != null && cid == rw.origin.peerId())) break // reached self or origin
            if (rw.failed.contains(cid)) { rw.nextIdx++; continue }
            val relay = host.linkTo(cid)
            if (relay == null || !relay.isOpen()) { rw.failed.add(cid); rw.nextIdx++; continue }
            try {
                relay.connection().sendJson(Connection.T_RELAY_ASK, relayAsk(rw))
                rw.askTime = android.os.SystemClock.elapsedRealtime()
                rw.retried = false
                Logger.i("relay: asking " + Node.shortId(cid) + " to relay " + Frames.shortSha(rw.sha256))
                // Schedule a timeout for this step of the walk.
                relayTimer.schedule(Runnable {
                    val timedOut = synchronized(rw) {
                        val w = relayWaits[rw.sha256]
                        val late = w != null && w === rw && w.askTime > 0 &&
                            android.os.SystemClock.elapsedRealtime() - w.askTime >= RELAY_ASK_TIMEOUT_MS
                        if (late) {
                            Logger.i("relay: " + Node.shortId(cid) + " timed out for " + Frames.shortSha(rw.sha256))
                            rw.failed.add(cid)
                            rw.nextIdx++
                        }
                        late
                    }
                    if (timedOut) askRelay(rw)
                }, RELAY_ASK_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
                return false
            } catch (e: Exception) {
                Logger.w("relay: could not ask " + Node.shortId(cid) + " for " + Frames.shortSha(rw.sha256) + ": " + e)
                rw.failed.add(cid)
                rw.nextIdx++
            }
        }
        return true
    }

    /** The candidate list is exhausted, so WANT from the origin. */
    private fun fallBackToOrigin(rw: RelayWait) {
        // Only the first thread to find the list exhausted falls back; a second one finds the wait
        // already gone and must not ask the origin twice.
        if (!relayWaits.remove(rw.sha256, rw)) return
        if (rw.origin == null || !rw.origin.isOpen()) return
        try {
            val name = FileExchange.safeName(rw.offerHdr.optString("name", "clip"))
            host.wantFromPeer(rw.origin, rw.sha256, name, rw.offerHdr,
                rw.offerHdr.optLong("size", -1), rw.offerHdr.optLong("seq", 0))
            Logger.i("relay: fell back to origin for " + Frames.shortSha(rw.sha256))
        } catch (e: Exception) {
            Logger.w("relay: fallback to origin failed: " + e.message)
        }
    }

    // ------------------------------------------------------------------ being the relay

    /**
     * A peer asks us to relay a file.  Accept unless we are opted out, on low battery, or are
     * ourselves waiting for the same file.
     */
    internal fun onRelayAsk(l: Link, msg: JSONObject) {
        val cfg = host.config()
        val sha: String = msg.optString("sha256")
        val c = l.connection()
        // Check opt-out and low battery.
        if (cfg.relayOptOut) {
            c.sendJson(Connection.T_RELAY_NO, JSONObject().put("sha256", sha).put("reason", "refused"))
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (opted out)")
            return
        }
        if ("low" == Node.battery(ctx)) {
            c.sendJson(Connection.T_RELAY_NO, JSONObject().put("sha256", sha).put("reason", "refused"))
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (low battery)")
            return
        }
        // Over what this device would accept for itself. "refused" and not "busy": nothing about
        // waiting and asking again changes a size, and the asker's walk should move on at once.
        val size = msg.optLong("size", -1)
        if (size > cfg.maxFileAny()) {
            c.sendJson(Connection.T_RELAY_NO, JSONObject().put("sha256", sha).put("reason", "refused"))
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() +
                " (" + size + " bytes is over this device's limit)")
            return
        }
        // A node that is itself waiting declines with busy (caps depth at one hop).
        if (relayWaits.containsKey(sha)) {
            c.sendJson(Connection.T_RELAY_NO, JSONObject().put("sha256", sha).put("reason", "busy"))
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (busy, also waiting)")
            return
        }
        // Accept.
        c.sendJson(Connection.T_RELAY_OK, Frames.shaMsg(sha))
        relayAccepted.computeIfAbsent(sha) { ConcurrentHashMap.newKeySet<Link>() }.add(l)
        Logger.i("relay: accepted " + Frames.shortSha(sha) + " for " + Node.shortId(l.peerId()))
        // If we already have the file, offer immediately.
        if (host.cached(sha)) {
            offerToWaiters(sha)
            return
        }
        // If a complete Partial exists, finalize and offer.
        val p = host.partial(sha)
        if (p != null && p.complete()) {
            host.finishDownload(null, p)
            // finishDownload calls offerToWaiters
        }
        // Otherwise, the offer will come when our own download completes (finishDownload calls offerToWaiters)
        // or via streaming relay once the first chunks arrive (endPush calls offerToWaiters).
    }

    /** Relay accepted our request; wait for its OFFER. */
    internal fun onRelayOk(l: Link, msg: JSONObject) {
        val sha: String = msg.optString("sha256")
        // Only that we are still waiting on this sha matters; an OK for a wait that has since been
        // satisfied or abandoned is stale and says nothing worth logging.
        if (!relayWaits.containsKey(sha)) return
        Logger.i("relay: " + Node.shortId(l.peerId()) + " accepted relay for " + Frames.shortSha(sha))
        // Nothing else to do: the relay will send OFFER when it has the file, and intercept()
        // recognises the relay as the source and WANTs directly.
    }

    /** Relay declined; walk to the next candidate. */
    internal fun onRelayNo(l: Link, msg: JSONObject) {
        val sha: String = msg.optString("sha256")
        val reason: String = msg.optString("reason", "")
        val rw = relayWaits[sha] ?: return
        Logger.i("relay: " + Node.shortId(l.peerId()) + " declined " + Frames.shortSha(sha) + ": " + reason)
        val retry = synchronized(rw) {
            if ("busy" == reason && !rw.retried) {
                rw.retried = true
                true
            } else {
                rw.failed.add(l.peerId())
                rw.nextIdx++
                false
            }
        }
        if (!retry) {
            askRelay(rw)
            return
        }
        // One retry after a short jittered delay (the candidate may be about to become the relay).
        val jitter = 500 + (Math.random() * 1000).toLong()
        relayTimer.schedule(Runnable {
            val walkOn = synchronized(rw) {
                val w = relayWaits[sha]
                if (w !== rw) return@Runnable
                try {
                    val relay = host.linkTo(l.peerId())
                    if (relay != null && relay.isOpen()) {
                        relay.connection().sendJson(Connection.T_RELAY_ASK, relayAsk(rw))
                        rw.askTime = android.os.SystemClock.elapsedRealtime()
                        Logger.i("relay: retrying " + Node.shortId(l.peerId()) + " for " + Frames.shortSha(sha))
                        false
                    } else {
                        rw.failed.add(l.peerId())
                        rw.nextIdx++
                        true
                    }
                } catch (e: Exception) {
                    Logger.w("relay: retry to " + Node.shortId(l.peerId()) + " failed: " + e)
                    rw.failed.add(l.peerId())
                    rw.nextIdx++
                    true
                }
            }
            if (walkOn) askRelay(rw)
        }, jitter, TimeUnit.MILLISECONDS)
    }

    /**
     * The originator's `seq` and `from` for a file we are relaying, if we still know
     * them.
     *
     * A relay must not restamp these. They are two thirds of the seen-set key, so a waiter that
     * also receives the file directly has to compute the same key from both copies or it treats the
     * second as new. Looked up rather than removed: the early offer, the completed offer and
     * `FileExchange.forwardFile` all want it, and only the last is done with it.
     *
     * @return `{seq, from}` from the OFFER that brought this file in, or this device's own
     *         values when it did not arrive by OFFER at all.
     */
    private fun relayOrigin(sha: String): Array<Any?> {
        val `in` = host.inboundOffer(sha) ?: return arrayOf(System.currentTimeMillis(), Node.id())
        return arrayOf(`in`.optLong("seq", System.currentTimeMillis()), `in`.optString("from", Node.id()))
    }

    /**
     * Send OFFER to every waiter that asked for this file via RELAY_ASK.
     * Called from finishDownload when we have the complete file in the cache.
     */
    internal fun offerToWaiters(sha: String) {
        val waiters = relayAccepted.remove(sha)
        if (waiters == null || waiters.isEmpty()) return
        val f = host.refFor(sha) ?: return
        host.rememberOffered(sha, f)
        for (w in waiters) {
            if (!w.isOpen()) continue
            try {
                val origin = relayOrigin(sha)
                val hdr = JSONObject()
                hdr.put("seq", origin[0])
                hdr.put("name", f.name)
                hdr.put("mime", f.mime)
                hdr.put("size", f.size)
                hdr.put("sha256", f.sha256)
                hdr.put("from", origin[1])
                // A relay is a forward: the waiter asked us for this file and must not pass it on
                // again, or the two of us hand it round the LAN between us.
                hdr.put("forwarded", true)
                val to = JSONArray()
                to.put(w.peerId())
                hdr.put("to", to)
                w.connection().sendJson(Connection.T_OFFER, hdr)
                Logger.i("relay: offered " + f.name + " to waiter " + Node.shortId(w.peerId()))
            } catch (e: Exception) {
                Logger.w("relay: offer to " + Node.shortId(w.peerId()) + " failed: " + e)
            }
        }
    }

    /**
     * Streaming relay: send OFFER to waiters as soon as we have the first chunk, so they can
     * start pulling while we are still receiving.  Does NOT remove from relayAccepted; that stays
     * so the pull-serving path knows to use the streaming path, and [offerToWaiters] cleans
     * it when the file completes.
     */
    internal fun earlyOfferToWaiters(sha: String, p: Files.Partial) {
        val waiters = relayAccepted[sha]
        if (waiters == null || waiters.isEmpty()) return
        for (w in waiters) {
            if (!w.isOpen()) continue
            try {
                val origin = relayOrigin(sha)
                val hdr = JSONObject()
                // Same seq/from as the completed offer that follows it, so the waiter does not see
                // one file as two: the early offer and offerToWaiters both describe this transfer.
                hdr.put("seq", origin[0])
                hdr.put("name", p.name)
                hdr.put("mime", p.mime)
                hdr.put("size", p.size)
                hdr.put("sha256", p.sha256)
                hdr.put("from", origin[1])
                hdr.put("forwarded", true)         // a relay is a forward; see offerToWaiters
                val to = JSONArray()
                to.put(w.peerId())
                hdr.put("to", to)
                w.connection().sendJson(Connection.T_OFFER, hdr)
                Logger.i("relay: early offer " + p.name + " to waiter " + Node.shortId(w.peerId()) + " (streaming)")
            } catch (e: Exception) {
                Logger.w("relay: early offer to " + Node.shortId(w.peerId()) + " failed: " + e)
            }
        }
    }

    /**
     * A link went away: walk past it if it was the relay we were waiting on, and stop counting it
     * as a waiter if it was one.
     *
     * Removing the empty sets is not tidiness. [busy] asks whether any hash still has a
     * waiter, so a finished relay left as an empty set would read as "busy" for the life of the
     * process, and the screen-off disconnect would never fire again, so the phone would hold its
     * links, and its radio, indefinitely.
     */
    internal fun onLinkGone(l: Link, peerId: String?) {
        if (peerId == null) return
        for (rw in ArrayList(relayWaits.values)) {
            val walkOn = synchronized(rw) {
                val waitingOnIt = peerId == currentRelayFor(rw)
                if (waitingOnIt) {
                    rw.failed.add(peerId)
                    rw.nextIdx++
                }
                waitingOnIt
            }
            if (walkOn) askRelay(rw)
        }
        relayAccepted.entries.removeIf { e ->
            e.value.remove(l)
            e.value.isEmpty()
        }
    }
}
