package io.github.lcebot.clipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

import org.json.JSONArray
import org.json.JSONObject

import java.nio.charset.StandardCharsets

/**
 * This device's clipboard, and the whole of what the rest of the app is allowed to know about it.
 *
 * It owns five things that are really one subject:
 *
 * - **the system clipboard**: reading a [ClipData] down to one text or one file, and
 *   writing an accepted remote clip back;
 * - **echo suppression**: `sentHashes`, `lastRemoteHash`, `lastRemoteUri`,
 *   the three answers to "did this come from us, or did we just put it there?";
 * - **identity of content**: [normalise] and the sha taken over it, which is the one
 *   rule both ends of the wire must compute the same way;
 * - **versioning**: `(ts, from)` compared lexicographically, normalised into the local
 *   clock domain by each link's measured offset, plus the bounded seen-set that makes a clip
 *   arriving twice by two routes harmless;
 * - **the pending slot**: what this device is still trying to hand to its peers, which is
 *   also what decides whether the radio may sleep.
 *
 * **What it does not do.** It never touches a socket except through [Host.sendClip], it
 * knows nothing about chunks, offers or relays, and it does not decide when to dial. It holds no
 * link state: which peer has already been given a clip is [Link]'s answer, and the pending
 * slot deliberately does not track it.
 *
 * **Locking.** The monitor is *not* this object's: it is the one [SyncService]
 * holds and every dialler waits on, passed in at construction. That is deliberate
 * and must not be "tidied" into a private lock. The gate a dialler sleeps on asks
 * `(!screenOn && !worthWaking()) || !hasNetwork`, and [worthWaking] reads the pending
 * slot, so the state in here and the condition out there are one invariant, and waking the sleeper
 * requires notifying the monitor that guards what it is waiting for. Two monitors would be two
 * chances to publish a new clip to a thread that is asleep on the other one.
 *
 * `sentHashes` and the seen-set keep their own small monitors: they are
 * self-contained bounded caches with no invariant shared with anything else, and taking the device
 * lock to answer "have I seen this" would put a frame-rate operation behind the clipboard.
 */
internal class ClipboardBridge internal constructor(
    private val ctx: Context,
    private val clipboard: ClipboardManager,
    /** The device's monitor, shared with the dialler gate. See the class note on locking. */
    private val lock: Any,
    private val host: Host,
) {

    /** What the clipboard needs from the device. Everything shared stays on the far side of this. */
    internal interface Host {
        fun config(): Config

        /** Every live link, for forwarding a clip on and for reporting an undelivered one. */
        fun links(): Iterable<Link>

        /** Direct ∪ indirect peer ids: the `to` set a CLIP frame carries. */
        fun knownPeerIds(): Set<String>

        /** Put one clip on one link: text as a CLIP frame, a file as an OFFER. */
        fun sendClip(l: Link, clip: Any)

        /**
         * A newer clip has just been captured: stop any transfer it supersedes and withdraw the
         * offers that are now about stale content. `except` is the new clip's own hash.
         */
        fun supersede(except: String)

        /** Notify the dialler gate and hand the new clip to every live link. */
        fun wake()

        /** The file-size limit in force on one link, for the diagnosis in [reportStalePending]. */
        fun limitOn(c: Connection): Long
    }

    // ------------------------------------------------------------------ echo suppression
    // volatile, all three: they are written from the clip worker and from up to N clipsync-push
    // threads, and read both under `lock` and (lastRemoteUri, in handleClip) outside it. With
    // several connections, a reader that misses a write re-applies a clip this device just sent,
    // which is how a two-peer ping-pong starts.
    @Volatile private var lastRemoteHash: String? = null     // content we last wrote into the local clipboard
    @Volatile private var lastRemoteUri: String? = null      // URI we last put on the clipboard (cheap loop check)

    /**
     * Content this device has sent recently, so a peer echoing it back is recognised and ignored.
     *
     * **A set, not a slot.** A single field is not enough with more than one peer: send A to
     * peer 1, then relay B, and a single slot would now hold only B, so peer 1's echo of A would no
     * longer be recognised and would be written back to the clipboard. A set keeps every recently
     * sent hash recognisable regardless of how many peers or clips are in flight.
     *
     * Bounded at sixteen and ordered by insertion, so it forgets the oldest rather than growing.
     * Sixteen is far more than the number of clips that can be in flight and small enough that a
     * linear scan never matters.
     */
    private val sentHashes: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 16
            }
        })

    /** This device has put that content on the wire; a peer echoing it back is not news. */
    internal fun markSent(hash: String?) {
        if (hash == null) return
        synchronized(sentHashes) { sentHashes.add(hash) }
    }

    private fun wasSentByUs(hash: String): Boolean {
        synchronized(sentHashes) { return sentHashes.contains(hash) }
    }

    /**
     * Is this content our own echo, or is it already what the clipboard holds?
     *
     * The question an inbound OFFER asks before it answers anything: both answers mean "we have
     * these bytes", and the peer should be told HAVE rather than sent them.
     */
    internal fun isEcho(sha: String): Boolean {
        synchronized(lock) {
            return wasSentByUs(sha) || sha == lastRemoteHash
        }
    }

    /**
     * A file a peer offered turned out to be one we already hold. Record where it is, so the next
     * OFFER of it is recognised as an echo without touching the cache.
     *
     * Deliberately NOT put on the clipboard by the caller: see the cache-hit arm of
     * `FileExchange.onOffer` for why.
     */
    internal fun noteCached(sha: String, uri: Uri) {
        synchronized(lock) {
            lastRemoteHash = sha
            lastRemoteUri = uri.toString()
        }
    }

    // ---- clip versioning ----
    /** Wall-clock ms when the current local clip was produced. Compared (ts, from) lexicographically. */
    @Volatile private var clipTs: Long = 0

    /** Node id of whoever produced the current clip (this device or a remote peer). */
    @Volatile private var clipFrom: String? = null

    /** SHA-256 hex of the current clip, over [normalise]d text. For catch-up and the seen-set. */
    @Volatile private var clipSha: String? = null

    /**
     * The current clip itself, so that a peer found to be behind can be given it.
     *
     * Exactly one of the two is non-null. Keeping the actual content here, not just its hash, is
     * what lets [catchUp] hand a behind peer the clip directly instead of routing through
     * [pendingLocal], a slot that is deliberately released as soon as every live link has
     * taken the clip, and so cannot be relied on once a peer reconnects later.
     *
     * Separate fields rather than one Object because the two are used differently: text is sent
     * as a CLIP and compared by version, a file is OFFERed and compared by hash alone. A received
     * file does get a version, because [adoptFile] borrows the originator's `seq` for
     * [clipTs], so the file takes its place in the text ordering, but no file is ever
     * compared against another file by it: [catchUp] only runs `compareVersion` for
     * text, and asks about a file by offering it.
     */
    @Volatile private var clipText: String? = null
    @Volatile private var clipFile: Files.Ref? = null

    internal fun clipTs(): Long {
        return clipTs
    }

    internal fun clipSha(): String? {
        return clipSha
    }

    /**
     * The last 64 `ts:from:sha256` triples seen, for duplicate suppression in any topology.
     *
     * Identical clips arriving by two routes are dropped here whatever path they took, which is
     * what makes forwarding safe in a network that is not a tree. **Files use it too**, keyed by
     * the OFFER's `seq` in place of a clip's `ts`, because without it a
     * device reconnecting and re-offering an old file could put it back on everyone's clipboard,
     * over whatever the user had copied since.
     *
     * Bounded, so the cost per incoming frame is one lookup in a small map and the memory is
     * fixed however long the process runs.
     */
    private val seenSet = object : java.util.LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 64
        }
    }

    /**
     * Record this (version, origin, content) triple and say whether it had already been seen.
     *
     * @param version the clip's `ts` for text, the OFFER's `seq` for a file
     */
    internal fun seen(version: Long, from: String, sha: String): Boolean {
        val key = "$version:$from:$sha"
        synchronized(seenSet) {
            return seenSet.put(key, true) != null
        }
    }

    // ------------------------------------------------------------------ the pending slot
    /**
     * The newest local clip, or null.
     *
     * **Not consumed.** Taking the clip on the first flush would be wrong with more than one
     * peer, since the first connection to send it would take it away from the rest. Instead each link
     * records what it has delivered (`Link.sentHash`), so this stays put until a newer clip
     * replaces it, and a peer that connects a minute later still gets it.
     */
    @Volatile private var pendingLocal: Any? = null

    /**
     * When the clip now in the slot **first** went into it, for [worthWaking].
     *
     * First, not last, and that distinction is what makes [PENDING_WAKE_MS] a bound at all.
     * A file is released from the slot as soon as every link has been *offered* it, and put
     * back by [requeue] when the transfer that followed died with the file unanswered, so a
     * file too large for the link it is crossing cycles through the slot indefinitely. Stamping each
     * re-entry with the current time would restart the five-minute window on every cycle, letting a
     * clip that can never be delivered keep the radio awake indefinitely; keeping the first-entry
     * timestamp instead makes the window a real bound. [pendingHash] is how a re-entry is told
     * from a new clip.
     */
    @Volatile private var pendingSince: Long = 0

    /** The content in the slot, so [requeue] can tell "still this one" from "a new one". */
    @Volatile private var pendingHash: String? = null

    /**
     * The content already reported as stuck, so the diagnosis is one line per clip.
     *
     * Kept past the release of the slot, and past the clip itself: the question "have I said this"
     * is about the content, and the alternative is a warning once per heartbeat for as long as the
     * situation lasts, which is noise that nobody reads.
     */
    @Volatile private var staleReported: String? = null

    /** The newest local clip, or null. Not consumed: every link delivers it once. */
    internal fun pendingClip(): Any? {
        return pendingLocal
    }

    /** Is there a clip recent enough to justify waking the radio for it? See [PENDING_WAKE_MS]. */
    internal fun worthWaking(): Boolean {
        return pendingLocal != null && System.currentTimeMillis() - pendingSince < PENDING_WAKE_MS
    }

    /**
     * The size limits may be exactly what just changed, so a clip already reported as stuck
     * deserves to be judged again rather than stay silently written off. Called on reload.
     */
    internal fun forgetStaleReport() {
        staleReported = null
    }

    // ------------------------------------------------------------------ local clipboard to peers
    private var lastClipStamp: Long = -1

    internal fun readLocalClip() {
        val cd: ClipData? = try {
            clipboard.primaryClip
        } catch (t: Throwable) {
            Logger.w("clipboard listener: getPrimaryClip failed", t)
            return
        }
        if (cd == null) {
            Logger.i("clipboard listener fired but getPrimaryClip() returned null (access denied in background?)")
            return
        }
        handleClip(cd, "listener")
    }

    /** Common path for the in-app listener and for clips pushed by the system_server hook. */
    internal fun handleClip(cd: ClipData, source: String) {
        val cfg = host.config()
        try {
            if (cd.itemCount == 0) return
            // In the foreground both the listener and the hook deliver the same clip. The system
            // stamps every clip; dropping the second delivery here saves a full re-read (and
            // re-hash) of a possibly 100 MB file.
            val desc = cd.description
            val stamp: Long = if (desc != null) desc.timestamp else 0
            synchronized(lock) {
                if (stamp > 0 && stamp == lastClipStamp) {
                    Logger.i("clip ($source): same clip already handled, skipped")   // proves the path is alive
                    return
                }
                lastClipStamp = stamp
            }
            // A clip can carry several items and each item text and/or a URI. Prefer the first
            // item that is a readable file (image copied from gallery/browser, file from a file
            // manager); otherwise the first non-empty text. A URI we cannot read is not sent as
            // its "content://..." string, because that is useless on the PC.
            var out: Any? = null
            var i = 0
            while (i < cd.itemCount && out == null) {
                val uri: Uri? = cd.getItemAt(i).uri
                i++
                if (uri == null) continue
                if (uri.toString() == lastRemoteUri) return       // our own clip, nothing to do
                // hashed now with the widest limit; the link-specific limit is checked at OFFER time
                val ref = Files.stat(ctx, uri, cfg.maxFileAny())
                if (ref != null) out = ref
            }
            if (out == null) {
                i = 0
                while (i < cd.itemCount && out == null) {
                    val item = cd.getItemAt(i)
                    i++
                    val cs: CharSequence? = if (item.text != null) item.text else item.coerceToText(ctx)
                    if (cs == null || cs.length == 0) continue
                    val itemUri: Uri? = item.uri
                    if (itemUri != null && cs.toString() == itemUri.toString()) continue   // just the URI as text
                    val text = cs.toString()
                    if (text.toByteArray(StandardCharsets.UTF_8).size > cfg.maxBytes) {
                        Logger.i("text too long, skipped (" + text.length + " chars)")
                        continue
                    }
                    out = text
                }
            }
            if (out == null) {
                Logger.i("clip ($source): nothing usable in it")
                return
            }
            val found: Any = out
            val h = hashOf(found)
            synchronized(lock) {
                if (h == lastRemoteHash || wasSentByUs(h)) return   // echo / duplicate
                pendingLocal = found
                // Unconditionally, even for content that was in the slot before: the user copying
                // something is a deliberate new attempt and deserves the full window. Only the
                // re-entry in requeue() preserves the original stamp.
                pendingHash = h
                pendingSince = System.currentTimeMillis()
                // Version stamp, for both kinds, because a file needs one just as much as text does, so
                // that HELLO and catch-up can reason about "what do I hold" for a file on the
                // clipboard the same way they do for text.
                clipTs = System.currentTimeMillis()
                clipFrom = Node.id()
                clipSha = h
                clipText = if (found is String) found else null
                clipFile = if (found is Files.Ref) found else null
            }
            Logger.i("clip ($source): " +
                    (if (found is Files.Ref) found.toString() else "text " + (found as String).length + " chars"))
            // copying something new while a file is still moving: stop that transfer first, and
            // withdraw the offers that are now about content nobody wants
            host.supersede(h)
            host.wake()                                // send now (reconnect if needed)
        } catch (t: Throwable) {
            Logger.w("read clipboard failed", t)
        }
    }

    /**
     * Put one text clip on one link.
     *
     * The file half of the same job is `FileExchange.offer`: a file is announced by hash and
     * only crosses the wire if the peer answers WANT, so the two do not share a code path past this
     * point. What they do share is [markSent], which is the device saying "this content came
     * from us" so that any peer echoing it back is ignored.
     */
    internal fun sendText(link: Link, text: String) {
        val h = Crypto.sha256Hex(normalise(text) ?: throw NullPointerException("normalise"))
        markSent(h)
        val j = JSONObject()
        j.put("ts", clipTs)
        j.put("from", Node.id())
        val to = JSONArray()
        for (id in host.knownPeerIds()) to.put(id)
        j.put("to", to)
        j.put("forwarded", false)
        j.put("mime", "text/plain")
        j.put("sha256", h)
        j.put("data", text)
        link.connection().send(Connection.T_CLIP, j.toString().toByteArray(StandardCharsets.UTF_8))
        Logger.i("sent local clip to remote (" + text.length + " chars)")
    }

    // ------------------------------------------------------------------ peers to local clipboard

    internal fun onRemoteClip(l: Link, msg: JSONObject) {
        val cfg = host.config()
        // Null only when the key is absent, as optString(key, null) would give; opt() says that
        // without passing null where org.json declares a non-null fallback.
        val text: String? = if (msg.opt("data") == null) null else msg.optString("data")
        if (text == null || "text/plain" != msg.optString("mime", "text/plain")) return
        if (text.toByteArray(StandardCharsets.UTF_8).size > cfg.maxBytes) return
        val h = Crypto.sha256Hex(normalise(text) ?: throw NullPointerException("normalise"))
        if (h != msg.optString("sha256")) {
            // Worth a line rather than a silent drop: the two ends hash the *normalised* text, so a
            // mismatch means either a peer on an older build (different hashing rule) or something
            // rewriting the payload. Both would otherwise look identical from the outside, as "text
            // just does not arrive from that one device," with nothing in the log to explain why.
            // Windows logs the same event.
            Logger.w("clip from " + l.peerId() + ": hash mismatch, dropping")
            return
        }

        val remoteTs = msg.optLong("ts", 0)
        val remoteFrom = msg.optString("from", "")
        val forwarded = msg.optBoolean("forwarded", false)

        // Normalise ts into the local clock domain using the offset measured by PING/PONG: each
        // PONG carries the peer's two stamps beside our own, so `peer_clock = my_clock + offset`
        // tracks drift without any extra message.
        val normTs = remoteTs - l.clockOffset

        // Drop it if we have already processed this exact (ts, from, sha) triple, whichever route it
        // came by. The sha belongs in the key: two different clips made in the same millisecond by
        // the same device would otherwise share the same (ts, from) and the second would be dropped
        // as a false duplicate.
        if (seen(remoteTs, remoteFrom, h)) return

        synchronized(lock) {
            if (wasSentByUs(h) || h == lastRemoteHash) return

            // Version comparison: accept only if the incoming clip is strictly newer.
            if (clipTs > 0 && compareVersion(normTs, remoteFrom, clipTs, clipFrom) <= 0) return

            lastRemoteHash = h
            lastRemoteUri = null
            clipTs = normTs
            clipFrom = remoteFrom
            clipSha = h
            clipText = text        // kept so a third peer that reconnects behind can be given it
            clipFile = null
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("clipsync", text))
        Logger.i("applied remote clip locally (" + text.length + " chars" +
                (if (forwarded) ", forwarded" else "") + ")")

        // Forwarding: pass it on to peers the sender could not reach, exactly one hop. The copy
        // goes out marked forwarded, and a copy that arrives marked forwarded is not passed on;
        // that is the whole of the loop protection, and it is why the chain is one hop and not two.
        if (!forwarded) {
            val toArr: JSONArray? = msg.optJSONArray("to")
            val recipients: MutableSet<String> = java.util.HashSet()
            if (toArr != null) {
                for (i in 0 until toArr.length()) recipients.add(toArr.optString(i, ""))
            }
            recipients.add(remoteFrom)          // the originator already has it
            recipients.add(Node.id())           // we have it now too

            for (peer in host.links()) {
                val peerId = peer.peerId()
                if (!peer.isOpen() || peerId == null) continue
                if (recipients.contains(peerId)) continue
                if (peer === l) continue             // don't send back to the link it came from
                try {
                    val fwd = JSONObject()
                    fwd.put("ts", remoteTs)         // original ts, not normalised
                    fwd.put("from", remoteFrom)
                    val fwdTo = JSONArray()
                    // include everyone the originator listed plus us and the forwarded-to peer
                    for (r in recipients) fwdTo.put(r)
                    fwdTo.put(peerId)
                    fwd.put("to", fwdTo)
                    fwd.put("forwarded", true)
                    fwd.put("mime", "text/plain")
                    fwd.put("sha256", h)
                    fwd.put("data", text)
                    peer.connection().send(Connection.T_CLIP,
                            fwd.toString().toByteArray(StandardCharsets.UTF_8))
                    Logger.i("forwarded clip to " + peer.connection().peer)
                } catch (e: Exception) {
                    Logger.i("forward to " + peer.connection().peer + " failed: " + e)
                }
            }
        }
    }

    /**
     * A file finished downloading: adopt it as what this device now holds, and put it on the
     * clipboard.
     *
     * @return false when the file turned out to be our own echo or already the current clip, in
     *         which case nothing was written and the caller should stop, because the bytes are on disk and
     *         that is all that was wanted.
     */
    internal fun adoptFile(from: Link?, p: Files.Partial, uri: Uri, ref: Files.Ref?): Boolean {
        synchronized(lock) {
            if (wasSentByUs(p.sha256) || p.sha256 == lastRemoteHash) return false
            lastRemoteHash = p.sha256
            lastRemoteUri = uri.toString()
            // What this device now holds, for HELLO and for catch-up. A received file's `seq` is
            // the originator's wall clock, which is the same kind of number a clip's `ts` is, so
            // it can stand in as the version; the id of whoever we got it from stands in for the
            // author, which only matters as a tie-break between two identical timestamps.
            if (p.seq > 0) {
                clipTs = p.seq
                clipFrom = from?.peerId() ?: ""
            }
            clipSha = p.sha256
            clipText = null
            clipFile = ref
        }
        clipboard.setPrimaryClip(ClipData.newUri(ctx.contentResolver, p.name, uri))
        Logger.i("saved remote file locally (" + p.mime + " " + p.name + " " + p.size + " bytes) as " + uri)
        return true
    }

    /**
     * A file whose transfer died with the offer unanswered goes back into the pending slot.
     *
     * Only if nothing newer has taken it: a clip the user copied while the transfer was dying is
     * the one that should win.
     */
    internal fun requeue(unanswered: Files.Ref) {
        synchronized(lock) {
            if (pendingLocal != null) return
            pendingLocal = unanswered
            // The stamp is preserved when this is the same content coming back, which is the
            // normal case here: a large file whose transfer keeps dying re-enters the slot every
            // few minutes, and a fresh stamp each time would make PENDING_WAKE_MS unreachable.
            if (unanswered.sha256 != pendingHash) {
                pendingHash = unanswered.sha256
                pendingSince = System.currentTimeMillis()
            }
        }
    }

    /**
     * Let go of the pending clip once every live link has it.
     *
     * **This is what keeps the radio asleep.** With the screen off, a dialer or a browse runs only
     * while [worthWaking] says so (SyncService waits on `(!screenOn && !worthWaking()) || !hasNetwork`),
     * and [worthWaking] is true while `pendingLocal` is set and younger than [PENDING_WAKE_MS]. This
     * method is what clears `pendingLocal` once delivery is actually done, rather than on the first
     * flush, since the clip is a broadcast to every link and not something a single flush consumes.
     * Without a release every copy would keep the radio waking for the whole of [PENDING_WAKE_MS]:
     * screen off drops the links, the gate passes, each dialer reconnects, runs a burst, returns
     * "success" so skips its back-off, and does it again, with the same clip re-sent every cycle
     * because each new link starts with a clean `sentHash`.
     *
     * Delivered to every live link is the condition, rather than "to one": a clip that has reached
     * the PC but not the tablet is not delivered while both are connected. A peer that is not
     * connected at all does not hold the slot: it catches up by version when it next connects
     * ([catchUp]), and [PENDING_WAKE_MS] bounds how long an unreachable peer can keep the radio
     * waking anyway. With no live link at all nothing is released, since nothing has been
     * delivered.
     */
    internal fun releasePending(hash: String) {
        var all = true
        var live = 0
        for (l in host.links()) {
            if (!l.isOpen()) continue
            live++
            if (hash != l.sentHash()) {
                all = false
                break
            }
        }
        if (!all || live == 0) return
        synchronized(lock) {
            val p = pendingLocal
            if (p != null && hash == hashOf(p)) pendingLocal = null
        }
    }

    // ------------------------------------------------------------------ catch-up

    /**
     * Give a peer what it is missing, the moment the handshake says it is missing something.
     *
     * Delivery cannot depend on [pendingLocal] alone: that slot is released as soon as
     * every *currently live* link has taken the clip, which is correct for its own job of
     * deciding when the radio may sleep but useless as a record of what this device holds. Without
     * this method, a tablet offline when the phone copied something would reconnect to a phone with
     * an empty slot and simply stay behind, with nothing to retry.
     *
     * Three reasons to say nothing, in the order they are cheapest to check:
     *
     * - the peer's hash already matches ours, so it has this exact content;
     * - the pending slot still holds it, in which case [Link.deliver] is about to send
     *   it anyway and this would only make it arrive twice;
     * - the peer's version is newer or equal by the same comparison every other path uses, so
     *   sending ours would push the network backwards.
     *
     * The third does not apply to a file, and the `text != null` guard below is where that
     * is enforced. A received file does carry a version: [adoptFile] borrows the
     * originator's `seq` for [clipTs], so it takes its place in the text ordering and
     * goes out in HELLO, but no file is ever compared against another file by it, because two
     * copies of one file are the same bytes and a digest settles that question outright. So an OFFER
     * is the only way to ask, and it costs one frame: a peer that already has it answers HAVE.
     */
    internal fun catchUp(l: Link, peerTs: Long, peerSha: String?) {
        var text: String? = null
        var file: Files.Ref? = null
        var ts: Long = 0
        var from: String? = null
        var sha: String? = null
        synchronized(lock) {
            text = clipText; file = clipFile; ts = clipTs; from = clipFrom; sha = clipSha
        }
        val clip: Any = text ?: file ?: return
        if (peerSha != null && !peerSha.isEmpty() && peerSha == sha) return
        val pending = pendingLocal
        if (pending != null && hashOf(pending) == sha) return
        if (text != null && peerTs > 0 && ts > 0 &&
                compareVersion(ts, from, peerTs, l.peerId()) <= 0) return
        try {
            host.sendClip(l, clip)
            Logger.i("catch-up to " + l.connection().peerLabel + ": sent what it was missing")
        } catch (e: Exception) {
            Logger.i("catch-up to " + l.connection().peerLabel + " failed: " + e)
        }
    }

    // ------------------------------------------------------------------ diagnosis

    /**
     * Say once, when a clip has been waiting long enough to stop justifying wake-ups, why it has not
     * gone out.
     *
     * This diagnosis is easy to mistake for a frozen-process warning, so it is worth being clear
     * about the distinction. A phone does not
     * reach deep sleep until it has been locked for some time, so a clip still retrying by then is
     * not evidence that the system is interfering; the system suspending an idle process is the
     * system being right. It is evidence that **delivery is failing**, and the three ways it can
     * fail want three different things from the user, which is why this branches rather than printing
     * one line:
     *
     * - **no peer connected**: a reachability problem; the sheet already says why each target
     *   is down, so this only points at it;
     * - **connected, but the clip never went out**: sends are failing, and the log above this
     *   line has the exception;
     * - **connected and offered, and still here**: the transfer started and did not finish.
     *   For a file this is almost always the interesting one: a link that drops before the file
     *   is through will retry for as long as the clip is on the clipboard, and the fix is the
     *   size limit, not the battery settings.
     */
    internal fun reportStalePending() {
        val p = pendingLocal ?: return
        val age = System.currentTimeMillis() - pendingSince
        if (age < PENDING_WAKE_MS) return
        val h = hashOf(p)
        if (h == staleReported) return
        staleReported = h

        val mins = Math.max(1L, age / 60_000)
        val isFile = p is Files.Ref
        // Bytes, not MB: a small file would read as "0 MB", and the size is the point of the line.
        val what = if (p is Files.Ref) p.name + " (" + p.size + " bytes)"
        else (p as String).length.toString() + " characters of text"
        var live = 0
        var owing = 0
        var limit = Long.MAX_VALUE
        for (l in host.links()) {
            if (!l.isOpen()) continue
            live++
            limit = Math.min(limit, host.limitOn(l.connection()))
            if (h != l.sentHash()) owing++
        }
        val head = "$what has not been delivered in $mins min"
        if (live == 0) {
            Logger.w(head + ": no peer has been connected. It stops waking the radio now and goes out" +
                    " as soon as one is; open the app's peer list to see why each target is down.")
        } else if (owing > 0) {
            Logger.w(head + ": " + owing + " of " + live + " connected peer(s) never received it, so a" +
                    " send is failing, and the reason is in the lines above this one.")
        } else if (isFile) {
            Logger.w(head + ": every peer was offered it and no transfer finished. A link that drops" +
                    " before the whole file is through will keep retrying for as long as this file is" +
                    " on the clipboard; the limit in use on these link(s) is " + mb(limit) + " MB, and" +
                    " lowering it is what ends this. Not a battery-optimisation problem: a system" +
                    " that suspends an idle app is behaving correctly.")
        } else {
            Logger.w(head + ": every peer took it, yet it is still queued. This should not happen;" +
                    " please report it with the log.")
        }
    }

    internal companion object {
        /**
         * How long an undelivered clip may keep the radio awake with the screen off.
         *
         * Without a bound this is a permanent cost, not a transient one: the slot is released only
         * when a live link has taken the clip, so with no peer reachable at all it never clears, the
         * screen-off gate never closes, and every dialer goes on dialling, once a minute each once the
         * back-off ladder tops out, for as long as the phone is locked. A peer that is simply switched
         * off would otherwise drain the phone all night.
         *
         * The clip is **not** discarded when this expires. It stops being a reason to wake the
         * radio, and is still delivered the moment a link comes up for any other reason, which is the
         * honest split: undelivered is not the same as unwanted.
         *
         * It is also the moment the device has learned something worth saying, so it is where
         * [reportStalePending] speaks. Measuring [pendingSince] from the clip's
         * *first* entry into the slot, rather than each re-entry, is what makes this reachable even
         * for a file that re-enters the slot on every failed transfer.
         */
        private const val PENDING_WAKE_MS: Long = 5 * 60_000L

        /** The content hash of a clip, whichever of the two kinds it is. */
        internal fun hashOf(o: Any): String {
            return if (o is Files.Ref) o.sha256
            else Crypto.sha256Hex(normalise(o as String) ?: throw NullPointerException("normalise"))
        }

        /**
         * One line ending, so that one piece of text has one hash.
         *
         * The hash of a clip is a value on the wire: the sender puts it in the frame, the receiver
         * recomputes it and drops anything that disagrees, and both ends advertise it in HELLO to decide
         * who is behind. Windows puts CRLF on the clipboard and Android does not, so without normalising
         * first, the same text hashed as typed would produce two different answers on the two platforms,
         * so every clip from a PC would look corrupt to a peer computing it the other way, and the
         * catch-up check "do we already agree" would never answer yes, re-sending the same paragraph on
         * every single reconnect.
         *
         * Normalising is the one rule that has to be identical at both ends, so it lives in one method
         * and every hash goes through it: the send, the check on receipt, and [clipSha]. The text
         * itself is sent unchanged; this decides what is *hashed*, not what is pasted.
         */
        internal fun normalise(text: String?): String? {
            return text?.replace("\r\n", "\n")
        }

        /**
         * Compare two clip versions lexicographically by (ts, from).
         *
         * @return positive if a is newer, negative if b is newer, 0 if equal
         */
        private fun compareVersion(tsA: Long, fromA: String?, tsB: Long, fromB: String?): Int {
            val c = java.lang.Long.compare(tsA, tsB)
            if (c != 0) return c
            val a = fromA ?: ""
            val b = fromB ?: ""
            return a.compareTo(b)
        }

        private fun mb(bytes: Long): Long {
            return bytes / (1024 * 1024)
        }
    }
}
