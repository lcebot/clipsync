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

        /**
         * How far into this peer's stream we have seen.
         *
         * <p>Keyed by <b>target</b> and not by node id, for a reason that only shows up here: the
         * cursor has to go out in our HELLO, and the peer does not say who it is until its HELLO
         * comes back. The target is the only name we have at that moment, and in practice it maps to
         * one peer — a listed address, or the service instance mDNS resolved.
         *
         * <p>Asked for at handshake time rather than passed in: a connect can take as long as an
         * mDNS browse, and a transfer finishing during it advances the cursor.
         */
        long lastSeq(String target);

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

    private Link(Owner owner, String target, Connection c) {
        this.owner = owner;
        this.target = target;
        this.conn = c;
    }

    /** Completes the dialler's handshake, or throws having closed the socket it was given. */
    private static Link dialled(SyncService s, Owner o, String target, Connection c) throws Exception {
        try {
            c.hello(s, o.lastSeq(target));
        } catch (Exception e) {
            c.close();                     // the socket is ours from the moment we were handed it
            throw e;
        }
        return new Link(o, target, c);
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
    static Link accepted(SyncService s, Owner o, Connection c) throws Exception {
        String target = c.peerLabel;
        try {
            c.sendHello(s, o.lastSeq(target));
        } catch (Exception e) {
            c.close();
            throw e;
        }
        return new Link(o, target, c);
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
     * a disconnect, reconnect, and rebuild exactly the link that was discarded. (§5)
     */
    void bye(String reason) {
        try {
            conn.sendJson(Connection.T_BYE, new org.json.JSONObject().put("reason", reason));
        } catch (Exception ignored) {
            // it is going away regardless; a peer that cannot hear the reason still sees the close
        }
        close();
    }

    /** One keep-alive frame, sent by the device's single heartbeat. */
    void ping() throws Exception {
        if (alive()) conn.send(Connection.T_PING);
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
        String h = clip instanceof Files.Ref ? ((Files.Ref) clip).sha256 : Crypto.sha256Hex((String) clip);
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

    /** True once the peer has said goodbye: the link is closing on purpose, not failing. */
    boolean saidBye() {
        return bye != null;
    }

    /**
     * Why the peer said goodbye, or null if it did not.
     *
     * <p>Kept, where it used to be a bare flag, because the reason decides what the dialler does
     * next: a duplicate means defer to the link that won, and an idle peer means wait for it to come
     * back rather than dial into a device that has just gone to sleep. The peer took the trouble to
     * say which; throwing that away and treating every goodbye alike is the version of this that
     * wakes a sleeping phone once a minute.
     */
    @Nullable String byeReason() {
        return bye;
    }

    void markBye(String reason) {
        bye = reason == null ? "" : reason;
    }

    private volatile String bye;

    /**
     * True once <em>this</em> end closed the link as a duplicate.
     *
     * <p>The mirror of {@link #saidBye()}, and needed for the same reason from the other side. A
     * link closed from another thread leaves its owner seeing nothing but a dead socket, which is
     * indistinguishable from the peer going away — and a dialler that cannot tell those apart
     * redials immediately into the link it has just lost.
     */
    boolean superseded() {
        return superseded;
    }

    void markSuperseded() {
        superseded = true;
    }

    private volatile boolean superseded;

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
    private void burst() {
        long until = System.currentTimeMillis() + BURST_CAP_MS;
        try {
            while (System.currentTimeMillis() < until) {
                conn.setSoTimeout(owner.transferBusy() ? BURST_BUSY_MS : BURST_IDLE_MS);
                owner.onFrame(this, conn.recv());
            }
        } catch (Exception ignored) {
            // read timeout: the burst is over
        }
    }

    private static final long BURST_CAP_MS = 120_000;
    // int, because Connection.setSoTimeout takes one — a long here compiles as a lossy conversion.
    private static final int BURST_BUSY_MS = 10_000, BURST_IDLE_MS = 1_000;
}
