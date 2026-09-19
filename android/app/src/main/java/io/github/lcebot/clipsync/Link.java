package io.github.lcebot.clipsync;

import android.net.Network;

import androidx.annotation.Nullable;

/**
 * One session with one peer, from the moment a socket is opened to the moment it is torn down.
 *
 * <p>Everything in here used to live inside one lexical scope in {@code SyncService.mainLoop}, which
 * braided seven concerns together — gating, connecting, the handshake, the screen-off burst, the
 * heartbeat, the read loop and teardown — and used {@code conn == c} as the test for "am I still the
 * current session". That idiom means "is this <em>the</em> one", not "is this alive", and it is why
 * the file could only ever hold one connection.
 *
 * <p>The owner ({@link SyncService}) supplies everything shared through {@link Owner}. The split is
 * the point: what a Link may decide for itself is what it holds, and what belongs to the device is
 * what it has to ask for. Two things that were per-session and turned out to be per-device are
 * <em>not</em> here as a result:
 *
 * <ul>
 *   <li><b>the heartbeat.</b> One timer for the device, not one per link — N pingers would wake the
 *       radio N times on a phone that has exactly one radio, and the frozen-process detector and the
 *       status timestamp must not run N times either. {@link #ping()} is all that is left here.
 *   <li><b>the retry.</b> It belongs to the target, which outlives any session to it.
 * </ul>
 *
 * <p>And one thing that was global and turned out to be per-link is here: {@link #sentHash}. A clip
 * has to reach every peer, so "have I already sent this" is a question each link answers for itself.
 */
final class Link implements AutoCloseable {
    /** What a session needs from the service. Shared state stays on the far side of this. */
    interface Owner {
        /** The clipboard is one device's, however many peers there are. */
        void onFrame(Link link, Connection.Frame frame) throws Exception;

        void onConnected(Link link);

        /** Wall-clock ms of the current local clip, or 0 if none. */
        long clipTs();

        /** SHA-256 hex of the current local clip, or null if none. */
        @Nullable String clipSha();

        /** The newest local clip, or null. Not consumed — every link delivers it once. */
        @Nullable Object pendingClip();

        /** Put one clip on one link. Throws like any send; the caller decides what that means. */
        void send(Link link, Object clip) throws Exception;

        /** This link has now delivered that content hash. */
        void delivered(Link link, String hash);

        /** Is a transfer or an open offer still outstanding anywhere? */
        boolean transferBusy();

        boolean isRunning();

        boolean isScreenOn();

        Config config();
    }

    private final Owner owner;
    private final Connection conn;
    /** The target this link was dialled for: a listed address, or the mDNS service name. */
    final String target;
    private volatile boolean open = true;
    /**
     * The content hash this link has already delivered.
     *
     * <p>Per link, not global, and that is the difference between one peer and several: the old code
     * consumed the pending clip, so the first connection to flush it took it away from the rest. A
     * clip has to reach every peer, including one that connects a minute later.
     */
    private volatile String sentHash;

    /**
     * NTP-style clock offset to this peer, in milliseconds.
     *
     * <p>{@code peer_clock = my_clock + offset}. Updated on every PONG round trip, so it tracks
     * drift without extra messages. Used to normalise an incoming clip's version stamp into the
     * local clock domain before it is compared with ours: two devices whose wall clocks differ by a
     * minute would otherwise decide "newer" by whose clock was fast.
     */
    volatile long clockOffset;

    /**
     * What the peer declared in its HELLO.
     *
     * <p>Kept because two of its fields are about the <em>session</em> rather than about the peer:
     * {@link Hello#clipTs} and {@link Hello#clipSha} say what the peer's clipboard held when it
     * connected, and they are only useful once, at this moment. Both ends send them; until this
     * field existed, neither end read them, so a device that reconnected behind stayed behind.
     */
    private final Hello peerHello;

    private Link(Owner owner, String target, Connection c, Hello hello) {
        this.owner = owner;
        this.target = target;
        this.conn = c;
        this.peerHello = hello;
    }

    /** What the peer declared when this session opened. Never null. */
    Hello peerHello() {
        return peerHello;
    }

    // ------------------------------------------------------------------ how this link ends

    /**
     * Why this link stopped, when it stopped on purpose.
     *
     * <p>One state, where there used to be five members — a {@code bye} string, two accessors, a
     * {@code superseded} flag and its two — scattered through the middle of this file with the
     * fields declared after their own readers. They were never independent: a link is running, or
     * the peer said goodbye, or this end retired it in favour of another link to the same device,
     * and no two of those are ever true at once.
     *
     * <p>What the dialler asks is one question — {@link #endedOnPurpose()} — so the two non-RUNNING
     * members behave alike and the enum is, to the code, a boolean. They are still named apart
     * because they are two different reasons to answer yes, and the difference is measured in radio
     * wake-ups:
     *
     * <ul>
     *   <li>{@link #RUNNING} also covers a link that ended by <em>failing</em> — a read that threw,
     *       a socket closed under us. That is the case where redialling is the right answer, so it
     *       is the one with no marker;
     *   <li>{@link #PEER_BYE} means the peer chose to close and said why. It is not coming straight
     *       back on its own account, so the dialler waits out its longest back-off instead of
     *       rebuilding the link the peer just discarded — which, with no BYE at all, is an infinite
     *       loop between two devices that both think the other vanished;
     *   <li>{@link #SUPERSEDED} means <em>this</em> end closed it as a duplicate. Its owner sees
     *       nothing but a dead socket otherwise, which is indistinguishable from the peer going
     *       away, and a dialler that cannot tell those apart redials immediately into the link that
     *       just replaced this one.
     * </ul>
     */
    enum End {
        /** Still up, or ended by an error. The ordinary case, and the only one worth retrying soon. */
        RUNNING,
        /** The peer sent BYE. What it said is logged where the frame is read, not kept here. */
        PEER_BYE,
        /** This end closed it: another link to the same peer won the duplicate tiebreak. */
        SUPERSEDED
    }

    /**
     * How this link ended.
     *
     * <p>Only ever asked as {@link #endedOnPurpose()}. There used to be an {@code end()} getter and
     * an {@code endReason} beside it, on the assumption that someone would want to tell PEER_BYE
     * from SUPERSEDED or to read back what the peer said; nobody ever did. The reason is logged
     * where the BYE frame is read, which is where the peer and the frame are both in hand, so
     * keeping a second copy on the link only made the field look like an answer to a question
     * nothing asks.
     */
    private volatile End end = End.RUNNING;

    /**
     * True once this link was closed deliberately by either end.
     *
     * <p>The one question both of the dialler's back-off branches used to ask as
     * {@code saidBye() || superseded()}: whichever end decided, redialling at once would undo the
     * decision.
     */
    boolean endedOnPurpose() {
        return end != End.RUNNING;
    }

    /**
     * Record how this link is ending. First writer wins: a link that the peer said goodbye to and
     * that this end then retires is still, in the only sense the dialler cares about, the first
     * thing that happened to it.
     */
    void ended(End how) {
        if (end != End.RUNNING) return;
        end = how;
    }

    // ------------------------------------------------------------------ opening

    /** Completes the dialler's handshake, or throws having closed the socket it was given. */
    private static Link dialled(SyncService s, Owner o, String target, Connection c) throws Exception {
        Hello hello;
        try {
            hello = c.hello(s, o.clipTs(), o.clipSha());
        } catch (Exception e) {
            c.close();                     // the socket is ours from the moment we were handed it
            throw e;
        }
        return new Link(o, target, c, hello);
    }

    static Link toPeer(SyncService s, Owner o, String peer, Network net) throws Exception {
        return dialled(s, o, peer, Connection.toPeer(s, o.config(), peer, net));
    }

    /**
     * A peer dialled us and has already declared itself — {@link Server} reads the HELLO because it
     * has to, in order to tell a control connection from a data one. All that is left here is the
     * answer.
     *
     * <p>The reverse order of {@link #dialled}, and it buys something. A dialler has to send its
     * sequence cursor before it knows who it is talking to, so the cursor is keyed by the only name
     * it has — the target it dialled. Here the peer names itself first, so the cursor is keyed by
     * <b>what the peer calls itself</b>, which is also what the sheet and the log should show for a
     * link nobody chose an address for.
     *
     * <p>That does mean an outbound link to a peer and an inbound one from the same peer keep two
     * cursors. The cost is one redundant catch-up offer on the second route, which the far end
     * answers with HAVE; the alternative — keying by node id — would put a UUID in front of the user
     * wherever the target appears.
     */
    static Link accepted(SyncService s, Owner o, Connection c, Hello hello) throws Exception {
        String target = c.peerLabel;
        try {
            c.sendHello(s, o.clipTs(), o.clipSha());
        } catch (Exception e) {
            c.close();
            throw e;
        }
        return new Link(o, target, c, hello);
    }

    /**
     * @param target the dialer's target, not the advertised service name. Those are different
     *               things and conflating them cost two bugs: the log alternated between two names
     *               for one dialer, and the per-target sequence cursor was keyed by the name the PC
     *               advertises — so renaming the PC silently reset the LAN path's cursor. The
     *               advertised name is still what the UI shows; it reaches it through the peer's
     *               HELLO ({@code Connection.peerLabel}), which is where a display name belongs.
     * @param inst   the advertisement this dialer was created for, with every address it named
     */
    static Link viaMdns(SyncService s, Owner o, String target, Mdns.Instance inst, Network net) throws Exception {
        return dialled(s, o, target, Connection.toInstance(s, o.config(), inst, net));
    }

    Connection connection() {
        return conn;
    }

    /** The peer's node id, known once the handshake is done. */
    String peerId() {
        return conn.peerId;
    }

    boolean isOpen() {
        return open;
    }

    /**
     * The test that replaces {@code conn == c}: the question that was actually meant, and one that
     * stays meaningful when there are several links.
     */
    private boolean alive() {
        return open && owner.isRunning();
    }

    @Override
    public void close() {
        open = false;
        conn.close();
    }

    /**
     * Close deliberately, telling the peer why first.
     *
     * <p>The BYE is the whole point: a close without one becomes a loop. The far side would see only
     * a disconnect, reconnect, and rebuild exactly the link that was discarded.
     */
    void bye(String reason) {
        try {
            conn.sendJson(Connection.T_BYE, new org.json.JSONObject().put("reason", reason));
        } catch (Exception ignored) {
            // it is going away regardless; a peer that cannot hear the reason still sees the close
        }
        close();
    }

    /**
     * One keep-alive frame, sent by the device's single heartbeat. Carries {@code t1} so the peer's
     * PONG can be turned into a clock offset; see {@link #clockOffset}.
     */
    void ping() throws Exception {
        if (alive()) {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("t1", System.currentTimeMillis());
            conn.sendJson(Connection.T_PING, j);
        }
    }

    /**
     * Send the current local clip if this link has not already delivered it.
     *
     * <p>Idempotent, because it is called from three places — on connect, when a clip is captured,
     * and after a burst — and a peer must get a clip exactly once however many of those fire.
     */
    synchronized void deliver() throws Exception {
        Object clip = owner.pendingClip();
        if (clip == null) return;
        // Through the same normalisation the rest of the device uses: this hash is compared against
        // the one the pending slot is keyed by, so computing it the other way here would make every
        // clip containing a CRLF look undelivered forever.
        String h = clip instanceof Files.Ref ? ((Files.Ref) clip).sha256
                : Crypto.sha256Hex(ClipboardBridge.normalise((String) clip));
        if (h.equals(sentHash)) return;
        // Marked after the send, not before. Marking first left a link claiming delivery it had not
        // made when the send threw — and, worse, skipped `delivered`, so the clip was never released
        // and went on justifying a reconnect every burst interval for the whole five-minute window.
        // The cost of this order is a possible duplicate if the send half-succeeded, which the peer
        // discards by hash; the cost of the other was a phone that would not settle.
        owner.send(this, clip);
        sentHash = h;
        owner.delivered(this, h);
    }

    /** The content hash this link has delivered, or null. */
    String sentHash() {
        return sentHash;
    }

    /**
     * Run the session to its end. Returns when the peer goes away, the screen-off burst finishes, or
     * the link is closed from outside.
     *
     * @return true when this was a screen-off burst rather than a session that ended. The caller
     *         must not back off on that: a burst finishing is the expected outcome, not a failure.
     */
    boolean run() throws Exception {
        owner.onConnected(this);
        deliver();
        if (!owner.isScreenOn()) {
            burst();
            // Say why before going. Every session that ends because *this* device is asleep ends
            // here — the one the screen-off transition interrupted, and every later one a peer
            // opens while we stay asleep — so this is the single place the fact can be told, and
            // the second kind matters just as much: a peer that dials a sleeping device and is cut
            // off without a word sees a reset, which reads as a fault and is not one.
            bye(Connection.BYE_IDLE);
            return true;
        }
        while (alive()) {
            owner.onFrame(this, conn.recv());
        }
        return false;
    }

    /**
     * Woken only to deliver a pending clip: give the peer a moment to answer — a WANT for an offered
     * file, or anything newer — then let the link drop so the radio can sleep. While an offer is open
     * or a transfer is running the window stays open, with a hard cap; otherwise one second of
     * silence ends it.
     */
    private void burst() throws Exception {
        long until = System.currentTimeMillis() + BURST_CAP_MS;
        try {
            while (System.currentTimeMillis() < until) {
                conn.setSoTimeout(owner.transferBusy() ? BURST_BUSY_MS : BURST_IDLE_MS);
                owner.onFrame(this, conn.recv());
            }
        } catch (java.net.SocketTimeoutException expected) {
            // Read timeout: the burst is over, and this is the ONE clean ending it has.
        } catch (Exception e) {
            // Everything else — a frame that will not decrypt, a protocol error, a handler that
            // threw — used to end here too, and the caller read that as "the burst finished". So a
            // link that failed every single time reconnected every thirty seconds all night with
            // the screen off, and the UI showed no fault because nothing ever reported one.
            if (!alive()) return;        // we closed it: the read failing afterwards is the consequence
            Logger.w("burst on " + conn.peerLabel + " ended in error: " + e);
            throw e;
        }
    }

    private static final long BURST_CAP_MS = 120_000;
    // int, because Connection.setSoTimeout takes one — a long here compiles as a lossy conversion.
    private static final int BURST_BUSY_MS = 10_000, BURST_IDLE_MS = 1_000;
}
