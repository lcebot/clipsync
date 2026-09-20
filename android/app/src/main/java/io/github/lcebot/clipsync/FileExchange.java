package io.github.lcebot.clipsync;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about moving a file between this device and its peers.
 *
 * <p>The protocol it implements, in one paragraph: a file is announced by <b>hash</b>
 * (OFFER), never by sending it. The receiver answers HAVE if it already holds those bytes, SKIP if
 * it will not take them, or WANT with the chunk ranges it is missing, which is what makes a
 * transfer resumable, because "missing" is read from a chunk map on disk and survives a dropped
 * link, a reboot and an ABORT. The bytes then cross on separate data connections, several in
 * parallel, opened by exactly one of the two ends, and the transfer finishes with END or ABORT.
 *
 * <p>What lives here is therefore: the two small registries of what can still be served
 * ({@code offered}, {@code sent}), the one of what is being received ({@code partials}), the count
 * of streams pushing at us ({@code pushing}), the headers files arrived with ({@code inboundOffers}),
 * the at-most-one-each-way transfer slots, and the three server-side loops: serving a pull, serving
 * a relayed pull, and receiving a push. It also owns the debounced re-ask ({@link #scheduleReask}),
 * which is what stops a peer-driven download that ran out of streams from waiting for the peer's
 * next OFFER. It also owns {@link RelayCoordinator}, because a relay is a file transfer seen from
 * one side.
 *
 * <p><b>What it does not do.</b> It never reads or writes the system clipboard and never decides
 * what this device "holds": a file that finishes downloading is handed to {@link ClipboardBridge},
 * which decides whether it is news. It does not know about dialling, back-off or status. It does not
 * own the peer map; it asks {@link Host} for links, so that "which peers exist" has one answer in
 * one place.
 *
 * <p><b>Locking.</b> It does <em>not</em> take the device lock, and that is the whole point of
 * routing every clip-state question through {@link ClipboardBridge} ({@code isEcho},
 * {@code adoptFile}, {@code requeue}, {@code seen}): those methods take the lock themselves, so the
 * one invariant that spans the two classes, that "what this device holds" is consistent with "what it
 * last put on the clipboard", is still enforced by one monitor rather than by two that have to be
 * taken in the right order. Inside here, each registry keeps the monitor it always had
 * ({@code offered} and {@code sent} share one, {@code inboundOffers} and {@code partials} have their
 * own) and the transfer slots are atomics; no two of them are ever held at once.
 */
final class FileExchange implements RelayCoordinator.Host {

    /** What the file exchange needs from the device. */
    interface Host {
        Config config();

        /** Every live link. */
        Iterable<Link> links();

        /** The live link to one peer id, or null. */
        Link linkTo(String peerId);

        /** Direct ∪ indirect peer ids: the {@code to} set an OFFER header carries. */
        java.util.Set<String> knownPeerIds();
    }

    private final Context ctx;
    private final FileCache cache;
    private final ClipboardBridge clip;
    private final Host host;
    private final RelayCoordinator relay;

    FileExchange(Context ctx, FileCache cache, ClipboardBridge clip, Host host) {
        this.ctx = ctx;
        this.cache = cache;
        this.clip = clip;
        this.host = host;
        this.relay = new RelayCoordinator(ctx, this);
    }

    RelayCoordinator relay() {
        return relay;
    }

    @Override public Config config() {
        return host.config();
    }

    @Override public Iterable<Link> links() {
        return host.links();
    }

    @Override public Link linkTo(String peerId) {
        return host.linkTo(peerId);
    }

    /** File-size limit for one session: LAN peers get the larger one. */
    static long limitFor(Config cfg, Connection c) {
        return c.lanPeer ? cfg.maxFileBytesLocal : cfg.maxFileBytes;
    }

    // ---- files: an OFFER (hash) is answered with WANT {ranges}, HAVE, or SKIP, then chunks flow over parallel data connections ----
    /** Files we have offered and may be asked for (bounded; nothing is held in memory, only URIs). */
    private final java.util.LinkedHashMap<String, Files.Ref> offered = new java.util.LinkedHashMap<>();
    /** Files fully uploaded recently; a late re-WANT (lost stream on the PC side) is served from here. */
    private final java.util.LinkedHashMap<String, Files.Ref> sent = new java.util.LinkedHashMap<>();
    /**
     * The OFFER header a file arrived with, kept until the file is complete.
     *
     * <p>It carries the two facts forwarding needs and nothing else has: who already has this file
     * ({@code to}), and whether it had already been passed on once ({@code forwarded}). The download
     * that answers an OFFER finishes minutes later and on another thread, so the frame cannot simply
     * be read at that point. Bounded like {@code offered}, for the same reason.
     */
    private final java.util.LinkedHashMap<String, JSONObject> inboundOffers = new java.util.LinkedHashMap<>();

    @Override public void rememberOffered(String sha, Files.Ref f) {
        synchronized (offered) {
            offered.put(sha, f);
            while (offered.size() > 8) offered.remove(offered.keySet().iterator().next());
        }
    }

    @Override public JSONObject inboundOffer(String sha) {
        synchronized (inboundOffers) { return inboundOffers.get(sha); }
    }

    @Override public boolean cached(String sha) {
        return cache.get(sha) != null;
    }

    @Override public Files.Partial partial(String sha) {
        return partials.get(sha);
    }

    /**
     * The transfers in flight: at most one each way for the whole device, still.
     *
     * <p>Atomic references, and installed with {@code getAndSet}, because "at most one" stopped being
     * enforced by there being one peer. Two dialer threads can reach {@code onWant} for different
     * peers at the same moment, both pass a plain null-check, and both assign: the loser's eight
     * worker threads keep pushing bytes at a peer nobody is tracking, its completion callback never
     * matches, and neither {@code stopTransfersOn} nor {@code abortAll} can see it to stop it. A
     * volatile field makes that race visible; it does not make it safe.
     */
    private final java.util.concurrent.atomic.AtomicReference<Transfer> upload = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<Transfer> download = new java.util.concurrent.atomic.AtomicReference<>();
    private static final int DOWNLOAD_RETRIES = 3;

    /**
     * How often this device re-asks a peer for the chunks it is still missing, in one session.
     *
     * <p>Same name and same value as Python's {@code WANT_RETRIES}, and it has to be: the budget is
     * per receiver, so a phone that gave itself more tries than the PC would simply be the noisier
     * of the two ends of the same protocol. Spent through {@link Files.Partial#claimReask(int)}.
     */
    static final int WANT_RETRIES = 3;

    /**
     * How long a re-ask waits before it goes out. Python's {@code threading.Timer(2.0, ...)} in
     * {@code _schedule_reask}, to the millisecond.
     *
     * <p>The debounce is the reason the ordinary case produces no WANT at all: eight streams carry
     * one file, one of them finishes its stripe early, and for a moment it looks like "every stream
     * is out and the file is incomplete". Two seconds later the peer has opened the rest and
     * {@link #reask} sees a busy transfer and says nothing.
     */
    private static final long REASK_DEBOUNCE_MS = 2000;

    static List<int[]> rangesOf(JSONArray a, int n) {
        List<int[]> out = new ArrayList<>();
        if (a == null) { out.add(new int[]{0, n}); return out; }
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r != null && r.length() == 2) out.add(new int[]{Math.max(0, r.optInt(0)), Math.min(n, r.optInt(1))});
        }
        return out;
    }

    private static JSONArray rangesJson(List<int[]> rs) {
        JSONArray a = new JSONArray();
        for (int[] r : rs) a.put(new JSONArray().put(r[0]).put(r[1]));
        return a;
    }

    // ------------------------------------------------------------------ sending a file

    /**
     * Announce one file on one link: the file half of "put this clip on that peer".
     *
     * <p>Two-step: the hash goes out first and the bytes only follow if the peer answers WANT. A
     * file over this link's limit is not announced at all, and is deliberately <b>not</b> marked as
     * sent: marking it would make a peer's own copy of the same file look like our echo and be
     * ignored.
     */
    void offer(Link link, Files.Ref f) throws Exception {
        Connection c = link.connection();
        long limit = limitFor(config(), c);
        if (f.size > limit) {
            Logger.i("not offering " + f + ": over the " + (c.lanPeer ? "LAN" : "internet")
                    + " limit (" + limit / (1024 * 1024) + " MB)");
            return;
        }
        clip.markSent(f.sha256);
        rememberOffered(f.sha256, f);
        c.sendJson(Connection.T_OFFER, header(f, false));
        Logger.i("offered local file to remote: " + f);
    }

    /**
     * The OFFER header for a file we are putting on the wire.
     *
     * @param forwarded false when this device is the origin of the clip, true when it is passing on
     *                  a file it received. The flag caps forwarding at one hop, exactly as the CLIP
     *                  frame's does: without it a file in a network that is not a tree comes back to
     *                  a device that has moved on, and lands on the clipboard over what the user
     *                  copied since. A hop count would say more; a boolean is what the text path
     *                  already uses, and two mechanisms for one rule is how they drift apart.
     */
    private JSONObject header(Files.Ref f, boolean forwarded) throws Exception {
        return header(f, forwarded, System.currentTimeMillis(), Node.id());
    }

    /**
     * The same header, but keeping the originator's {@code seq} and {@code from}.
     *
     * <p>A forwarder must not restamp these. They are the two halves of the seen-set key, so a file
     * that reaches a device by two routes has to arrive carrying the same pair both times or the
     * second copy looks like a new one and lands on the clipboard. The text path has always passed
     * the original through (see the CLIP forwarding arm); files now do too.
     */
    private JSONObject header(Files.Ref f, boolean forwarded, long seq, String fromId) throws Exception {
        JSONObject hdr = new JSONObject();
        hdr.put("seq", seq);
        hdr.put("name", f.name);
        hdr.put("mime", f.mime);
        hdr.put("size", f.size);
        hdr.put("sha256", f.sha256);
        hdr.put("from", fromId);
        hdr.put("forwarded", forwarded);
        JSONArray to = new JSONArray();
        for (String id : host.knownPeerIds()) to.put(id);
        hdr.put("to", to);
        return hdr;
    }

    /**
     * A peer's suggested file name, reduced to something that can only ever name a file.
     *
     * <p>The name in an OFFER is chosen by the sender and is the one piece of it that reaches the
     * filesystem, so it is treated as hostile. {@code File.getName()} alone is not enough on
     * Android: a backslash is an ordinary character here, so {@code "..\..\evil.so"} survived it
     * untouched and went to MediaStore as a display name. Same rules as the Windows side's
     * {@code safe_name}, deliberately, because the two ends must reduce the same input to the same string,
     * or a file's name depends on which device received it.
     */
    static String safeName(String name) {
        if (name == null) return "clip";
        // Last segment taken by hand rather than with File.getName(), which differs from Python's
        // os.path.basename on a name ending in a separator: "a/b/" is "b" to one and "" to the
        // other, so the same OFFER would land under two different names depending on which end
        // received it. Splitting explicitly makes both cases resolve to "clip".
        String raw = name.replace('\\', '/');
        int cut = raw.lastIndexOf('/');
        String s = (cut < 0 ? raw : raw.substring(cut + 1)).trim();
        if (s.equals(".") || s.equals("..")) return "clip";     // before filtering: neither has a filtered char
        s = s.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_");
        if (s.length() > 150) s = s.substring(0, 150);
        return s.isEmpty() ? "clip" : s;
    }

    // ------------------------------------------------------------------ receiving a file

    /**
     * A peer offers a file: re-use our cached copy (HAVE), refuse (SKIP), let a better-placed LAN
     * peer fetch it for us, or ask for the chunks we miss (WANT) and pull them.
     */
    void onOffer(Link l, JSONObject hdr) throws Exception {
        Config cfg = config();
        Connection c = l.connection();
        String sha = hdr.optString("sha256");
        String name = safeName(hdr.optString("name", "clip"));
        long size = hdr.optLong("size", -1);
        long seq = hdr.optLong("seq", 0);
        // A file's place in the seen-set, keyed by the sender's seq the way a clip is keyed by its
        // ts. An OFFER that has already been dealt with is dropped outright: not answered, not
        // written to the clipboard, not passed on, which is what stops a device coming back from a
        // week offline and re-offering last week's file into a network that has moved on.
        if (clip.seen(seq, hdr.optString("from", ""), sha)) {
            Logger.i("offer: " + name + " already seen, ignored");
            return;
        }
        if (hdr.optBoolean("forwarded", false))
            Logger.i("offer: " + name + " (forwarded)");
        // Remembered because the decision it feeds, whether to pass this file on and to whom,
        // is taken much later, in finishDownload, by which time the frame that carried it is gone.
        synchronized (inboundOffers) {
            inboundOffers.put(sha, hdr);
            while (inboundOffers.size() > 8) inboundOffers.remove(inboundOffers.keySet().iterator().next());
        }
        // the cursor only advances once we actually have the file (HAVE / SKIP / download done), so
        // that after a dropped connection the peer's catch-up OFFER triggers the resume
        if (clip.isEcho(sha)) {
            c.sendJson(Connection.T_HAVE, Frames.shaMsg(sha));
            return;
        }
        Uri cached = cache.get(sha);
        if (cached != null) {
            c.sendJson(Connection.T_HAVE, Frames.shaMsg(sha));
            clip.noteCached(sha, cached);
            // Deliberately NOT put on the clipboard. "We already have these bytes" is not "the user
            // just copied them": an offer can arrive long after the fact, from a peer reconnecting and
            // catching up, or a file coming back round a network that is not a tree, and writing
            // it here would overwrite whatever the user copied since. That is the flip-flop the
            // forwarded flag and the seen-set exist to stop, and it would walk straight past both,
            // because this branch returns before either is consulted. HAVE + touch is the whole of
            // what the peer needs. Python does the same (clipsync.py, on_offer's cache-hit arm).
            Logger.i("offer: " + name + " already cached, re-used " + cached);
            cache.prune(cfg.keepHours, cfg.keepMaxBytes, cached, inFlight());
            return;
        }
        long limit = limitFor(cfg, c);
        if (size < 0 || size > limit) {
            String why = size + " bytes > " + limit + " (" + (c.lanPeer ? "LAN" : "internet") + " limit)";
            c.sendJson(Connection.T_SKIP, Frames.shaMsg(sha).put("reason", why));
            Logger.i("offer: " + name + " skipped, " + why);
            return;
        }

        // Relay election: an OFFER from outside the LAN that carries a recipient list is one several
        // nodes here are about to pull separately. Every node computes the same priority order, the
        // best-placed one pulls from the origin and the rest ask it to pass the file on; a RELAY_ASK
        // going out is the whole of our part until the relay offers it back to us.
        if (relay.intercept(l, sha, name, hdr)) return;

        // Normal WANT path (we are the best candidate, or no relay election applies).
        wantFromPeer(l, sha, name, hdr, size, seq);
    }

    /** The common WANT path: prepare a Partial, send WANT, start data connections if we drive. */
    @Override public void wantFromPeer(Link l, String sha, String name, JSONObject hdr, long size, long seq) throws Exception {
        Connection c = l.connection();
        // a newer offer supersedes a download still running (the peer already stopped serving it)
        Transfer d = download.get();
        if (d != null && !d.sha256.equals(sha)) { d.abort(); d.partial.keep(); noteAborted(d.sha256); }
        clearAbort(sha);                // this hash is being transferred again, whatever happened before
        // One Partial per hash for the whole device, from a registry rather than from the disk.
        // Two peers offering the same file is the normal way a relayed clip arrives, and both would
        // otherwise create their own: two pending MediaStore rows, and two instances writing the same
        // chunk map over each other until whichever finalises second fails its hash check.
        Files.Partial p = partialFor(sha, name, hdr.optString("mime", "application/octet-stream"), size, seq);
        if (p == null) {
            c.sendJson(Connection.T_SKIP, Frames.shaMsg(sha).put("reason", "cannot open a file for it"));
            return;
        }
        List<int[]> missing = p.missing();
        if (missing.isEmpty()) {
            // Already complete on disk, so ask for nothing. An empty range list means "everything" to
            // the peer, so sending it would re-stream the whole file to be discarded chunk by chunk.
            finishDownload(l, p);
            c.sendJson(Connection.T_HAVE, Frames.shaMsg(sha));
            return;
        }
        // Where a re-ask for this file goes, and a fresh budget of them: a new OFFER means the peer
        // is driving this transfer again, so the three it may already have spent stalling last time
        // must not be held against it. Both mirror _want_from_peer (pt.origin / pt.retries = 0).
        wantOrigin.put(sha, l);
        p.resetReasks();
        c.sendJson(Connection.T_WANT, Frames.shaMsg(sha).put("ranges", rangesJson(missing)));
        Logger.i("offer: " + name + " (" + size + " bytes), want " + (p.haveCount() == 0 ? "all" : (p.n - p.haveCount()) + "/" + p.n + " chunks (resume)"));
        // Only one end opens the data connections (Connection.drivesTransfer). When it is not this
        // one, the WANT above is not quite the whole of our part: the peer pushes the chunks over
        // connections it opens and serveData() receives them, and if those end with the file still
        // incomplete, the debounced re-ask (scheduleReask) asks for the rest.
        if (c.drivesTransfer()) startDownload(l, p, 0);
    }

    /**
     * The one {@link Files.Partial} for this hash, created on first sight and shared after.
     *
     * <p>Keyed by hash because the file is: {@code files/partial/<sha>.json} was always the chunk
     * map's path, so two instances for one hash were always two writers of one file. With one peer
     * that could not happen; with several it is the common case.
     */
    private final java.util.Map<String, Files.Partial> partials = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The link a file was WANTed over: where a re-ask has to go.
     *
     * <p>Python keeps this as {@code Partial.origin}; here it is a map beside the Partials instead,
     * because {@link Files.Partial} lives in the storage layer and knows about MediaStore rows and
     * chunk maps, not about {@link Link}s. Same fact, same lifetime (set when the WANT goes out,
     * dropped when the file lands), different shelf.
     *
     * <p>Overwritten rather than merged when a second peer offers the same hash, which is right: the
     * newest offer is the one whose peer is actually sending, and a WANT to a peer that has moved on
     * is a frame that will be answered with ABORT at best.
     */
    private final java.util.Map<String, Link> wantOrigin = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Hashes whose transfer has been called off, with the moment it happened.
     *
     * <p>The mirror of Python's {@code SyncState.aborted}. A {@link Transfer} object only exists for
     * a transfer this end is actively driving, so a peer-driven download that gets ABORTed has
     * nothing else to record the fact, and without this map, a later re-ask would go out for a
     * transfer both ends have already given up on.
     *
     * <p>Aged out after an hour, like Python's, because "aborted" is about the session and not about
     * the file: the same hash offered again tomorrow is a new transfer, and {@link #clearAbort} says
     * so explicitly the moment a fresh WANT goes out for it.
     */
    private final java.util.LinkedHashMap<String, Long> aborted = new java.util.LinkedHashMap<>();
    private static final long ABORT_MEMORY_MS = 3600_000L;

    /** Note that {@code sha} has been called off, by either end. */
    private void noteAborted(String sha) {
        if (sha == null || sha.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (aborted) {
            aborted.values().removeIf(t -> now - t >= ABORT_MEMORY_MS);
            aborted.put(sha, now);
            while (aborted.size() > 16) aborted.remove(aborted.keySet().iterator().next());
        }
    }

    /** A new WANT for this hash: whatever was called off before, this is a new transfer. */
    private void clearAbort(String sha) {
        synchronized (aborted) { aborted.remove(sha); }
    }

    private boolean isAborted(String sha) {
        synchronized (aborted) {
            Long t = aborted.get(sha);
            return t != null && System.currentTimeMillis() - t < ABORT_MEMORY_MS;
        }
    }

    /**
     * @return the shared Partial, or null when one cannot be made, because a MediaStore insert that fails
     *         because the configured path is gone or storage is full. Caught here rather than thrown,
     *         because the caller can answer SKIP and keep the link: letting it out of the control
     *         loop would tear down a working session over one undeliverable file, and the peer would
     *         learn nothing about why. The reason goes to the log; the peer gets a refusal it can
     *         act on.
     */
    private Files.Partial partialFor(String sha, String name, String mime, long size, long seq) {
        Files.Partial p = partials.get(sha);
        if (p != null) return p;
        synchronized (partials) {
            p = partials.get(sha);
            if (p != null) return p;
            p = Files.Partial.resume(ctx, sha);
            if (p == null) try {
                p = Files.Partial.create(ctx, config().relativePath, name, mime, size, sha, seq);
            } catch (Exception e) {
                Logger.w("cannot open a file for " + name + " under " + config().relativePath + ": " + e);
                return null;
            }
            if (p != null) partials.put(sha, p);
            return p;
        }
    }

    private void startDownload(Link l, Files.Partial p, int attempt) {
        Transfer t = Transfer.download(ctx, config(), PeerRoute.of(l.connection()), p, (tr, complete) -> {
            download.compareAndSet(tr, null);
            if (complete) {
                finishDownload(l, p);
                // the link that offered it is the link whose cursor moves
            } else if (!tr.isAborted() && l.isOpen() && attempt < DOWNLOAD_RETRIES) {
                Logger.i("retrying " + p + " (" + (attempt + 1) + "/" + DOWNLOAD_RETRIES + ")");
                startDownload(l, p, attempt + 1);
            } else {
                p.keep();          // resumed on the next OFFER of the same file
            }
        });
        // Streaming relay: when this end drives the download, the writes happen inside Transfer, so
        // this callback is how the service learns the first chunk has landed and can offer the file
        // to waiters immediately, rather than only once it is complete. The push direction gets the
        // same signal directly, in serveData.
        t.onFirstChunk(() -> {
            if (relay.hasWaiters(p.sha256)) relay.earlyOfferToWaiters(p.sha256, p);
        });
        Transfer displaced = download.getAndSet(t);
        if (displaced != null && displaced != t) displaced.abort();   // never leave one running untracked
        t.start();
    }

    /** @param l the link the file came in on, or null when a peer pushed it over data connections */
    @Override public void finishDownload(Link l, Files.Partial p) {
        Config cfg = config();
        try {
            partials.remove(p.sha256);              // the file exists now; a later OFFER hits the cache
            wantOrigin.remove(p.sha256);            // nothing left to re-ask for
            Uri uri = p.finalizeFile();
            cache.put(p.sha256, uri, p.name, p.mime, p.size);
            // Offer it to any waiters that asked us to relay it.
            relay.offerToWaiters(p.sha256);
            forwardFile(l, p.sha256);
            Files.Ref ref = refFor(p.sha256);        // outside the lock: it may touch the provider
            if (!clip.adoptFile(l, p, uri, ref)) return;
            cache.prune(cfg.keepHours, cfg.keepMaxBytes, uri, inFlight());
        } catch (Exception e) {
            Logger.w("receive file failed: " + e.getMessage());
        }
    }

    /** Hashes a transfer is still using, which {@code prune} must not delete out from under it. */
    private java.util.Set<String> inFlight() {
        java.util.Set<String> s = new java.util.HashSet<>(partials.keySet());
        s.addAll(pushing.keySet());
        return s;
    }

    /**
     * Pass a received file on to the peers the sender could not reach.
     *
     * <p>This is what lets a file cross a network where A reaches the phone over the internet and the
     * phone reaches C over the LAN: without it, the phone would receive the file and C would never
     * hear of it. The rules mirror the text
     * path's, for the same reasons and so that the two cannot drift:
     *
     * <ul>
     *   <li>only to peers not already in the OFFER's {@code to} list, because they have it or are getting it;
     *   <li>never back down the link it arrived on;
     *   <li>never at all if it arrived {@code forwarded}, which caps the chain at one hop. Two
     *       forwarders passing a file back and forth is not a slow network, it is a clipboard that
     *       will not stay still.
     * </ul>
     */
    private void forwardFile(Link from, String sha) {
        JSONObject hdr;
        synchronized (inboundOffers) { hdr = inboundOffers.remove(sha); }
        if (hdr == null || hdr.optBoolean("forwarded", false)) return;
        Files.Ref f = refFor(sha);
        if (f == null) return;
        java.util.Set<String> has = new java.util.HashSet<>();
        JSONArray to = hdr.optJSONArray("to");
        for (int i = 0; to != null && i < to.length(); i++) has.add(to.optString(i, ""));
        has.add(hdr.optString("from", ""));
        has.add(Node.id());
        for (Link peer : host.links()) {
            if (!peer.isOpen() || peer.peerId() == null) continue;
            if (peer == from || has.contains(peer.peerId())) continue;
            try {
                rememberOffered(sha, f);
                peer.connection().sendJson(Connection.T_OFFER, header(f, true,
                        hdr.optLong("seq", System.currentTimeMillis()),
                        hdr.optString("from", Node.id())));
                Logger.i("forwarded " + f.name + " to " + peer.connection().peer);
            } catch (Exception e) {
                Logger.i("forwarding " + f.name + " to " + peer.connection().peer + " failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ serving a file

    /** The peer wants (part of) a file we offered, or one we still have: push the requested chunks. */
    void onWant(Connection c, JSONObject msg) throws Exception {
        String sha = msg.optString("sha256");
        if (!c.drivesTransfer()) {
            // The other end opens the streams on this link (Connection.drivesTransfer), so there is
            // nothing to start: it will PULL, and servePull() answers. Exactly what the PC does in
            // this position, and for the same reason.
            Logger.i("want: " + Frames.shortSha(sha) + ", waiting for " + c.peerLabel + "'s data connections");
            return;
        }
        Transfer u = upload.get();
        if (u != null && u.upload && u.sha256.equals(sha) && !u.isAborted()) {
            Logger.i("want: " + Frames.shortSha(sha) + " already uploading, ignored");
            return;
        }
        Files.Ref f = refFor(sha);
        if (f == null) {
            Logger.w("want: " + Frames.shortSha(sha) + " not available any more");
            c.sendJson(Connection.T_ABORT, Frames.shaMsg(sha).put("reason", "not available any more"));
            return;
        }
        if (u != null && !u.isAborted()) u.abort();          // a different file: the newer request wins
        final Files.Ref ref = f;
        Transfer t = Transfer.upload(ctx, config(), PeerRoute.of(c), f,
                rangesOf(msg.optJSONArray("ranges"), Connection.chunks(f.size)), (tr, complete) -> {
            upload.compareAndSet(tr, null);
            if (complete) {
                // done from our side: no longer "outstanding" (screen-off burst may end), but still
                // servable should the peer ask again for a lost stream
                synchronized (offered) {
                    offered.remove(ref.sha256);
                    sent.put(ref.sha256, ref);
                    while (sent.size() > 8) sent.remove(sent.keySet().iterator().next());
                }
            }
        });
        Transfer displaced = upload.getAndSet(t);
        if (displaced != null && displaced != t) displaced.abort();
        t.start();
    }

    /**
     * A file we can serve, wherever it is: still offered, recently sent, or in the cache.
     *
     * <p>All three are needed and none of them is a fallback for the others. An offer stays
     * answerable until HAVE/SKIP/ABORT because a lost stream is re-asked for; a sent file stays
     * answerable because the far end may have lost its last chunk; and the cache answers a peer that
     * asks for something this device received rather than originated, which is how a file reaches a
     * third device at all.
     */
    @Override public Files.Ref refFor(String sha) {
        Files.Ref f;
        synchronized (offered) { f = offered.get(sha); if (f == null) f = sent.get(sha); }
        if (f == null) {
            Uri cached = cache.get(sha);
            if (cached != null) f = Files.stat(ctx, cached, config().maxFileAny());
        }
        return f != null && f.sha256.equals(sha) ? f : null;
    }

    // ---- inbound data connections: the peer opened the link, so the peer drives the transfer ----
    /**
     * How many data connections are currently pushing each hash at us.
     *
     * <p>Counted from a connection's first <b>CHUNK</b>, not from the moment it opens. A data
     * connection does not declare which way it runs, so at open time a stream a waiter opened to
     * <em>pull</em> a file we are relaying looks exactly like one pushing it at us. Counting only
     * from the first CHUNK keeps a pull stream that will never send a byte out of the count, so the
     * "last stream out" that finishes or re-asks for the file is decided only by streams that are
     * actually pushing.
     *
     * <p>The tradeoff worth naming: a stream that has opened but not yet sent anything is not
     * counted, so a fast stream finishing first can still look like "every stream closed, file
     * incomplete". Nothing is lost when that happens: {@link #endPush} answers it with
     * {@code p.keep()}, which only flushes the chunk map, and the next chunk on another stream
     * reopens the channel, and the window is one disk read on the pushing side, between opening the
     * stream and sending the chunk it opened for. The re-ask {@code endPush} arms is debounced by two
     * seconds and re-reads this very map before it sends anything, so a false "last stream out" costs
     * nothing at all. The PC's on_push_close is the same shape.
     *
     * <p>A connection that opens to push and dies <em>before</em> that disk read is not in here
     * either, and nothing would ever have noticed it: that is what {@link #streamGone} is for.
     */
    private final java.util.Map<String, Integer> pushing = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One data connection a peer opened to us: it will push the chunks of a file we asked for, or
     * pull the chunks of one we offered.
     *
     * <p>Which of the two is not declared, it is whichever frame arrives first, and it does not
     * need to be: a connection that sends PULL is pulling and one that sends CHUNK is pushing, and
     * nothing else is accepted.
     */
    void serveData(Connection c, Hello hello) throws Exception {
        String sha = hello.sha256;
        // Set by the first CHUNK and by nothing else: it is both "this connection turned out to be
        // a pusher" and the Partial that endPush has to be told about. One connection is one thread
        // in dataLoop, so the holder is only ever touched from here.
        final Files.Partial[] pushed = {null};
        // What this connection turned out to be, when it turned out to be nothing. A PULL identifies
        // a puller for good, a relay waiter fetching from us, with no bearing on a transfer coming
        // in. Everything else that closes without a single CHUNK, including a connection that said
        // nothing at all, may have been a push that died before its first byte, and that case has to
        // reach streamGone. Python's data_thread calls the same flag `was_pull`.
        final boolean[] wasPull = {false};
        try {
            Frames.dataLoop(c, new Frames.Data() {
                @Override public void chunk(int idx, byte[] payload, int off, int len) throws Exception {
                    Files.Partial p = pushed[0];
                    if (p == null) {
                        // Looked up now rather than when the connection opened: the peer may open
                        // its streams a moment before our WANT path has a Partial for them, and by
                        // the first chunk it always exists. The PC does the same (on_push_open from
                        // the T_CHUNK arm of data_thread).
                        p = partials.get(sha);
                        if (p == null) throw new java.io.IOException("no transfer in progress for " + Frames.shortSha(sha));
                        pushed[0] = p;
                        pushing.merge(sha, 1, Integer::sum);
                    }
                    // claimFirstChunk, not "haveCount() == 0": the test and the flag are one
                    // operation, so the early OFFER below goes out once however many streams reach
                    // their first write together. Same rule, same method, as the pull side
                    // (Transfer.pull's chunk callback).
                    boolean wasFirst = p.claimFirstChunk();
                    p.write(idx, payload, off, len);
                    // Streaming relay: on the first chunk, send OFFER to the peers waiting on us to
                    // relay this file, so they start pulling while we are still receiving.
                    if (wasFirst && relay.hasWaiters(sha)) {
                        relay.earlyOfferToWaiters(sha, p);
                    }
                }

                /** This end accepted the connection, so the peer is entitled to ask us for chunks. */
                @Override public void pull(Connection cc, JSONObject msg) throws Exception {
                    wasPull[0] = true;          // a puller, and never a push stream that failed
                    servePull(cc, sha, msg);
                }

                /**
                 * A bare PONG, not the three-timestamp one a control connection sends: this stream
                 * carries no clip and compares no versions, so the stamps would be measured and
                 * thrown away. Answered at all because a push stream can sit idle between chunks
                 * while the sender reads from slow storage, and silence would cost it its socket.
                 */
                @Override public void ping(Connection cc) throws Exception {
                    cc.send(Connection.T_PONG);
                }
            });
        } finally {
            // Only a connection that actually pushed is one of the streams endPush is counting; a
            // pure PULL (a relay waiter fetching from us) never entered the count and must not be
            // able to decide that the last stream is out.
            if (pushed[0] != null) endPush(sha, pushed[0]);
            else if (!wasPull[0]) streamGone(sha, c);
        }
    }

    /** A push stream ended, cleanly or not. The last one out finishes the file or re-asks for the rest. */
    private void endPush(String sha, Files.Partial p) {
        // computeIfPresent, not get-then-put: another stream for the same file may be opening at
        // this instant, and a counter read and written in two steps loses one of the two.
        boolean last = pushing.computeIfPresent(sha, (k, v) -> v <= 1 ? null : v - 1) == null;
        if (!last) return;
        if (p.complete()) { finishDownload(null, p); return; }
        // Whatever arrived stays on disk. A peer's next OFFER would eventually resume it, but that
        // OFFER may be a long way off, so it is only the backstop: the debounced re-ask asks for the
        // missing chunks directly, and the OFFER still resumes whatever the re-ask could not finish.
        // Python's on_push_close, same two arms, same order.
        p.keep();
        if (!isAborted(sha)) scheduleReask(p);
    }

    /**
     * A data connection we accepted closed without ever having pushed a chunk, and without ever
     * having said it was pulling.
     *
     * <p>The counterpart of Python's {@code on_stream_gone}. Counting a push stream at its first
     * CHUNK rather than at open is right, because until a frame arrives, a pusher and a relay waiter's
     * puller are indistinguishable, but it means a connection that opens <em>to push</em> and dies
     * before its first byte is counted by nobody, so {@link #endPush} never runs and the one thing
     * that restarts a stalled transfer is never armed. This method covers exactly that case: if all
     * the streams for a file fail that way (a peer whose storage read fails, a link that drops the
     * moment it is used), this is what still arms the re-ask instead of leaving the file waiting for
     * the peer's next OFFER.
     *
     * <p>Deliberately <b>not</b> counted into {@link #pushing}: this connection pushed nothing, and
     * putting it into the count is the exact bug the late counting exists to avoid. All it does is
     * arm the same debounced re-ask, which re-checks everything before it sends anything.
     *
     * <p>A connection that died before saying anything at all is ambiguous by construction, and this
     * resolves it by re-asking: a stray WANT to a peer that is already pushing costs one log line,
     * while not asking costs the transfer.
     */
    private void streamGone(String sha, Connection c) {
        Files.Partial p = partials.get(sha);
        if (p == null || p.isFinalized() || isAborted(sha)) return;
        if (pushing.containsKey(sha) || p.complete()) return;
        Logger.i(c.peerLabel + " opened a data connection for " + Frames.shortSha(sha)
                + " and closed it before the first chunk; re-asking");
        p.keep();
        scheduleReask(p);
    }

    /**
     * Shas with a debounced re-ask already armed, Python's {@code reask_pending}, by the same name.
     *
     * <p>One timer per file at a time, and that is the whole point of the set. Eight streams end
     * together in the ordinary case; one timer each would be eight WANTs for one file a moment
     * later, and eight WANTs burn the whole {@link #WANT_RETRIES} budget in one instant, so the
     * transfer's three chances at being restarted are all spent on the same stall.
     */
    private final java.util.Set<String> reaskPending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Arm the debounced re-ask for this file, at most one timer per hash. Python's
     * {@code _schedule_reask}.
     *
     * <p>Not a main-thread Handler, and that is not a stylistic preference: {@link #reask} ends in a
     * socket write, and a socket write on the main looper throws NetworkOnMainThreadException. The
     * relay's fallback walk was posted that way once and every one of its timeouts died in a caught
     * exception (see {@link RelayCoordinator}'s timer). It runs on that same background timer.
     */
    private void scheduleReask(Files.Partial p) {
        if (!reaskPending.add(p.sha256)) return;
        if (!relay.schedule(() -> reask(p), REASK_DEBOUNCE_MS)) reaskPending.remove(p.sha256);
    }

    /**
     * The debounce has elapsed: ask the peer again for what is still missing, if it is still worth
     * asking. Python's {@code _reask}, guard for guard.
     *
     * <p>Everything is re-read here rather than captured two seconds ago, because two seconds is
     * long enough for the transfer to have finished, been aborted, been superseded by a newer offer
     * or simply resumed on streams the peer opened in the meantime.
     */
    private void reask(Files.Partial p) {
        String sha = p.sha256;
        // Released first thing, so a stream that ends while this one is deciding can arm the next
        // timer rather than being swallowed by a flag that outlives its timer.
        reaskPending.remove(sha);
        if (pushing.containsKey(sha)) return;           // busy: the peer is pushing again
        if (p.isFinalized() || p.complete() || isAborted(sha)) return;
        if (partials.get(sha) != p) return;             // finished, or a newer Partial took over
        Link l = wantOrigin.get(sha);
        if (l == null || !l.isOpen()) {                 // Python: `origin in self.clients`
            Logger.i(p.name + " incomplete; kept for resume");
            return;
        }
        Connection c = l.connection();
        // Only where WANT is our lever at all. When the peer drives, it opens the data connections
        // and a WANT asks it to do what it is already doing; when we drive there is no stall to
        // clear either, because startDownload retries the transfer itself (DOWNLOAD_RETRIES), which
        // is the phone's _pull_transfer. Same test as Python's on_stream_gone.
        if (c.drivesTransfer()) return;
        List<int[]> missing = p.missing();
        if (missing.isEmpty()) return;
        if (!p.claimReask(WANT_RETRIES)) {
            Logger.i(p.name + " incomplete; out of re-asks, kept for resume");
            return;
        }
        int n = 0;
        for (int[] r : missing) n += r[1] - r[0];
        Logger.i(p.name + " incomplete, asking " + c.peerLabel + " again for " + n + " chunk(s)");
        try {
            c.sendJson(Connection.T_WANT, Frames.shaMsg(sha).put("ranges", rangesJson(missing)));
        } catch (Exception e) {
            Logger.i("re-ask for " + Frames.shortSha(sha) + " failed: " + e);
        }
    }

    /** Stream the chunks a peer asked for, then END: the far side of Transfer's pull worker. */
    private void servePull(Connection c, String sha, JSONObject msg) throws Exception {
        // Streaming relay: if a Partial exists and the file is not yet in the cache, serve chunks as
        // they become available rather than waiting for the complete file.
        Files.Partial p = partials.get(sha);
        if (p != null && !p.complete() && relay.hasWaiters(sha)) {
            serveRelayPull(c, sha, msg, p);
            return;
        }
        Files.Ref f = refFor(sha);
        if (f == null) {
            Logger.w("pull: " + Frames.shortSha(sha) + " not available any more");
            c.sendJson(Connection.T_ABORT, Frames.shaMsg(sha).put("reason", "not available any more"));
            return;
        }
        byte[] buf = new byte[Connection.CHUNK];
        try (Files.ChunkSource src = new Files.ChunkSource(ctx, f)) {
            for (int[] r : rangesOf(msg.optJSONArray("ranges"), Connection.chunks(f.size)))
                for (int i = r[0]; i < r[1]; i++) {
                    int len = src.read(i, buf);
                    c.sendChunk(i, buf, len);
                }
        }
        c.sendJson(Connection.T_END, Frames.shaMsg(sha));
    }

    /**
     * Streaming relay serve: forward chunks from an in-progress Partial as they arrive.
     *
     * <p>Waits on the Partial's monitor for each missing chunk, with a per-chunk timeout.  If the
     * file completes or the waiter falls too far behind (bounded queue), it degrades: remaining
     * chunks are served from the finalised file (store-and-forward fallback).
     */
    private void serveRelayPull(Connection c, String sha, JSONObject msg, Files.Partial p) throws Exception {
        int n = p.n;
        byte[] buf = new byte[Connection.CHUNK];
        int served = 0;
        for (int[] r : rangesOf(msg.optJSONArray("ranges"), n)) {
            for (int i = r[0]; i < r[1]; i++) {
                // Wait for the chunk to become available (written by the origin's push threads).
                long deadline = System.nanoTime() + 60_000_000_000L; // 60s per chunk backstop
                boolean timedOut = false;
                synchronized (p) {
                    while (!p.hasChunk(i)) {
                        if (p.complete()) break; // all chunks in, switch to normal read
                        long remain = (deadline - System.nanoTime()) / 1_000_000;
                        if (remain <= 0) { timedOut = true; break; }
                        p.wait(Math.min(remain, 500)); // wake on each write via notifyAll
                    }
                }
                // Outside the monitor on purpose. Files.Partial.write() is synchronized on the same
                // object, so a send that blocks in here would stall every chunk the origin's push
                // threads are trying to store for this file, a slow reader on one relayed copy
                // holding up the download itself. Nothing below needs the lock: the decision was
                // made under it and `timedOut` carries it out.
                if (timedOut) {
                    Logger.w("relay pull: timed out waiting for chunk " + i + " of " + Frames.shortSha(sha));
                    c.sendJson(Connection.T_ABORT, Frames.shaMsg(sha).put("reason", "relay timeout"));
                    return;
                }
                if (!p.hasChunk(i)) {
                    // complete() returned true but chunk is missing; should not happen, but guard
                    c.sendJson(Connection.T_ABORT, Frames.shaMsg(sha).put("reason", "chunk not available"));
                    return;
                }
                int len = p.readChunk(i, buf);
                c.sendChunk(i, buf, len);
                served++;
            }
        }
        c.sendJson(Connection.T_END, Frames.shaMsg(sha));
        Logger.i("relay: streamed " + served + " chunks of " + Frames.shortSha(sha) + " to " + c.peerLabel);
    }

    // ------------------------------------------------------------------ the peer's answers

    void onHave(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("peer already has " + (f != null ? f.name : Frames.shortSha(sha)) + ", nothing transferred");
    }

    void onSkip(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("peer skipped " + (f != null ? f.name : Frames.shortSha(sha)) + ": " + msg.optString("reason"));
    }

    void onAbort(JSONObject msg) {
        String sha = msg.optString("sha256");
        synchronized (offered) { offered.remove(sha); }
        // Recorded even when no Transfer of ours matches: the case this most matters for is a
        // peer-driven download, where this end has no Transfer at all and the only thing that would
        // otherwise still act on this hash is the debounced re-ask.
        noteAborted(sha);
        for (Transfer t : new Transfer[]{upload.get(), download.get()}) {
            if (t != null && t.sha256.equals(sha)) {
                t.abort();
                Logger.i("peer aborted " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + msg.optString("reason"));
            }
        }
    }

    // ------------------------------------------------------------------ stopping

    /**
     * Stop whatever is in flight and tell the peer that was carrying it.
     *
     * <p>Told through the transfer's own {@link PeerRoute} and never through a connection the caller
     * supplies: a transfer knows which link it is running on, so there is nothing for the caller to
     * guess, and with several links a caller that guessed would risk telling the wrong peer.
     *
     * @param except a hash to leave alone, or null. It is the hash of the clip that is causing the
     *               abort, in the case where the new clip <em>is</em> the file already moving.
     */
    private void abortTransfers(String reason, String except) {
        for (Transfer t : new Transfer[]{upload.get(), download.get()}) {
            if (t == null || t.isAborted() || t.sha256.equals(except)) continue;
            t.abort();
            noteAborted(t.sha256);       // so a re-ask armed a moment ago does not undo this
            Logger.i("aborting " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + reason);
            try {
                t.route().send(Connection.T_ABORT, Frames.shaMsg(t.sha256).put("reason", reason));
            } catch (Exception ignored) {
            }
        }
    }

    /** Everything in flight has to stop, and nothing takes its place. Used on reload. */
    void abortAll(String reason) {
        abortTransfers(reason, null);
    }

    /**
     * The user copied something new: stop the transfer the new clip supersedes and withdraw every
     * open offer, which is now about content nobody is going to ask for.
     *
     * @param hash the new clip's hash, the one transfer that must survive, for the case where the
     *             new clip is the file that is already moving
     */
    void supersede(String hash) {
        abortTransfers("superseded by a newer clip on the phone", hash);
        synchronized (offered) { offered.clear(); }
    }

    /**
     * Is a transfer or an open offer still outstanding anywhere?
     *
     * <p>This is what holds the screen-off disconnect open. {@code pushing} counts too: a peer
     * streaming a file at us over connections it opened is a transfer this device is in the middle
     * of, even though no {@link Transfer} of ours is tracking it; without it the burst would close
     * the control link under a download in progress. A relay this device accepted counts as well:
     * the job is not ours, but dropping it mid-flight strands the peer that asked.
     */
    boolean transferBusy() {
        Transfer u = upload.get(), d = download.get();
        if (!pushing.isEmpty()) return true;
        if (relay.busy()) return true;
        synchronized (offered) {
            return !offered.isEmpty() || (u != null && !u.isAborted()) || (d != null && !d.isAborted());
        }
    }

    /**
     * A link is going: stop whatever <em>it</em> was carrying, and say what has to be tried again.
     *
     * <p>Transfers are still one at a time for the whole device, so this only has to tell the one in
     * flight apart from nothing, but it does have to check whose it was, because with several links
     * a closing one must not abort a transfer another is running.
     *
     * <p>{@code offered} is deliberately NOT cleared. It is what answers a WANT, it is keyed by hash
     * rather than by peer, and the same file is offered to every link, so clearing it when one link
     * dies would make the others' offers unanswerable. It is bounded at 8 and emptied by
     * HAVE / SKIP / ABORT, or wholesale by {@link #supersede}.
     *
     * @return the file this link was uploading and never got an answer about, for the caller to put
     *         back in the pending slot, or null
     */
    Files.Ref stopTransfersOn(Link l) {
        Connection c = l.connection();
        Files.Ref unanswered = null;
        Transfer u = upload.get(), d = download.get();
        // Only what this link was carrying. With one connection "no connection given" could mean
        // "mine"; with several it would mean "everyone's", and a target that merely failed to
        // connect would abort the transfer a different peer was happily running.
        //
        // compareAndSet and not a bare null: between reading the slot and clearing it, another link
        // may have installed its own transfer, and clearing unconditionally would drop that one out
        // of sight while it went on running, untracked by abortTransfers, transferBusy and the next
        // teardown alike.
        if (d != null && d.route().carriedBy(c)) { d.abort(); d.partial.keep(); download.compareAndSet(d, null); }
        if (u != null && u.route().carriedBy(c)) {
            if (!u.isAborted()) unanswered = u.ref;
            u.abort();
            upload.compareAndSet(u, null);
        }
        return unanswered;
    }
}
