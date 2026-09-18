package io.github.lcebot.clipsync;

/**
 * One session with one peer, from the moment a socket is opened to the moment it is torn down.
 *
 * <p><b>This class exists to be duplicated.</b> Everything in it used to live inside one lexical
 * scope in {@code SyncService.mainLoop}, which braided seven concerns together — gating, connecting,
 * the handshake, the screen-off burst, the heartbeat, the read loop and teardown — and used
 * {@code conn == c} as the test for "am I still the current session". That idiom means "is this
 * <em>the</em> one", not "is this alive", and it does not generalise to several at once. Pulling the
 * session out first makes holding N of them a matter of starting N of these, rather than a rewrite
 * of the loop that holds one.
 *
 * <p><b>Behaviour here is deliberately identical to what mainLoop did.</b> This step is a move, not a
 * change: the same order, the same timeouts, the same logging, the same teardown. What is different
 * is only that the decisions which will have to become per-peer or global are now visible as
 * separate methods rather than as consecutive paragraphs. Each one is marked below with which way it
 * will go, because that is the question the next step has to answer and this is where the evidence
 * for it lives.
 *
 * <p>The owner ({@link SyncService}) supplies everything shared through {@link Owner}. The split is
 * the point: what a Link may decide for itself is what it holds, and what belongs to the device is
 * what it has to ask for.
 *
 * <p>Teardown is <em>not</em> here. It still runs in the service's retry loop, because it also
 * decides whether and when to try again, and that decision is about to become per-target rather than
 * per-session. Moving it before that is settled would move it twice.
 */
final class Link implements AutoCloseable {
    /** What a session needs from the service. Shared state stays on the far side of this. */
    interface Owner {
        /** GLOBAL: the clipboard is one device's, however many peers there are. */
        void onFrame(Link link, Connection.Frame frame) throws Exception;

        /** GLOBAL for now; per-peer once several peers can be behind on different things. */
        void onConnected(Link link);

        /**
         * How far into the peer's stream we have already seen, asked for at handshake time.
         *
         * <p>Asked for rather than passed in, and that is not arbitrary: the connect can take as long
         * as an mDNS browse, and a transfer finishing during it advances the cursor. Reading it
         * before the connect would send a value that was already stale by the time it went out. It is
         * also the shape step 2 needs — one cursor per peer, looked up by the peer being dialled.
         */
        long lastSeq();

        /** GLOBAL: something to deliver. Consumed once today; broadcast in step 2. */
        void flushPending(Connection c) throws Exception;

        /** GLOBAL: is a transfer or an open offer still outstanding? Keyed by peer in a later step. */
        boolean transferBusy();

        /** GLOBAL: the suspend detector and the status heartbeat, which must not run N times. */
        void onHeartbeat(long overshootMs);

        boolean isRunning();

        boolean isScreenOn();

        Config config();
    }

    private final Owner owner;
    private final Connection conn;
    private final boolean lan;
    private volatile boolean open = true;

    /**
     * Opens the socket and completes the handshake. Throws exactly what mainLoop used to let
     * propagate, including {@link Connection.SelfConnection}, which the caller treats specially.
     *
     */
    Link(SyncService service, Owner owner, boolean lan, android.net.Network net) throws Exception {
        this.owner = owner;
        this.lan = lan;
        this.conn = new Connection(service, owner.config(), lan, net);
        try {
            conn.hello(service, owner.lastSeq());
        } catch (Exception e) {
            conn.close();                  // the socket is ours from the moment the constructor ran
            throw e;
        }
    }

    Connection connection() {
        return conn;
    }

    boolean isOpen() {
        return open;
    }

    /**
     * The test that replaces {@code conn == c}.
     *
     * <p>The old one asked "is this still the connection the service is holding", which happened to
     * answer "is this session alive" only because there was exactly one. This asks the question that
     * was actually meant, and it stays true when there are several.
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
     * Run the session to its end. Returns when the peer goes away, the screen-off burst finishes, or
     * the link is closed from outside.
     *
     * @return true when this was a screen-off burst rather than a session that ended. The caller
     *         must not back off on that: a burst finishing is the expected outcome, not a failure,
     *         and treating it as one would double the back-off and overwrite the "idle" status every
     *         time the device delivered a clip while asleep.
     */
    boolean run() throws Exception {
        owner.onConnected(this);
        owner.flushPending(conn);
        if (!owner.isScreenOn()) {
            burst();
            return true;
        }
        heartbeat();
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
     *
     * <p>GLOBAL, and this is the one that will need a decision rather than a rename: with several
     * peers, "wake up briefly and deliver" cannot mean N independent bursts, because each is a radio
     * wake-up on a device that was deliberately asleep.
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

    /**
     * Keep-alive, and the two things that ride along with it: the frozen-process detector and the
     * status timestamp the UI reads for liveness.
     *
     * <p>GLOBAL in everything but the PING itself. The interval is per-link because it depends on
     * that link's transport, but a device with several peers must not run several detectors or
     * several status writers — and ideally not several timers at all, since each one wakes the radio.
     * {@link Owner#onHeartbeat} is where those land, so that step 2 can coalesce them without
     * touching this.
     */
    private void heartbeat() {
        Thread t = new Thread(() -> {
            try {
                long interval = lan ? PING_WIFI_MS : PING_MOBILE_MS;
                while (alive()) {
                    long before = System.currentTimeMillis();
                    Thread.sleep(interval);
                    owner.onHeartbeat(System.currentTimeMillis() - before - interval);
                    if (alive()) conn.send(Connection.T_PING);
                }
            } catch (Exception ignored) {
            }
        }, "clipsync-ping");
        t.setDaemon(true);
        t.start();
    }

    // keep-alive: a peer drops a silent client after 90 s. Cellular pings are spaced wider because
    // every one of them pulls the modem out of its idle state.
    static final long PING_WIFI_MS = 30_000, PING_MOBILE_MS = 45_000;
    private static final long BURST_CAP_MS = 120_000;
    // int, because Connection.setSoTimeout takes one — a long here compiles as a lossy conversion.
    private static final int BURST_BUSY_MS = 10_000, BURST_IDLE_MS = 1_000;
}
