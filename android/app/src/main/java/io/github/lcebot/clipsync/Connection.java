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
import java.util.ArrayList;
import java.util.List;
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
    public static final int T_HELLO = 1, T_CLIP = 2, T_PING = 3, T_PONG = 4, T_OFFER = 6, T_WANT = 7,
            T_HAVE = 8, T_SKIP = 9, T_END = 11, T_ABORT = 12, T_CHUNK = 13, T_PULL = 14;
    /**
     * "I am closing this connection, and here is why" — {@code {reason}}.
     *
     * <p>15, not one of the retired numbers (5 FILE, 10 DATA): reusing one would make an old log
     * impossible to read, and the numbers are not scarce.
     *
     * <p>It exists because <b>a close without one becomes a loop</b>, and that failure needs no
     * network trouble to trigger. When a duplicate link is dropped, the far side sees nothing but a
     * disconnect, its reconnect logic fires, and it rebuilds exactly the link that was discarded — to
     * be discarded again. A peer that receives BYE does not schedule a redial. (docs/p2p-plan.md §5)
     */
    public static final int T_BYE = 15;
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
    /**
     * Pairing (docs/p2p-plan.md §12): {@code PAIR_ASK} joiner → provider, "give me the key", and
     * {@code PAIR_KEY} back with it.
     *
     * <p>Ordinary frames on an ordinary channel, reached after an ordinary handshake and an ordinary
     * {@code HELLO} — only the key differs. The first sketch had the joiner send a special frame
     * <em>instead of</em> HELLO, which would have created a parsing path that runs before any key
     * has been proven; that one rule is what makes an open port safe to expose, and varying the key
     * and adding a role costs nothing by comparison.
     */
    public static final int T_PAIR_ASK = 16, T_PAIR_KEY = 17;
    /**
     * 2: HELLO is exchanged in both directions and carries the node id, type, persistence and
     * battery bucket (docs/p2p-plan.md §2). A clean break, by §9 — a version 1 peer is refused
     * rather than tolerated, because a peer that cannot name itself cannot be deduplicated or
     * recognised as self.
     *
     * <p>3: HELLO also carries {@code port} and {@code data_out}, and every device listens.
     *
     * <p>The bump is not bookkeeping. The two new fields have defaults, so a version-2 peer would
     * connect and work — and then answer a WANT by opening its own data connections at the same
     * moment as this end opens its, because the rule that stops that is the field it does not send.
     * Every file would move twice. A failure a version check turns into one refused connection with
     * a plain message is worth a version number; both ends are updated together regardless.
     */
    public static final int PROTOCOL_VERSION = 3;
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
    private final byte[] txKey, rxKey;
    private final int maxFrame;
    /** This device's own listening port, declared in HELLO so an accepted peer can reach us back. */
    private final int listenPort;
    private long txCtr = 0, rxCtr = 0;
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
     * (docs/p2p-plan.md §5, §7)
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
     * deliberate and is what the PC has always done: both ends must hold the <em>same</em> value or
     * §5's first dedup rule can reach opposite verdicts at the two ends and close both links.
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
     * anonymously. (docs/p2p-plan.md §5)
     */
    public final Network network;

    // ---- what the peer declared in its HELLO (protocol 2, docs/p2p-plan.md §2). Set by hello().
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
     * needed no field. It stopped being structural the moment the PC gained a client role, and the
     * PC still has no code to open a data connection — so it declares that, and this end takes the
     * job whoever dialled. See {@link #drivesTransfer()}.
     */
    public boolean peerDataOut;

    /**
     * One listed address. Resolves and connects to that name and nothing else.
     *
     * <p>This replaces the constructor that raced every target and kept the first — see
     * {@link #connectAny}, which is gone. Racing was right while the device held one connection and
     * is exactly wrong now: the losers it closed are the other peers.
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
     * find the other for both to be connected. (docs/p2p-plan.md §13, phase 4)
     *
     * <p>The name is the address until the peer says otherwise: on this side the HELLO arrives
     * before we answer, so there is no window in which the peer is anonymous for long.
     */
    public static Connection accept(Context ctx, Config cfg, Socket s, Network net) throws Exception {
        return new Connection(cfg.psk, cfg.maxFrame(), cfg.port,
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
        JSONObject hello = new JSONObject();
        hello.put("v", PROTOCOL_VERSION);
        hello.put("id", Node.id());        // so an accepting peer can tell whose transfer this is
        hello.put("device", device);
        hello.put("role", "data");
        hello.put("sha256", sha256);
        c.sendJson(T_HELLO, hello);
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
     * Our half of a pairing declaration: who is asking, and nothing that only a configured node has.
     *
     * <p>Deliberately not {@link #sendHello}: that one carries a node id, a sequence cursor and the
     * capability flags of a peer, none of which a device without a key has any business claiming —
     * and the id is what the self-check and the dedup map key on, so an unpaired device offering one
     * would be enrolling itself into machinery it is not part of yet.
     */
    public void sendPairHello(Context ctx) throws Exception {
        JSONObject mine = new JSONObject();
        mine.put("v", PROTOCOL_VERSION);
        mine.put("role", "pair");
        mine.put("device", Node.name());
        mine.put("type", Node.type(ctx));
        sendJson(T_HELLO, mine);
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
     * declared own-addresses list (§4a) catches the common spellings before a socket is ever opened;
     * this catches everything else, and is the authority. (docs/p2p-plan.md §5)
     *
     * @throws SelfConnection when the peer turns out to be this device
     */
    public void hello(Context ctx, long lastSeq) throws Exception {
        sendHello(ctx, lastSeq);
        readHello();
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
    public void sendHello(Context ctx, long lastSeq) throws Exception {
        JSONObject mine = new JSONObject();
        mine.put("v", PROTOCOL_VERSION);
        mine.put("id", Node.id());
        mine.put("device", Node.name());
        mine.put("type", Node.type(ctx));
        mine.put("persistent", Node.persistent(ctx));
        mine.put("battery", Node.battery(ctx));
        mine.put("last_seq", lastSeq);
        mine.put("lan", lanPeer);
        mine.put("port", listenPort);       // an accepted connection cannot see this any other way
        mine.put("data_out", true);         // this end can open data connections; see peerDataOut
        sendJson(T_HELLO, mine);
    }

    /**
     * Read what the peer declares, and refuse it here if it cannot be talked to.
     *
     * @return the peer's HELLO, for the fields only the caller cares about ({@code last_seq},
     *         and on an accepted connection {@code role} and {@code sha256})
     * @throws SelfConnection when the peer turns out to be this device
     */
    public JSONObject readHello() throws Exception {
        Frame f = recv();
        if (f.type != T_HELLO) throw new IOException("expected HELLO, got frame type " + f.type);
        JSONObject theirs = new JSONObject(new String(f.payload, StandardCharsets.UTF_8));
        int v = theirs.optInt("v", -1);
        if (v != PROTOCOL_VERSION)
            throw new IOException("protocol version mismatch (peer speaks " + v + ", we speak " + PROTOCOL_VERSION + ")");
        peerId = theirs.optString("id", null);
        peerType = theirs.optString("type", "?");
        peerPersistent = theirs.optBoolean("persistent", false);
        peerBattery = theirs.optString("battery", "medium");
        peerPort = theirs.optInt("port", peerPort);
        peerDataOut = theirs.optBoolean("data_out", false);
        String name = theirs.optString("device", "");
        if (!name.isEmpty()) peerLabel = name;
        socket.setSoTimeout(READ_TIMEOUT_MS);      // it has spoken; the short leash was for silence
        // A stream or a pairing exchange, not a peer: neither enrols anything, so neither needs the
        // node checks below. Both still filled the name and type above, which is what the provider
        // shows when it asks the user whether to hand over the key.
        String role = theirs.optString("role");
        if ("data".equals(role) || "pair".equals(role)) return theirs;
        // Protocol 2's premise is that a peer can name itself, and everything downstream assumes it:
        // a link with no id cannot be deduplicated, cannot be recognised as this device, and would
        // sit outside the map that the heartbeat, the broadcast and the status all iterate — running
        // but reaching nobody. Refusing here is much easier to diagnose than that.
        if (peerId == null || peerId.isEmpty()) throw new IOException("peer sent no node id");
        if (peerId.equals(Node.id())) throw new SelfConnection(peerName);
        // On an accepted connection the dialler's verdict is the one that counts (see lanPeer).
        if (inbound && theirs.has("lan")) lanPeer = theirs.optBoolean("lan", lanPeer);
        return theirs;
    }

    /** The peer reached by this connection turned out to be this device. */
    public static final class SelfConnection extends IOException {
        private final String target;

        SelfConnection(String target) {
            super("that is this device: " + target);
            this.target = target;
        }

        /** The address or service name that led here, so the caller can stop dialling it. */
        public String target() {
            return target;
        }
    }

    /**
     * @param secret the shared key this channel's per-direction keys are derived from. The PSK for
     *               an ordinary link and a code-derived key for a pairing one — which is the whole
     *               of what makes pairing possible without new machinery: confidentiality,
     *               authentication and replay resistance all come along unchanged, and a caller
     *               without the code fails at the handshake exactly as a wrong PSK does today.
     *               (docs/p2p-plan.md §12)
     */
    private Connection(byte[] secret, int maxFrame, int listenPort,
                       Object[] r, Context ctx, Network net, boolean inbound) throws Exception {
        this.maxFrame = maxFrame;
        this.listenPort = listenPort;
        this.inbound = inbound;
        this.network = net;
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
        byte[] c2s = Crypto.hkdfSha256(secret, salt, "clipsync c2s".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] s2c = Crypto.hkdfSha256(secret, salt, "clipsync s2c".getBytes(StandardCharsets.US_ASCII), 32);
        txKey = inbound ? s2c : c2s;
        rxKey = inbound ? c2s : s2c;
    }

    // connectAny() and firstToConnect() are gone with the single connection they served. They raced
    // every listed address and the LAN together and kept whichever answered first, closing the rest
    // — which was the right shape for a device that held one link and is precisely the wrong one for
    // a device that holds several: the sockets it threw away are the other peers. What it did well
    // survives, in two pieces that each race only the things that really are alternatives to each
    // other: toPeer() resolves one name (getAllByName already tries every address that name has),
    // and toInstance() races the addresses of one advertised peer.
    //
    // The failover it gave for free — a listed address that is down costing nothing but its own
    // timeout, in parallel with the others — survives too, and is now structural: every target has
    // its own dialer and its own back-off, so a dead one cannot delay a live one at all rather than
    // merely not delaying it much.

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
            for (int done = 0; done < cands.size() && won == null; done++) {
                Future<Won> f = cs.poll((long) RACE_STAGGER_MS * cands.size() + timeoutMs + 1000, TimeUnit.MILLISECONDS);
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
            byte[] ct = Crypto.seal(txKey, txCtr++, pt);
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

    /** CHUNK frame: u32 index ‖ bytes. */
    public void sendChunk(int index, byte[] data, int len) throws Exception {
        byte[] pt = new byte[1 + 4 + len];
        pt[0] = (byte) T_CHUNK;
        pt[1] = (byte) (index >>> 24); pt[2] = (byte) (index >>> 16); pt[3] = (byte) (index >>> 8); pt[4] = (byte) index;
        System.arraycopy(data, 0, pt, 5, len);
        synchronized (sendLock) {
            byte[] ct = Crypto.seal(txKey, txCtr++, pt);
            byte[] frame = ByteBuffer.allocate(4 + ct.length).putInt(ct.length).put(ct).array();
            out.write(frame);
            out.flush();
        }
    }

    /** Blocks until a frame arrives. Returns {type, payload}. */
    public Frame recv() throws Exception {
        int len = in.readInt();
        if (len < 17 || len > maxFrame) throw new IOException("bad frame length " + len);
        byte[] ct = new byte[len];
        in.readFully(ct);
        byte[] pt = Crypto.open(rxKey, rxCtr++, ct);
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
