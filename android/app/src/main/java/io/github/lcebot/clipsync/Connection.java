package io.github.lcebot.clipsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

/**
 * One authenticated TCP session with one peer, dialled or accepted. Wire format in clipsync.py.
 */
public final class Connection implements AutoCloseable {
    // ---- frame types, grouped by purpose and renumbered for the draft protocol.
    // No external release has ever shipped, so nothing reads the old numbers.

    // Session lifecycle.
    public static final int T_HELLO = 1;
    /**
     * "I am closing this connection, and here is why" — {@code {reason}}.
     *
     * <p>It exists because <b>a close without one becomes a loop</b>, and that failure needs no
     * network trouble to trigger. When a duplicate link is dropped, the far side sees nothing but a
     * disconnect, its reconnect logic fires, and it rebuilds exactly the link that was discarded — to
     * be discarded again. A peer that receives BYE does not schedule a redial.
     */
    public static final int T_BYE = 2;
    public static final int T_PING = 3, T_PONG = 4;
    /**
     * The {@code BYE} reason a device sends as it goes to sleep, and the one that does not mean
     * "this link was redundant".
     *
     * <p>The two must not be confused, because they ask for opposite things: a duplicate tells the
     * peer to defer to the link that won, while an idle peer has no winning link to defer to and
     * wants to be left alone until it dials out again. It lives here, beside the frame type, because
     * both ends compare against it and neither owns it.
     */
    public static final String BYE_IDLE = "idle";

    // Key rotation: the PSK is replaced on a schedule, and both ends accept the outgoing key for a
    // while so the changeover is invisible. Keys holds the arithmetic.
    /**
     * Sent after HELLO on a control connection, carrying this device's key schedule: {@code {psk,
     * since, next}}. Both ends send one if rotation is enabled; the receiver reconciles with
     * {@link Keys#betterNext} and persists the result.
     */
    public static final int T_KEYS = 5;

    // Clipboard.
    public static final int T_CLIP = 6;

    // File transfer.
    public static final int T_OFFER = 7, T_WANT = 8, T_HAVE = 9, T_SKIP = 10;
    public static final int T_CHUNK = 11, T_PULL = 12, T_END = 13, T_ABORT = 14;

    // Pairing: getting the PSK onto a second device without typing 64 hex characters into it. The
    // channel is an ordinary one keyed by a nine-digit code instead of the PSK; see Pairing.
    /**
     * {@code PAIR_ASK} joiner → provider, "give me the key", and {@code PAIR_KEY} back with it.
     *
     * <p>Ordinary frames on an ordinary channel, reached after an ordinary handshake and an ordinary
     * {@code HELLO} — only the key differs. The first sketch had the joiner send a special frame
     * <em>instead of</em> HELLO, which would have created a parsing path that runs before any key
     * has been proven; that one rule is what makes an open port safe to expose, and varying the key
     * and adding a role costs nothing by comparison.
     */
    public static final int T_PAIR_ASK = 15, T_PAIR_KEY = 16;

    // Relay coordination: a node that cannot reach a sender directly asks a peer that can to hold
    // the file and offer it on.
    /**
     * {@code RELAY_ASK} waiter → relay, "I am waiting for this sha; offer it to me when you have it."
     * {@code RELAY_OK} relay → waiter, "accepted."
     * {@code RELAY_NO} relay → waiter, "declined" — with a reason: {@code busy} (retryable) or
     * {@code refused} (final: opted out, over the size limit, or battery too low).
     */
    public static final int T_RELAY_ASK = 17, T_RELAY_OK = 18, T_RELAY_NO = 19;

    // Peer roster exchange: a node tells each direct peer about its other direct peers, so every
    // node knows the 2-hop neighbourhood and can build a complete OFFER `to`.
    public static final int T_PEERS = 20;
    /**
     * 2: HELLO is exchanged in both directions and carries the node id, type, persistence and
     * battery bucket (see {@link Hello}). A clean break rather than a tolerated one, which is this
     * project's standing rule for protocol changes — the two ends ship together, so a version
     * mismatch is refused with a plain message instead of being worked around. A version 1 peer
     * cannot name itself, and a peer that cannot name itself cannot be deduplicated or recognised
     * as self.
     *
     * <p>3: HELLO also carries {@code port} and {@code data_out}, and every device listens.
     *
     * <p>The bump is not bookkeeping. The two new fields have defaults, so a version-2 peer would
     * connect and work — and then answer a WANT by opening its own data connections at the same
     * moment as this end opens its, because the rule that stops that is the field it does not send.
     * Every file would move twice. A failure a version check turns into one refused connection with
     * a plain message is worth a version number; both ends are updated together regardless.
     *
     * <p>5: the clipboard hash is taken over <b>newline-normalised</b> text (CRLF read as LF), OFFER
     * headers carry {@code forwarded}, and RELAY_ASK carries {@code size}. The first of those is why
     * this is a version and not three optional fields: the sha is a value on the wire that both ends
     * compute independently, so a peer using the other rule does not degrade, it disagrees — every
     * clip containing a Windows line ending would be dropped as corrupt, silently from the user's
     * side. The two ends ship together, so a refused handshake with a plain message is the cheapest
     * way for a mismatched pair to say so.
     */
    public static final int PROTOCOL_VERSION = 5;
    /** Chunk size (CHUNK frames carry u32 index ‖ bytes); also the largest frame anyone buffers. */
    public static final int CHUNK = 512 * 1024;

    public static int chunks(long size) {
        return (int) Math.max(1, (size + CHUNK - 1) / CHUNK);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MDNS_CONNECT_TIMEOUT_MS = 3_000;   // LAN: fail fast
    private static final int READ_TIMEOUT_MS = 90_000;
    /**
     * How long an accepted socket may stay silent before it has said who it is.
     *
     * <p>Much shorter than {@link #READ_TIMEOUT_MS} on purpose. An authenticated peer is allowed to
     * be quiet for a heartbeat interval; before that, silence is either a stuck network or something
     * that is not a peer at all, and either way it is holding one of a bounded number of accept
     * workers. Raised to the session timeout the moment the HELLO lands.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;

    // The single "last mDNS address that worked" cache is gone, along with the single LAN peer it
    // assumed. What replaces it is the service's own map of discovered instances, refreshed by a
    // browse on a schedule: a cache of one address could not hold two peers, and a cache with a
    // 24-hour life could not notice one of them leaving.

    /**
     * Targets a handshake has proved to be this device.
     *
     * <p>Held per process rather than written to the configuration: it is a *discovery*, not a
     * setting, and a name that resolves here today may resolve elsewhere tomorrow. It is cleared
     * whenever the configuration changes, because the user editing the list is exactly the moment to
     * stop believing what the old list implied.
     */
    private static final Set<String> selfTargets = ConcurrentHashMap.newKeySet();

    static boolean isKnownSelf(String target) {
        return selfTargets.contains(Config.normalisePeer(target));
    }

    static void rememberSelf(String target) {
        if (target != null) selfTargets.add(Config.normalisePeer(target));
    }

    /** Forget everything learned at run time about targets (the configuration changed). */
    public static void forgetLearned() {
        selfTargets.clear();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    /**
     * The two halves of the channel, one per direction.
     *
     * <p>Stateful on purpose. They own the nonce counters that used to be {@code txCtr}/{@code
     * rxCtr} here, which turned "a nonce is never reused under one key" from a convention this class
     * had to keep into something it cannot break: there is no way from outside to set a counter, and
     * no way to seal a frame without advancing one. {@code tx} is touched only under
     * {@link #sendLock}; {@code rx} only on the single thread that reads this connection.
     */
    private Crypto.Sealer tx;
    private Crypto.Opener rx;
    private final int maxFrame;
    /** This device's own listening port, declared in HELLO so an accepted peer can reach us back. */
    private final int listenPort;

    /**
     * Candidate channels for an inbound connection that has not yet identified its peer's key.
     *
     * <p>Null once the key is resolved — either immediately for outbound connections (which always
     * use the current PSK) or on the first {@link #recv()} for inbound ones. The candidate list is
     * {@link Keys.Schedule#accepted()}, so every key the device considers valid is tried, in the
     * order worth trying: current first, then successor, then the ring.
     *
     * <p>One {@link Crypto.Opener} per candidate rather than one shared counter, which is what makes
     * trial decryption safe to write: an Opener that fails has not advanced, so every loser is still
     * at frame 0 and the winner is at frame 1 — exactly the state the connection needs to keep.
     */
    private Crypto.Sealer[] candidateSealers;
    private Crypto.Opener[] candidateOpeners;
    private byte[][] candidateSecrets;
    private boolean keyResolved;

    /**
     * Whether the peer has proved it holds a key we accept, by sending one frame that decrypts.
     *
     * <p>Not the same question as {@link #keyResolved}, which is only "do we know <em>which</em> of
     * several keys to use" and is true from the start whenever there is exactly one candidate — the
     * ordinary case, which is precisely the case the limits below exist for. True from the start for
     * a connection we opened: we chose the address, and a data connection's first inbound frame is a
     * 512 KiB chunk, which no pre-auth cap may refuse.
     */
    private boolean peerAuthenticated;
    /**
     * When an unauthenticated connection has run out of time, on the monotonic clock.
     *
     * <p>Absolute, where {@link #HANDSHAKE_TIMEOUT_MS} as a socket timeout is per read. The two
     * differ for exactly the caller worth stopping: one byte every fourteen seconds never trips a
     * per-read timeout, so it can hold an inbound worker for as long as it likes at no cost to
     * itself. A deadline set at construction is the only thing that bounds it.
     */
    private final long handshakeDeadline;

    /**
     * The secret that authenticated this channel, or null for outbound connections where it is
     * always the current PSK.
     *
     * <p>Exposed so that {@code SyncService} can tell whether the peer is using the current key,
     * the successor, or an old one, and act accordingly: a peer on the successor has promoted
     * ahead of us, and one on an old key is behind.
     */
    public byte[] matchedSecret;
    private final Object sendLock = new Object();
    /** "direct", "mdns" or "inbound" — which path this session came up on (for logs and the UI). */
    public final String via;
    /**
     * True when the peer opened this connection, not us.
     *
     * <p>It decides <b>which half of the nonce exchange</b> to perform, and which key label is ours:
     * the labels are named for who opened the socket, so an accepted connection is the "s" side of
     * the peer's link. Getting that backwards does not fail at the handshake, which exchanges
     * plaintext nonces; it fails at the first frame, as a decrypt error.
     *
     * <p>It is also half of {@link #drivesTransfer()} — the other half being what the peer declares
     * it can do — and it is why {@link #peerPort} exists: an accepted socket's remote port is the
     * peer's ephemeral source port, so the listening port has to be declared rather than observed.
     */
    public final boolean inbound;
    /** Human-readable peer: listed address or mDNS service name, plus the address actually used. */
    public final String peer;
    /** Just the name part: the listed address, or the advertised mDNS service name. */
    public final String peerName;
    /** What the peer calls itself, once it has said so in HELLO; the address until then. */
    public String peerLabel;
    /**
     * True when the peer is on our LAN: reached via mDNS, or its address is on one of the prefixes
     * of the network we are using. Decides which file-size limit applies (sent in HELLO too).
     *
     * <p>Not final any more, because an accepted connection learns it from the peer's HELLO and the
     * HELLO arrives after the constructor. Taking the dialler's word rather than re-deciding is
     * deliberate and is what the PC has always done: both ends must hold the <em>same</em> value,
     * because link dedup prefers the LAN link over the internet one and two ends that disagree
     * about which link is which can pick opposite winners and close both.
     *
     * <p>On a data connection it is not decided here at all: {@link #data} copies the control
     * connection's value over the constructor's guess, because a data connection has no Context to
     * answer {@link OnLink} with and {@code via} alone would call a LAN peer remote on every link
     * that did not come up over mDNS.
     */
    public volatile boolean lanPeer;
    /** Where this session connected; data connections for file transfer go to the same place. */
    public final InetSocketAddress remote;
    /**
     * The network this session rides on, or null if it could not be established.
     *
     * <p>Two things need it. A socket <b>bound</b> to a network fails immediately when that network
     * goes away, instead of hanging until the 90-second read timeout — switching between Wi-Fi and
     * cellular does not close a TCP socket, it leaves it half-open, and that delay was the whole of
     * what "the drop is noticed at once" asks for. And with several peers, "which connections died"
     * is a question that cannot be answered at all while sockets ride the default network
     * anonymously.
     */
    public final Network network;

    // ---- what the peer declared in its HELLO (protocol 2 onwards; see Hello). Set by readHello().
    /** The peer's node id. The key for link dedup, OFFER recipients and priority tie-breaks. */
    public String peerId;
    /** {@code pc} | {@code tablet} | {@code phone}. */
    public String peerType = "?";
    /** Whether the peer can hold a connection while idle — a capability it declares, not a guess. */
    public boolean peerPersistent;
    /** {@code mains} | {@code high} | {@code medium} | {@code low}. */
    public String peerBattery = "medium";
    /**
     * Where the peer listens, from its HELLO; the port of {@link #remote} until it says.
     *
     * <p>Needed only on an accepted connection, and needed badly there: data connections go to the
     * peer's <em>listening</em> port, and the one visible on an accepted socket is the ephemeral
     * source port of its dial, which reaches nothing. On a connection we opened the two are the same
     * number, so this changes nothing in that direction.
     */
    public int peerPort;
    /**
     * Whether the peer can open data connections of its own.
     *
     * <p>A file's bytes move over separate connections, and <b>exactly one</b> of the two nodes must
     * open them: both opening transfers the file twice, neither opening transfers it not at all.
     * While a phone only ever dialled a PC that only ever accepted, the answer was structural and
     * needed no field. It stopped being structural the moment the PC gained a client role. Both
     * ends now declare {@code true} — the PC opens its own data connections as of the release that
     * fixed PC-to-PC transfers — so the rule is simply "whoever dialled drives", which is what
     * {@link #drivesTransfer()} computes. The field stays because the rule is a negotiation, not a
     * constant: an end that cannot dial out says so and the other one takes the job.
     */
    public boolean peerDataOut;

    /**
     * One listed address. Resolves and connects to that name and nothing else.
     *
     * <p>Deliberately not a race across every target: the losers such a race closes are the other
     * peers. {@link #connectDirect} still tries every address <em>that one name</em> resolves to,
     * which is the only sense in which alternatives exist here.
     */
    public static Connection toPeer(Context ctx, Config cfg, String peer, Network net) throws Exception {
        return new Connection(cfg.psk, cfg.maxFrame(), cfg.port,
                new Object[]{connectDirect(peer, cfg.port, net), "direct", peer}, ctx, net, false);
    }

    /**
     * A connection a peer opened to us.
     *
     * <p>The device listens now, which is what makes phone-to-tablet possible at all: neither of
     * them has a stable address the other can be configured with, and only one of the two needs to
     * find the other for both to be connected.
     *
     * <p>The name is the address until the peer says otherwise: on this side the HELLO arrives
     * before we answer, so there is no window in which the peer is anonymous for long.
     *
     * <p>Multi-key: every key in {@link Keys.Schedule#accepted()} is tried against the first
     * frame, so a peer using the current key, the successor, or any key still in the ring is let
     * in. Which key matched is exposed via {@link #matchedSecret} once the first frame lands.
     */
    public static Connection accept(Context ctx, Config cfg, Socket s, Network net) throws Exception {
        // One unusable entry skipped, not the whole ring abandoned. The hex parse used to throw from
        // here, so a single malformed key anywhere in the ring made every inbound connection fail
        // before a byte was read, permanently and across restarts. Crypto.fromHex answers null
        // instead, which is what makes skipping the bad entry the natural thing to write. The
        // validation in Config should keep one out; this is what stops a value that got in anyway
        // from taking the listening socket down with it.
        List<byte[]> usable = new ArrayList<>();
        for (String h : cfg.keys.accepted()) {
            byte[] k = Crypto.fromHex(h);
            if (k == null) Logger.w("keys: skipping a malformed key in the ring");
            else usable.add(k);
        }
        if (usable.isEmpty()) throw new IOException("no usable key to authenticate with");
        byte[][] secrets = usable.toArray(new byte[0][]);
        return new Connection(secrets, cfg.maxFrame(), cfg.port,
                new Object[]{s, "inbound", String.valueOf(s.getRemoteSocketAddress())
                        .replaceFirst("^[^/]*/", "")}, ctx, net, true);
    }

    /**
     * One peer found on the local network, reached at whichever of its addresses answers first.
     *
     * <p>It takes an instance rather than browsing for itself, and that is the difference between
     * one peer and several. Browsing inside the connect meant "find the LAN peer and connect to it",
     * which cannot express two of them; the browse now belongs to the service, which keeps one
     * dialler per instance it has found, and this is only the connect.
     *
     * <p>Still a race, and still the right one: a machine typically advertises every adapter it has
     * (VMware/Hyper-V/WSL/hotspot subnets included), so these are several routes to <em>one</em>
     * peer. Addresses on our own subnet go first, then Happy-Eyeballs, rather than eating a 3 s
     * timeout per dead address.
     *
     * @param net the active network, used to rank addresses by whether they are on-link
     */
    public static Connection toInstance(Context ctx, Config cfg, Mdns.Instance inst, Network net) throws Exception {
        List<Mdns.Candidate> cands = new ArrayList<>();
        for (InetSocketAddress a : inst.addrs) cands.add(new Mdns.Candidate(a, inst.name));
        if (cands.isEmpty()) throw new IOException("mdns: " + inst.name + " advertised no usable address");
        cands = OnLink.sort(ctx, net, cands);
        Won w = race(cands, MDNS_CONNECT_TIMEOUT_MS, net);
        return new Connection(cfg.psk, cfg.maxFrame(), cfg.port,
                new Object[]{w.s, "mdns", inst.name}, ctx, net, false);
    }

    /**
     * A data connection to a known address (opened by the transfer workers, several in parallel).
     *
     * <p>Opened only by the end that {@link #drivesTransfer()} names, and aimed at the peer's
     * declared listening port rather than at the control socket's remote port.
     */
    public static Connection data(Config cfg, Connection control, String sha256, String device) throws Exception {
        // The peer's LISTENING port, not the port of the control socket. On a connection we opened
        // they are the same number; on one we accepted, the control socket's is the ephemeral source
        // port of the peer's dial and connecting to it reaches nothing at all.
        InetSocketAddress to = new InetSocketAddress(control.remote.getAddress(), control.peerPort);
        Connection c = new Connection(cfg.psk, cfg.maxFrame(), cfg.port, new Object[]{
                connectTo(to, control.lanPeer ? MDNS_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS, control.network),
                control.via, "data"}, null, control.network, false);
        // Copied from the control connection rather than left to the constructor's guess. A data
        // connection is built with ctx == null, so the constructor can only fall back to "did the
        // link come up over mDNS" — which says true for an mdns link and false for a `direct` or
        // `inbound` one even when the peer is a hop away on the same LAN. Nothing reads it on a data
        // connection today, and a wrong answer waiting for its first reader is worse than no answer:
        // the control connection settled this during its handshake, so take its verdict.
        c.lanPeer = control.lanPeer;
        // The id is here so an accepting peer can tell whose transfer this is; the sha names which
        // file. Everything a peer link declares is deliberately absent — see Hello.data.
        c.sendJson(T_HELLO, Hello.data(Node.id(), device, sha256).toJson());
        return c;
    }

    /**
     * The two ends of a pairing channel: the same machinery with a code-derived key.
     *
     * <p>Neither takes a {@link Config}, and that is not tidiness — a device being paired has no
     * usable configuration yet, which is the whole reason it is being paired. The three things a
     * channel actually needs are a secret, a frame cap and, for HELLO, a port; a Config is merely
     * where an ordinary link finds them.
     *
     * <p>{@code maxFrame} is small on purpose: a pairing channel carries two short JSON frames and
     * nothing else, so the cap is a bound on what an unauthenticated caller can make this end
     * allocate before the handshake proves anything.
     */
    public static Connection pairTo(InetSocketAddress addr, byte[] key, Network net) throws Exception {
        // Bound to the network the browse ran on, like every other connect here: the address came
        // from a LAN advertisement, and a phone whose default route is cellular would otherwise send
        // a private address out of the modem and wait for the timeout.
        return new Connection(key, PAIR_MAX_FRAME, 0,
                new Object[]{connectTo(addr, MDNS_CONNECT_TIMEOUT_MS, net), "pair", String.valueOf(addr)},
                null, net, false);
    }

    public static Connection pairAccept(Socket s, byte[] key) throws Exception {
        return new Connection(key, PAIR_MAX_FRAME, 0,
                new Object[]{s, "pair", String.valueOf(s.getRemoteSocketAddress()).replaceFirst("^[^/]*/", "")},
                null, null, true);
    }

    /** Two short JSON frames is all a pairing channel ever carries. */
    private static final int PAIR_MAX_FRAME = 4096;

    /**
     * The most an <b>unauthenticated</b> caller may make this end allocate.
     *
     * <p>The same rule the pairing channel has always had, applied where it was missing. Until the
     * first frame decrypts, nothing about the peer is known — the length prefix is plaintext and
     * anybody who can reach the port can write one, so the ordinary frame cap (a chunk, over half a
     * megabyte) times the inbound worker limit is what an attacker gets to allocate for the cost of
     * a TCP handshake. A real HELLO is a dozen short fields; 8 KiB leaves room for a long device
     * name and still ends that.
     */
    private static final int PRE_AUTH_MAX_FRAME = 8192;

    /**
     * Our half of a pairing declaration: who is asking, and nothing that only a configured node has.
     *
     * <p>Deliberately not {@link #sendHello}: that one carries a node id, a sequence cursor and the
     * capability flags of a peer, none of which a device without a key has any business claiming —
     * and the id is what the self-check and the dedup map key on, so an unpaired device offering one
     * would be enrolling itself into machinery it is not part of yet.
     */
    public void sendPairHello(Context ctx) throws Exception {
        sendJson(T_HELLO, Hello.pair(Node.name(), Node.type(ctx)).toJson());
    }

    /**
     * The control handshake: declare ourselves, then read what the peer declares back.
     *
     * <p>Protocol 2 made this an exchange. Before it, only the PC learned anything — the phone sent
     * its name and never heard a reply — which left no way to tell two peers apart, to notice that
     * two addresses lead to one machine, or to notice that one of them leads here.
     *
     * <p>Which is the last thing this does: **if the peer's id is ours, the connection is dropped.**
     * Both ends hold the same PSK, so the handshake succeeds and the node would otherwise enrol
     * itself as a peer — broadcasting to itself and comparing versions against its own clips. The
     * user's declared own-addresses list catches the common spellings before a socket is ever
     * opened; this catches everything else, and is the authority.
     *
     * @return the peer's HELLO — the dialler's half of catch-up reads {@link Hello#clipTs} and
     *         {@link Hello#clipSha} from it to decide whether the peer is behind. Discarding it was
     *         how two phones could reconnect and stay out of step: the fields were sent by both ends
     *         and consumed by neither.
     * @throws SelfConnection when the peer turns out to be this device
     */
    public Hello hello(Context ctx, long clipTs, String clipSha) throws Exception {
        sendHello(ctx, clipTs, clipSha);
        return readHello();
    }

    /**
     * Our half of the declaration.
     *
     * <p>Separate from {@link #readHello()} because the two ends do them in opposite orders, and
     * the order is not a detail: the dialler declares first because it has nothing to wait for,
     * and the accepter answers — which means an accepted connection knows who the peer is
     * <em>before</em> it has to say anything, and can therefore send a sequence cursor that is
     * actually about that peer rather than a zero.
     */
    public void sendHello(Context ctx, long clipTs, String clipSha) throws Exception {
        // Values, not a Context: Hello is the semantic layer and knows nothing about Android. The
        // two fields nothing else could supply are listenPort — an accepted connection cannot see
        // our listening port any other way — and lanPeer, which is this end's verdict on whether the
        // two are on one LAN and which the accepter takes as final.
        Hello mine = Hello.control(Node.id(), Node.name(), Node.type(ctx),
                Node.persistent(ctx), Node.battery(ctx), clipTs, clipSha, lanPeer, listenPort);
        // Once per process, and here rather than at start-up because this is the only place a real
        // outgoing declaration exists: the thing being checked is what control() built, not a
        // reconstruction of it. See Hello.auditSent.
        mine.auditSent();
        sendJson(T_HELLO, mine.toJson());
    }

    /**
     * Read what the peer declares, and refuse it here if it cannot be talked to.
     *
     * <p>Parsing is {@link Hello#parse}'s job; what is left here is everything that is about
     * <em>this connection</em> rather than about the message — the version gate, the fields that
     * become connection state, the self-check, and raising the read timeout now that the peer has
     * proved it is one.
     *
     * @return the peer's declaration, for the fields only the caller cares about
     *         ({@link Hello#clipTs}, {@link Hello#clipSha}, and on an accepted connection
     *         {@link Hello#role} and {@link Hello#sha256})
     * @throws SelfConnection when the peer turns out to be this device
     */
    public Hello readHello() throws Exception {
        Frame f = recv();
        if (f.type != T_HELLO) throw new IOException("expected HELLO, got frame type " + f.type);
        Hello theirs = Hello.parse(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
        if (theirs.v != PROTOCOL_VERSION)
            throw new IOException("protocol version mismatch (peer speaks " + theirs.v
                    + ", we speak " + PROTOCOL_VERSION + ")");
        peerId = theirs.id;
        peerType = theirs.type;
        peerPersistent = theirs.persistent;
        peerBattery = theirs.battery;
        // Only if it said: a peer that omits the field leaves us with the remote port we already
        // have, which is the right number on a connection we opened.
        if (theirs.has(Hello.K_PORT)) peerPort = theirs.port;
        peerDataOut = theirs.dataOut;
        if (!theirs.device.isEmpty()) peerLabel = theirs.device;
        socket.setSoTimeout(READ_TIMEOUT_MS);      // it has spoken; the short leash was for silence
        // A stream or a pairing exchange, not a peer: neither enrols anything, so neither needs the
        // node checks below. Both still filled peerLabel and peerType above, and on the pairing path
        // those two are the only description of the caller the provider has to put in front of the
        // user before it hands the key over — the joiner has no node id to show.
        if (Hello.ROLE_DATA.equals(theirs.role) || Hello.ROLE_PAIR.equals(theirs.role)) return theirs;
        theirs.audit(peerLabel);
        // Protocol 2's premise is that a peer can name itself, and everything downstream assumes it:
        // a link with no id cannot be deduplicated, cannot be recognised as this device, and would
        // sit outside the map that the heartbeat, the broadcast and the status all iterate — running
        // but reaching nobody. Refusing here is much easier to diagnose than that.
        if (peerId == null || peerId.isEmpty()) throw new IOException("peer sent no node id");
        if (peerId.equals(Node.id())) throw new SelfConnection(peerName);
        // On an accepted connection the dialler's verdict is the one that counts (see lanPeer). The
        // `has` test is what distinguishes "the peer says we are not on one LAN" from "the peer said
        // nothing", which are different answers and must not collapse into false.
        if (inbound && theirs.has(Hello.K_LAN)) lanPeer = theirs.lan;
        return theirs;
    }

    /**
     * The peer reached by this connection turned out to be this device.
     *
     * <p>Carries no address of its own: the dialler marks the target it was <em>dialling</em>, which
     * is the string its own map is keyed by, and not the name this connection happened to reach.
     * The two differ for a discovery target, and the one that stops the redial is the dialler's.
     */
    public static final class SelfConnection extends IOException {
        SelfConnection(String target) {
            super("that is this device: " + target);
        }
    }

    /**
     * Single-key constructor: outbound connections, pairing, and data channels.
     *
     * @param secret the shared key this channel's per-direction keys are derived from. The PSK for
     *               an ordinary link and a code-derived key for a pairing one — which is the whole
     *               of what makes pairing possible without new machinery: confidentiality,
     *               authentication and replay resistance all come along unchanged, and a caller
     *               without the code fails at the handshake exactly as a wrong PSK does today.
     */
    private Connection(byte[] secret, int maxFrame, int listenPort,
                       Object[] r, Context ctx, Network net, boolean inbound) throws Exception {
        this(new byte[][]{secret}, maxFrame, listenPort, r, ctx, net, inbound);
    }

    /**
     * Multi-key constructor: inbound connections try every candidate.
     *
     * <p>The nonce exchange is key-independent (plaintext), so it runs once. If there is a single
     * key the channel is ready immediately; with several, key derivation is done for each and the
     * actual selection is deferred to the first {@link #recv()}, which trial-decrypts until one
     * succeeds.
     */
    private Connection(byte[][] secrets, int maxFrame, int listenPort,
                       Object[] r, Context ctx, Network net, boolean inbound) throws Exception {
        this.maxFrame = maxFrame;
        this.listenPort = listenPort;
        this.inbound = inbound;
        this.network = net;
        this.handshakeDeadline = android.os.SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS;
        this.peerAuthenticated = !inbound;
        socket = (Socket) r[0];
        via = (String) r[1];
        remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        // The right answer for a connection we opened, and a placeholder for one we accepted until
        // its HELLO corrects it.
        peerPort = remote.getPort();
        peerName = String.valueOf(r[2]);
        peerLabel = peerName;
        peer = peerName + " [" + remote + "]";
        lanPeer = "mdns".equals(via) || (ctx != null && OnLink.isOnLink(ctx, net, socket.getInetAddress()));
        socket.setSoTimeout(inbound ? HANDSHAKE_TIMEOUT_MS : READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        // A hint to the kernel and nothing more: Android's default idle time before the first probe
        // is two hours, which is far past every timeout here, and it is not settable from this API.
        // The heartbeat is what actually notices a dead peer; this only helps on the ROMs that
        // shorten the default themselves.
        socket.setKeepAlive(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        // handshake: Nc -> ; <- Ns ; keys = HKDF(psk, Nc||Ns, info).
        //
        // The labels are named for who OPENED the connection, not for who is a server — a device
        // now both dials and accepts, so an accepted connection is the "s" side of the peer's link
        // and must read the client nonce first. Getting this backwards does not fail at the
        // handshake, which exchanges plaintext nonces; it fails at the first frame, as a decrypt
        // error, which is a much more expensive way to find out.
        byte[] mine = new byte[32], theirs = new byte[32];
        Crypto.RNG.nextBytes(mine);
        if (inbound) {
            in.readFully(theirs);
            out.write(mine);
            out.flush();
        } else {
            out.write(mine);
            out.flush();            // before the read, always: the peer is waiting for these bytes
            in.readFully(theirs);
        }
        byte[] nc = inbound ? theirs : mine, ns = inbound ? mine : theirs;
        byte[] salt = ByteBuffer.allocate(64).put(nc).put(ns).array();

        if (secrets.length == 1) {
            byte[] c2s = Crypto.hkdfSha256(secrets[0], salt, "clipsync c2s".getBytes(StandardCharsets.US_ASCII), 32);
            byte[] s2c = Crypto.hkdfSha256(secrets[0], salt, "clipsync s2c".getBytes(StandardCharsets.US_ASCII), 32);
            tx = new Crypto.Sealer(inbound ? s2c : c2s);
            rx = new Crypto.Opener(inbound ? c2s : s2c);
            matchedSecret = secrets[0];
            keyResolved = true;
        } else {
            // Derive a channel for every candidate; the first recv() picks the winner.
            candidateSecrets = secrets;
            candidateSealers = new Crypto.Sealer[secrets.length];
            candidateOpeners = new Crypto.Opener[secrets.length];
            for (int i = 0; i < secrets.length; i++) {
                byte[] c2s = Crypto.hkdfSha256(secrets[i], salt, "clipsync c2s".getBytes(StandardCharsets.US_ASCII), 32);
                byte[] s2c = Crypto.hkdfSha256(secrets[i], salt, "clipsync s2c".getBytes(StandardCharsets.US_ASCII), 32);
                candidateSealers[i] = new Crypto.Sealer(inbound ? s2c : c2s);
                candidateOpeners[i] = new Crypto.Opener(inbound ? c2s : s2c);
            }
            keyResolved = false;
        }
    }

    /**
     * Resolve fresh every time — the address behind a name can move, which is the whole point of a
     * dynamic one — prefer IPv6, try each address. A literal is returned by getAllByName without a
     * lookup, so an address entered directly costs nothing extra here.
     */
    private static Socket connectDirect(String host, int port, Network net) throws IOException {
        // Resolved on the network too, when there is one: the default resolver can answer from a
        // different interface's DNS than the one the socket will use, which on a phone with Wi-Fi
        // and cellular both up is how a LAN name resolves to nothing.
        InetAddress[] all = net != null ? net.getAllByName(host) : InetAddress.getAllByName(host);
        List<InetAddress> ordered = new ArrayList<>();
        for (InetAddress a : all) if (a instanceof Inet6Address) ordered.add(a);
        for (InetAddress a : all) if (!(a instanceof Inet6Address)) ordered.add(a);
        IOException last = null;
        for (InetAddress a : ordered) {
            try {
                return connectTo(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS, net);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no address for " + host);
    }

    private static final class Won {
        final Socket s;
        final Mdns.Candidate c;
        Won(Socket s, Mdns.Candidate c) { this.s = s; this.c = c; }
    }

    private static final ExecutorService RACE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "clipsync-race");
        t.setDaemon(true);
        return t;
    });
    private static final int RACE_STAGGER_MS = 250;

    /**
     * Happy-Eyeballs style: start a connect to each candidate in order, {@link #RACE_STAGGER_MS}
     * apart, return the first that succeeds and close the rest. Total bound ≈ stagger·n + timeout.
     */
    private static Won race(List<Mdns.Candidate> cands, int timeoutMs, Network net) throws IOException {
        CompletionService<Won> cs = new ExecutorCompletionService<>(RACE_POOL);
        List<Future<Won>> futures = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        final AtomicBoolean finished = new AtomicBoolean(false);
        Won won = null;
        try {
            for (int i = 0; i < cands.size(); i++) {
                final Mdns.Candidate c = cands.get(i);
                final long startAt = System.currentTimeMillis() + (long) i * RACE_STAGGER_MS;
                futures.add(cs.submit(() -> {
                    long d = startAt - System.currentTimeMillis();
                    if (d > 0) Thread.sleep(d);
                    if (finished.get()) throw new IOException("race already won");
                    Socket s = connectTo(c.addr, timeoutMs, net);
                    if (finished.get()) {           // late winner: don't leave a half-open client on the server
                        s.close();
                        throw new IOException("race already won");
                    }
                    return new Won(s, c);
                }));
            }
            // One deadline for the whole race, not one per candidate. The poll used to take the
            // full bound each time round the loop, so the "≈ stagger·n + timeout" above was the
            // bound on a *single* wait and the real worst case was that figure times the number of
            // addresses — eight adapters on one PC is forty-eight seconds with the dialler thread
            // blocked throughout, for a peer that is simply not there.
            long until = System.currentTimeMillis()
                    + (long) RACE_STAGGER_MS * cands.size() + timeoutMs + 1000;
            for (int done = 0; done < cands.size() && won == null; done++) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                Future<Won> f = cs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    won = f.get();
                } catch (ExecutionException e) {
                    errors.add(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finished.set(true);
            for (Future<Won> f : futures) {
                if (won != null && f.isDone() && !f.isCancelled()) {
                    try {
                        Won other = f.get();
                        if (other != won) other.s.close();
                    } catch (Exception ignored) {
                    }
                } else {
                    f.cancel(true);
                }
            }
        }
        if (won == null) throw new IOException(errors.isEmpty() ? "no candidate answered" : errors.get(errors.size() - 1));
        return won;
    }

    /** Orders candidates so that addresses on one of the phone's own prefixes come first. */
    private static final class OnLink {
        static List<LinkAddress> mine(Context ctx, Network net) {
            List<LinkAddress> mine = new ArrayList<>();
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            LinkProperties lp = (cm != null && net != null) ? cm.getLinkProperties(net) : null;
            if (lp != null) mine.addAll(lp.getLinkAddresses());
            return mine;
        }

        static boolean isOnLink(Context ctx, Network net, InetAddress a) {
            return a != null && onLink(a, mine(ctx, net));
        }

        static List<Mdns.Candidate> sort(Context ctx, Network net, List<Mdns.Candidate> in) {
            List<LinkAddress> mine = mine(ctx, net);
            List<Mdns.Candidate> first = new ArrayList<>(), rest = new ArrayList<>();
            for (Mdns.Candidate c : in) (onLink(c.addr.getAddress(), mine) ? first : rest).add(c);
            first.addAll(rest);
            return first;
        }

        static boolean onLink(InetAddress a, List<LinkAddress> mine) {
            byte[] x = a.getAddress();
            for (LinkAddress la : mine) {
                byte[] y = la.getAddress().getAddress();
                if (y.length != x.length) continue;
                int bits = la.getPrefixLength();
                if (a instanceof Inet6Address && a.isLinkLocalAddress()) return true;   // fe80:: is by definition on-link
                boolean same = true;
                for (int i = 0; i < bits && same; i++) {
                    int mask = 0x80 >> (i & 7);
                    same = ((x[i >> 3] ^ y[i >> 3]) & mask) == 0;
                }
                if (same) return true;
            }
            return false;
        }
    }

    /**
     * @param net the network to pin this socket to, or null to use the default route. Binding must
     *            happen before the connect, which is why it is here and not in the constructor.
     */
    private static Socket connectTo(InetSocketAddress addr, int timeoutMs, Network net) throws IOException {
        Socket s = new Socket();
        try {
            if (net != null) net.bindSocket(s);
            s.connect(addr, timeoutMs);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    public void send(int type, byte[] payload) throws Exception {
        byte[] pt = new byte[1 + payload.length];
        pt[0] = (byte) type;
        System.arraycopy(payload, 0, pt, 1, payload.length);
        synchronized (sendLock) {
            byte[] ct = tx.seal(pt);
            byte[] frame = ByteBuffer.allocate(4 + ct.length).putInt(ct.length).put(ct).array();
            out.write(frame);
            out.flush();
        }
    }

    /**
     * Does this end open the data connections for files on this link?
     *
     * <p>Exactly one of the two must, and this is the rule that decides it:
     *
     * <ul>
     *   <li>if the peer <b>cannot</b> open them, we do, whoever dialled — the PC is in exactly this
     *       position, having gained a client role for control connections and none for data ones;
     *   <li>otherwise <b>the node that dialled</b> does. It has proved it can reach the other's
     *       listening port, which is precisely what a data connection needs, and the far end reaches
     *       the same verdict from the same two facts.
     * </ul>
     *
     * <p>Getting this wrong in either direction is a visible failure rather than an inefficiency:
     * both ends opening moves every file twice, and neither opening leaves a WANT unanswered
     * forever.
     */
    public boolean drivesTransfer() {
        return !peerDataOut || !inbound;
    }

    public void setSoTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    public void send(int type) throws Exception {
        send(type, new byte[0]);
    }

    public void sendJson(int type, org.json.JSONObject o) throws Exception {
        send(type, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * CHUNK frame: u32 index ‖ bytes.
     *
     * <p>Through two buffers held for the life of the connection, rather than three allocations per
     * frame. A data connection sends nothing but chunks, so the buffers are as long-lived as it is
     * and are allocated on first use — an ordinary control connection never sends a chunk and never
     * pays for them. Both are written only under {@link #sendLock}, which is what makes holding them
     * on the connection safe.
     *
     * <p>{@code chunkFrame} is sized exactly, not generously, and the margin is zero: Conscrypt
     * refuses a write-through {@code doFinal} whose destination has fewer than
     * {@code plaintextLen + 16} bytes left, and the largest plaintext this sends is
     * {@code 1 + 4 + CHUNK}. So {@code 4 + 1 + 4 + CHUNK + 16} is the smallest array that works and
     * one byte less is a ShortBufferException on every full chunk. See {@code Crypto.Sealer.sealInto}
     * for why 16 is a constant here and not something to ask the provider for.
     *
     * <p>Two separate arrays rather than one, also deliberately: Conscrypt tolerates sealing within
     * a single array but does it by copying the input range first, which is the copy this whole
     * arrangement exists to avoid.
     */
    private byte[] chunkPlain, chunkFrame;

    public void sendChunk(int index, byte[] data, int len) throws Exception {
        synchronized (sendLock) {
            if (chunkPlain == null) {
                chunkPlain = new byte[1 + 4 + CHUNK];
                chunkFrame = new byte[4 + 1 + 4 + CHUNK + 16];   // length prefix ‖ ciphertext ‖ tag
            }
            chunkPlain[0] = (byte) T_CHUNK;
            chunkPlain[1] = (byte) (index >>> 24); chunkPlain[2] = (byte) (index >>> 16);
            chunkPlain[3] = (byte) (index >>> 8);  chunkPlain[4] = (byte) index;
            System.arraycopy(data, 0, chunkPlain, 5, len);
            int n = tx.sealInto(chunkPlain, 0, 5 + len, chunkFrame, 4);
            chunkFrame[0] = (byte) (n >>> 24); chunkFrame[1] = (byte) (n >>> 16);
            chunkFrame[2] = (byte) (n >>> 8);   chunkFrame[3] = (byte) n;
            out.write(chunkFrame, 0, 4 + n);
            out.flush();
        }
    }

    /** Blocks until a frame arrives. Returns {type, payload}. */
    public Frame recv() throws Exception {
        if (!peerAuthenticated) {
            // Three checks and not one, because a read timeout is per read: the socket timeout is
            // trimmed to what is left of the deadline so a single stalled read cannot outlive it,
            // and the deadline is tested again after the body because a caller that dribbles bytes
            // restarts that timeout with every one of them.
            long left = handshakeDeadline - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) throw new IOException("handshake did not finish in "
                    + HANDSHAKE_TIMEOUT_MS / 1000 + "s");
            socket.setSoTimeout((int) left);
        }
        int len = in.readInt();
        // min, not the constant: a pairing channel's own cap is smaller still, and this must only
        // ever tighten a limit, never raise one.
        int cap = peerAuthenticated ? maxFrame : Math.min(maxFrame, PRE_AUTH_MAX_FRAME);
        if (len < 17 || len > cap) throw new IOException("bad frame length " + len);
        byte[] ct = new byte[len];
        in.readFully(ct);
        if (!peerAuthenticated && android.os.SystemClock.elapsedRealtime() > handshakeDeadline)
            throw new IOException("handshake did not finish in " + HANDSHAKE_TIMEOUT_MS / 1000 + "s");

        if (!keyResolved) {
            // Trial-decrypt with each candidate. The first that succeeds is the peer's key.
            for (int i = 0; i < candidateOpeners.length; i++) {
                try {
                    byte[] pt = candidateOpeners[i].open(ct);
                    // Commit to this channel. The winning Opener has already advanced past this
                    // frame and the losers have not advanced at all, so nothing needs correcting.
                    rx = candidateOpeners[i];
                    tx = candidateSealers[i];
                    matchedSecret = candidateSecrets[i];
                    keyResolved = true;
                    peerAuthenticated = true;
                    candidateOpeners = null;
                    candidateSealers = null;
                    candidateSecrets = null;
                    byte[] payload = new byte[pt.length - 1];
                    System.arraycopy(pt, 1, payload, 0, payload.length);
                    return new Frame(pt[0] & 0xff, payload);
                } catch (GeneralSecurityException ignored) {
                    // Wrong key — try the next candidate.
                }
            }
            throw new IOException("no accepted key could authenticate this peer");
        }

        byte[] pt = rx.open(ct);
        peerAuthenticated = true;       // it decrypted under a key we accept; that is the proof
        byte[] payload = new byte[pt.length - 1];
        System.arraycopy(pt, 1, payload, 0, payload.length);
        return new Frame(pt[0] & 0xff, payload);
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
