package io.github.lcebot.clipsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Who fetches a file for whom, when several nodes on one LAN all want the same thing from one node
 * that is not on it.
 *
 * <p>The problem it exists for: a PC on the internet offers a file to a phone and a tablet that sit
 * beside each other on Wi-Fi. Without this, both pull the whole file over the slow link and the PC
 * uploads it twice. With it, both ends of the LAN compute the <em>same</em> priority order from
 * fields that were already in HELLO — mains power beats battery, a PC beats a tablet beats a phone,
 * a node that can hold a connection while idle beats one that cannot, and the node id breaks ties —
 * so the winner pulls from the origin and the losers ask the winner to pass it on. The order is
 * total and computed identically everywhere, which is what lets them agree without an election
 * message.
 *
 * <p>Three things follow, and this class owns all three:
 *
 * <ul>
 *   <li><b>asking</b> ({@code relayWaits}): a walk down the candidate list, one RELAY_ASK at a time,
 *       with a timeout per step and one retry for a candidate that says "busy", falling back to the
 *       origin when the list runs out;
 *   <li><b>accepting</b> ({@code relayAccepted}): a set of waiting links per hash, which this device
 *       will OFFER the file to — as soon as the <em>first chunk</em> lands, not when the file is
 *       complete, so a relayed file moves through rather than being stored and forwarded;
 *   <li><b>saying so</b>: {@link #relayingCount()} is what the UI shows as "Relay (n)", and
 *       {@link #busy()} is what stops a screen-off disconnect dropping a relay mid-flight.
 * </ul>
 *
 * <p><b>What it does not do.</b> It never touches a chunk, a Partial or the cache directly, it never
 * decides whether a file is wanted, and it does not own the pull that streams a relayed file out —
 * all of that is {@link FileExchange}, which it reaches through {@link Host}. It also holds no clip
 * state at all: a relay is about bytes, and whether those bytes end up on the clipboard is somebody
 * else's decision.
 *
 * <p><b>Locking.</b> It does not take the device lock and must not: every map here is a concurrent
 * one, every field of a {@link RelayWait} is touched only from the walk (the control-frame thread
 * that owns the wait, or the single {@code clipsync-relay} timer thread), and nothing in here is
 * part of an invariant that spans another class. The one invariant that does span — "a hash with an
 * empty waiter set is a finished relay, not a busy one" — is stated in {@link #busy()} and in
 * {@link #onLinkGone} and is maintained entirely inside this file.
 */
final class RelayCoordinator {

    /** What the relay needs from the file exchange it is part of. */
    interface Host {
        Config config();

        /** Every live link. */
        Iterable<Link> links();

        /** The live link to one peer id, or null. */
        Link linkTo(String peerId);

        /** A file we can serve, wherever it is: still offered, recently sent, or in the cache. */
        Files.Ref refFor(String sha);

        /** Remember this file as offered, so a WANT for it can be answered. */
        void rememberOffered(String sha, Files.Ref f);

        /** The OFFER header this file arrived with, or null — for its originator's seq and from. */
        JSONObject inboundOffer(String sha);

        /** Is the complete file already in the cache? */
        boolean cached(String sha);

        /** The in-progress download of this hash, or null. */
        Files.Partial partial(String sha);

        /** Finalise a complete Partial: it lands in the cache and the waiters are offered it. */
        void finishDownload(Link l, Files.Partial p);

        /** The ordinary WANT path, for when the walk gives up and falls back to the origin. */
        void wantFromPeer(Link l, String sha, String name, JSONObject hdr, long size, long seq) throws Exception;
    }

    private final Context ctx;
    private final Host host;

    RelayCoordinator(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    // ------------------------------------------------------------------ election

    /**
     * Priority key for relay election.  Lower array = higher priority; elements are compared
     * left to right, and the node id breaks ties (lower id wins).  The order is total, so every
     * node computes the same answer from the same HELLO fields without exchanging an election
     * message.
     */
    private static int[] priorityKey(boolean persistent, String type, String battery) {
        int typeRank = "pc".equals(type) ? 2 : "tablet".equals(type) ? 1 : 0;
        int battRank = "mains".equals(battery) ? 3 : "high".equals(battery) ? 2 : "medium".equals(battery) ? 1 : 0;
        return new int[]{persistent ? 0 : 1, -typeRank, -battRank};
    }

    private static int comparePriority(int[] a, String idA, int[] b, String idB) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return idA.compareTo(idB);
    }

    /** Compute the candidate list for relay election, sorted by priority (best first). */
    private List<String> buildCandidateList(String offerFrom, Connection originConn, java.util.Set<String> recipients) {
        // Each candidate: [id, persistent, type, battery].
        List<String[]> cands = new ArrayList<>();
        // Me.
        cands.add(new String[]{Node.id(), String.valueOf(Node.persistent(ctx)), Node.type(ctx), Node.battery(ctx)});
        // Origin, if on LAN.
        if (originConn.lanPeer) {
            cands.add(new String[]{offerFrom, String.valueOf(originConn.peerPersistent), originConn.peerType, originConn.peerBattery});
        }
        // LAN peers that are in the recipient list.
        for (Link peer : host.links()) {
            if (!peer.isOpen() || peer.peerId() == null || peer.peerId().equals(offerFrom)) continue;
            Connection pc = peer.connection();
            if (pc.lanPeer && recipients.contains(peer.peerId())) {
                cands.add(new String[]{peer.peerId(), String.valueOf(pc.peerPersistent), pc.peerType, pc.peerBattery});
            }
        }
        cands.sort((a, b) -> comparePriority(
                priorityKey(Boolean.parseBoolean(a[1]), a[2], a[3]), a[0],
                priorityKey(Boolean.parseBoolean(b[1]), b[2], b[3]), b[0]));
        List<String> ids = new ArrayList<>();
        for (String[] c : cands) ids.add(c[0]);
        return ids;
    }

    /** @return the best relay candidate id, or null if the candidate set is empty. */
    private String electRelay(String offerFrom, Connection originConn, java.util.Set<String> recipients) {
        List<String> ids = buildCandidateList(offerFrom, originConn, recipients);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ------------------------------------------------------------------ waiting

    /** State for a file we are waiting on a relay to provide. */
    private static final class RelayWait {
        final String sha256;
        final JSONObject offerHdr;          // the original OFFER header, for a later WANT to the origin
        final Link origin;                  // who sent the OFFER
        final List<String> candidates;      // priority-sorted node ids (walk order)
        int nextIdx;                        // current position in the fallback walk
        long askTime;                       // SystemClock.elapsedRealtime when RELAY_ASK was sent
        final java.util.Set<String> failed = new java.util.HashSet<>();
        boolean retried;                    // whether the current busy candidate was retried once

        RelayWait(String sha, JSONObject hdr, Link origin, List<String> candidates) {
            this.sha256 = sha;
            this.offerHdr = hdr;
            this.origin = origin;
            this.candidates = candidates;
        }
    }

    /** Files we are waiting on a relay to provide, keyed by sha256. */
    private final java.util.Map<String, RelayWait> relayWaits = new java.util.concurrent.ConcurrentHashMap<>();
    /** Relay requests we accepted: sha → set of waiter Links that will receive the OFFER. */
    private final java.util.Map<String, java.util.Set<Link>> relayAccepted = new java.util.concurrent.ConcurrentHashMap<>();
    /** Timeout for a single step of the relay fallback walk (generous — it is a backstop, not a scheduler). */
    private static final long RELAY_ASK_TIMEOUT_MS = 30_000;

    /**
     * Where the relay's delayed work runs. <b>Not the main looper.</b>
     *
     * <p>Both the step timeout and the busy retry end in a socket write, and a socket write on the
     * main thread throws NetworkOnMainThreadException. Both call sites caught Exception and said
     * nothing, so the effect was that every relay that reached its timeout or was told "busy" —
     * which is to say every relay that needed the fallback walk at all — marked every remaining
     * candidate failed and then failed the fall back to the origin too. The feature could only work
     * when it never had to try twice.
     *
     * <p>It is also the phone's only background timer for file work, which is why
     * {@link FileExchange}'s debounced re-ask runs on it through {@link #schedule} rather than
     * starting a second one: one thread, one shutdown, and the same "never the main looper" rule
     * enforced in one place instead of two. Both kinds of work end in a short socket write and are
     * rare (a relay that had to fall back, a stream that ended with the file incomplete), so sharing
     * the thread costs nothing that a second thread would buy.
     */
    private final java.util.concurrent.ScheduledExecutorService relayTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "clipsync-relay");
                t.setDaemon(true);
                return t;
            });

    /**
     * Run {@code r} on the background timer after {@code delayMs}.
     *
     * @return false when the timer is gone (the service is being destroyed), so a caller that keeps
     *         a "one timer armed for this file" flag can drop it again instead of leaving a flag
     *         behind that nothing will ever clear.
     */
    boolean schedule(Runnable r, long delayMs) {
        try {
            relayTimer.schedule(r, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;       // shutdown() ran; nothing left to re-ask on
        }
    }

    void shutdown() {
        relayTimer.shutdownNow();
    }

    /** How many files this device is actually relaying: hashes that still have a waiter. */
    int relayingCount() {
        int n = 0;
        for (java.util.Set<Link> w : relayAccepted.values()) if (!w.isEmpty()) n++;
        return n;
    }

    /**
     * Is a relay this device accepted still outstanding?
     *
     * <p>A non-empty <em>set</em>, not a non-empty map — a key whose waiters have all gone is a
     * finished relay, and reading it as an outstanding one is what kept the phone awake.
     * {@link #onLinkGone} removes those keys; this is the belt to that pair of braces.
     */
    boolean busy() {
        for (java.util.Set<Link> w : relayAccepted.values()) if (!w.isEmpty()) return true;
        return false;
    }

    /** Are there waiters for this hash — i.e. is this device relaying it? */
    boolean hasWaiters(String sha) {
        return relayAccepted.containsKey(sha);
    }

    /**
     * A RELAY_ASK: the hash, and how big the thing is.
     *
     * <p>The size is what lets a candidate say no <em>now</em>. Without it a relay that could never
     * accept the file — over its own limit — has nothing to answer until it has seen the file, so
     * the asker waits out the full {@link #RELAY_ASK_TIMEOUT_MS} before moving to the next
     * candidate: thirty seconds of silence per candidate, ninety for a list of three, all of it
     * avoidable by sending a number that is already in the OFFER header.
     */
    private static JSONObject relayAsk(RelayWait rw) throws Exception {
        return Frames.shaMsg(rw.sha256).put("size", rw.offerHdr.optLong("size", -1));
    }

    /**
     * An OFFER has arrived and the file is worth having. Should a relay fetch it instead of us?
     *
     * <p>This is the whole of the election as {@code FileExchange.onOffer} sees it.
     *
     * @return true when a RELAY_ASK has gone out and the caller must do nothing further; false when
     *         the ordinary WANT path applies — either because no election is called for, or because
     *         this device won it, or because this OFFER <em>is</em> the relay answering.
     */
    boolean intercept(Link l, String sha, String name, JSONObject hdr) throws Exception {
        Connection c = l.connection();
        RelayWait rw = relayWaits.get(sha);
        if (rw != null && l.peerId() != null && l.peerId().equals(currentRelayFor(rw))) {
            // This OFFER is from the relay we asked — skip election, WANT directly.
            relayWaits.remove(sha);
            Logger.i("offer: " + name + " from relay " + Node.shortId(l.peerId()) + " -> want directly");
            return false;
        }
        String offerFrom = hdr.optString("from", "");
        JSONArray toArr = hdr.optJSONArray("to");
        if (toArr == null || toArr.length() == 0 || offerFrom.isEmpty()) return false;
        java.util.Set<String> recipients = new java.util.HashSet<>();
        for (int i = 0; i < toArr.length(); i++) recipients.add(toArr.optString(i));
        String best = electRelay(offerFrom, c, recipients);
        if (best == null || best.equals(Node.id()) || best.equals(offerFrom)) return false;
        // A higher-priority LAN peer should relay; ask it.
        List<String> candidateIds = buildCandidateList(offerFrom, c, recipients);
        RelayWait wait = new RelayWait(sha, hdr, l, candidateIds);
        relayWaits.put(sha, wait);
        askRelay(wait);
        return true;
    }

    /** @return the node id of the relay we are currently asking, or null. */
    private String currentRelayFor(RelayWait rw) {
        if (rw.nextIdx < rw.candidates.size()) {
            String id = rw.candidates.get(rw.nextIdx);
            if (!id.equals(Node.id()) && (rw.origin == null || !id.equals(rw.origin.peerId()))) return id;
        }
        return null;
    }

    /**
     * Walk the candidate list: send RELAY_ASK to the next viable candidate, or fall back to the
     * origin when the list is exhausted.
     */
    private void askRelay(RelayWait rw) {
        while (rw.nextIdx < rw.candidates.size()) {
            String cid = rw.candidates.get(rw.nextIdx);
            if (cid.equals(Node.id()) || (rw.origin != null && cid.equals(rw.origin.peerId()))) break; // reached self or origin
            if (rw.failed.contains(cid)) { rw.nextIdx++; continue; }
            Link relay = host.linkTo(cid);
            if (relay == null || !relay.isOpen()) { rw.failed.add(cid); rw.nextIdx++; continue; }
            try {
                relay.connection().sendJson(Connection.T_RELAY_ASK, relayAsk(rw));
                rw.askTime = android.os.SystemClock.elapsedRealtime();
                rw.retried = false;
                Logger.i("relay: asking " + Node.shortId(cid) + " to relay " + Frames.shortSha(rw.sha256));
                // Schedule a timeout for this step of the walk.
                relayTimer.schedule(() -> {
                    RelayWait w = relayWaits.get(rw.sha256);
                    if (w == rw && w.askTime > 0 && android.os.SystemClock.elapsedRealtime() - w.askTime >= RELAY_ASK_TIMEOUT_MS) {
                        Logger.i("relay: " + Node.shortId(cid) + " timed out for " + Frames.shortSha(rw.sha256));
                        rw.failed.add(cid);
                        rw.nextIdx++;
                        askRelay(rw);
                    }
                }, RELAY_ASK_TIMEOUT_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                Logger.w("relay: could not ask " + Node.shortId(cid) + " for " + Frames.shortSha(rw.sha256) + ": " + e);
                rw.failed.add(cid);
                rw.nextIdx++;
            }
        }
        // Exhausted the candidate list — WANT from origin.
        relayWaits.remove(rw.sha256);
        if (rw.origin == null || !rw.origin.isOpen()) return;
        try {
            String name = FileExchange.safeName(rw.offerHdr.optString("name", "clip"));
            host.wantFromPeer(rw.origin, rw.sha256, name, rw.offerHdr,
                    rw.offerHdr.optLong("size", -1), rw.offerHdr.optLong("seq", 0));
            Logger.i("relay: fell back to origin for " + Frames.shortSha(rw.sha256));
        } catch (Exception e) {
            Logger.w("relay: fallback to origin failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ being the relay

    /**
     * A peer asks us to relay a file.  Accept unless we are opted out, on low battery, or are
     * ourselves waiting for the same file.
     */
    void onRelayAsk(Link l, JSONObject msg) throws Exception {
        Config cfg = host.config();
        String sha = msg.optString("sha256");
        Connection c = l.connection();
        // Check opt-out and low battery.
        if (cfg.relayOptOut) {
            c.sendJson(Connection.T_RELAY_NO, new JSONObject().put("sha256", sha).put("reason", "refused"));
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (opted out)");
            return;
        }
        if ("low".equals(Node.battery(ctx))) {
            c.sendJson(Connection.T_RELAY_NO, new JSONObject().put("sha256", sha).put("reason", "refused"));
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (low battery)");
            return;
        }
        // Over what this device would accept for itself. "refused" and not "busy": nothing about
        // waiting and asking again changes a size, and the asker's walk should move on at once.
        long size = msg.optLong("size", -1);
        if (size > cfg.maxFileAny()) {
            c.sendJson(Connection.T_RELAY_NO, new JSONObject().put("sha256", sha).put("reason", "refused"));
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId()
                    + " (" + size + " bytes is over this device's limit)");
            return;
        }
        // A node that is itself waiting declines with busy (caps depth at one hop).
        if (relayWaits.containsKey(sha)) {
            c.sendJson(Connection.T_RELAY_NO, new JSONObject().put("sha256", sha).put("reason", "busy"));
            Logger.i("relay: declined " + Frames.shortSha(sha) + " from " + l.peerId() + " (busy, also waiting)");
            return;
        }
        // Accept.
        c.sendJson(Connection.T_RELAY_OK, Frames.shaMsg(sha));
        relayAccepted.computeIfAbsent(sha, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(l);
        Logger.i("relay: accepted " + Frames.shortSha(sha) + " for " + Node.shortId(l.peerId()));
        // If we already have the file, offer immediately.
        if (host.cached(sha)) {
            offerToWaiters(sha);
            return;
        }
        // If a complete Partial exists, finalize and offer.
        Files.Partial p = host.partial(sha);
        if (p != null && p.complete()) {
            host.finishDownload(null, p);
            // finishDownload calls offerToWaiters
        }
        // Otherwise, the offer will come when our own download completes (finishDownload → offerToWaiters)
        // or via streaming relay once the first chunks arrive (endPush → offerToWaiters).
    }

    /** Relay accepted our request — wait for its OFFER. */
    void onRelayOk(Link l, JSONObject msg) {
        String sha = msg.optString("sha256");
        // Only that we are still waiting on this sha matters — an OK for a wait that has since been
        // satisfied or abandoned is stale and says nothing worth logging.
        if (!relayWaits.containsKey(sha)) return;
        Logger.i("relay: " + Node.shortId(l.peerId()) + " accepted relay for " + Frames.shortSha(sha));
        // Nothing else to do: the relay will send OFFER when it has the file, and intercept()
        // recognises the relay as the source and WANTs directly.
    }

    /** Relay declined — walk to the next candidate. */
    void onRelayNo(Link l, JSONObject msg) {
        String sha = msg.optString("sha256");
        String reason = msg.optString("reason", "");
        RelayWait rw = relayWaits.get(sha);
        if (rw == null) return;
        Logger.i("relay: " + Node.shortId(l.peerId()) + " declined " + Frames.shortSha(sha) + ": " + reason);
        if ("busy".equals(reason) && !rw.retried) {
            // One retry after a short jittered delay (the candidate may be about to become the relay).
            rw.retried = true;
            long jitter = 500 + (long) (Math.random() * 1000);
            relayTimer.schedule(() -> {
                RelayWait w = relayWaits.get(sha);
                if (w != rw) return;
                try {
                    Link relay = host.linkTo(l.peerId());
                    if (relay != null && relay.isOpen()) {
                        relay.connection().sendJson(Connection.T_RELAY_ASK, relayAsk(rw));
                        rw.askTime = android.os.SystemClock.elapsedRealtime();
                        Logger.i("relay: retrying " + Node.shortId(l.peerId()) + " for " + Frames.shortSha(sha));
                    } else {
                        rw.failed.add(l.peerId());
                        rw.nextIdx++;
                        askRelay(rw);
                    }
                } catch (Exception e) {
                    Logger.w("relay: retry to " + Node.shortId(l.peerId()) + " failed: " + e);
                    rw.failed.add(l.peerId());
                    rw.nextIdx++;
                    askRelay(rw);
                }
            }, jitter, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            rw.failed.add(l.peerId());
            rw.nextIdx++;
            askRelay(rw);
        }
    }

    /**
     * The originator's {@code seq} and {@code from} for a file we are relaying, if we still know
     * them.
     *
     * <p>A relay must not restamp these. They are two thirds of the seen-set key, so a waiter that
     * also receives the file directly has to compute the same key from both copies or it treats the
     * second as new. Looked up rather than removed: the early offer, the completed offer and
     * {@code FileExchange.forwardFile} all want it, and only the last is done with it.
     *
     * @return {@code {seq, from}} from the OFFER that brought this file in, or this device's own
     *         values when it did not arrive by OFFER at all.
     */
    private Object[] relayOrigin(String sha) {
        JSONObject in = host.inboundOffer(sha);
        if (in == null) return new Object[]{System.currentTimeMillis(), Node.id()};
        return new Object[]{in.optLong("seq", System.currentTimeMillis()), in.optString("from", Node.id())};
    }

    /**
     * Send OFFER to every waiter that asked for this file via RELAY_ASK.
     * Called from finishDownload when we have the complete file in the cache.
     */
    void offerToWaiters(String sha) {
        java.util.Set<Link> waiters = relayAccepted.remove(sha);
        if (waiters == null || waiters.isEmpty()) return;
        Files.Ref f = host.refFor(sha);
        if (f == null) return;
        host.rememberOffered(sha, f);
        for (Link w : waiters) {
            if (!w.isOpen()) continue;
            try {
                Object[] origin = relayOrigin(sha);
                JSONObject hdr = new JSONObject();
                hdr.put("seq", origin[0]);
                hdr.put("name", f.name);
                hdr.put("mime", f.mime);
                hdr.put("size", f.size);
                hdr.put("sha256", f.sha256);
                hdr.put("from", origin[1]);
                // A relay is a forward: the waiter asked us for this file and must not pass it on
                // again, or the two of us hand it round the LAN between us.
                hdr.put("forwarded", true);
                JSONArray to = new JSONArray();
                to.put(w.peerId());
                hdr.put("to", to);
                w.connection().sendJson(Connection.T_OFFER, hdr);
                Logger.i("relay: offered " + f.name + " to waiter " + Node.shortId(w.peerId()));
            } catch (Exception e) {
                Logger.w("relay: offer to " + Node.shortId(w.peerId()) + " failed: " + e);
            }
        }
    }

    /**
     * Streaming relay: send OFFER to waiters as soon as we have the first chunk, so they can
     * start pulling while we are still receiving.  Does NOT remove from relayAccepted — that stays
     * so the pull-serving path knows to use the streaming path, and {@link #offerToWaiters} cleans
     * it when the file completes.
     */
    void earlyOfferToWaiters(String sha, Files.Partial p) {
        java.util.Set<Link> waiters = relayAccepted.get(sha);
        if (waiters == null || waiters.isEmpty()) return;
        for (Link w : waiters) {
            if (!w.isOpen()) continue;
            try {
                Object[] origin = relayOrigin(sha);
                JSONObject hdr = new JSONObject();
                // Same seq/from as the completed offer that follows it, so the waiter does not see
                // one file as two: the early offer and offerToWaiters both describe this transfer.
                hdr.put("seq", origin[0]);
                hdr.put("name", p.name);
                hdr.put("mime", p.mime);
                hdr.put("size", p.size);
                hdr.put("sha256", p.sha256);
                hdr.put("from", origin[1]);
                hdr.put("forwarded", true);         // a relay is a forward; see offerToWaiters
                JSONArray to = new JSONArray();
                to.put(w.peerId());
                hdr.put("to", to);
                w.connection().sendJson(Connection.T_OFFER, hdr);
                Logger.i("relay: early offer " + p.name + " to waiter " + Node.shortId(w.peerId()) + " (streaming)");
            } catch (Exception e) {
                Logger.w("relay: early offer to " + Node.shortId(w.peerId()) + " failed: " + e);
            }
        }
    }

    /**
     * A link went away: walk past it if it was the relay we were waiting on, and stop counting it
     * as a waiter if it was one.
     *
     * <p>Removing the empty sets is not tidiness. {@link #busy()} asks whether any hash still has a
     * waiter, so one finished relay used to pin the device as "busy" for the life of the process —
     * the screen-off disconnect never fired again and the phone held its links, and its radio, all
     * night.
     */
    void onLinkGone(Link l, String peerId) {
        if (peerId == null) return;
        for (RelayWait rw : new ArrayList<>(relayWaits.values())) {
            String cur = currentRelayFor(rw);
            if (peerId.equals(cur)) {
                rw.failed.add(peerId);
                rw.nextIdx++;
                askRelay(rw);
            }
        }
        relayAccepted.entrySet().removeIf(e -> {
            e.getValue().remove(l);
            return e.getValue().isEmpty();
        });
    }
}
