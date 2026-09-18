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
 * One authenticated TCP session to the Windows server. Wire format documented in clipsync.py.
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
     * 2: HELLO is exchanged in both directions and carries the node id, type, persistence and
     * battery bucket (docs/p2p-plan.md §2). A clean break, by §9 — a version 1 peer is refused
     * rather than tolerated, because a peer that cannot name itself cannot be deduplicated or
     * recognised as self. Both ends must be updated together.
     */
    public static final int PROTOCOL_VERSION = 2;
    /** Chunk size (CHUNK frames carry u32 index ‖ bytes); also the largest frame anyone buffers. */
    public static final int CHUNK = 512 * 1024;

    public static int chunks(long size) {
        return (int) Math.max(1, (size + CHUNK - 1) / CHUNK);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MDNS_CONNECT_TIMEOUT_MS = 3_000;   // LAN: fail fast
    private static final int READ_TIMEOUT_MS = 90_000;

    private static final long MDNS_CACHE_MS = 24 * 3600_000L;

    /** Last address that worked via mDNS (and its advertised name); tried before a fresh browse. */
    private static volatile InetSocketAddress mdnsCached;
    private static volatile String mdnsCachedName;
    private static volatile long mdnsCachedAt;

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

    /** Drop the cached LAN address and everything learned about self-targets (config changed). */
    public static void forgetMdns() {
        mdnsCached = null;
        selfTargets.clear();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] txKey, rxKey;
    private final int maxFrame;
    private long txCtr = 0, rxCtr = 0;
    private final Object sendLock = new Object();
    /** "direct" or "mdns" — which path this session came up on (for logs and the UI). */
    public final String via;
    /** Human-readable peer: listed address or mDNS service name, plus the address actually used. */
    public final String peer;
    /** Just the name part: the listed address, or the advertised mDNS service name. */
    public final String peerName;
    /** What the peer calls itself, once it has said so in HELLO; the address until then. */
    public String peerLabel;
    /**
     * True when the PC is on our LAN: reached via mDNS, or its address is on one of the prefixes
     * of the network we are using. Decides which file-size limit applies (sent in HELLO too).
     */
    public final boolean lanPeer;
    /** Where this session connected; data connections for file transfer go to the same place. */
    public final InetSocketAddress remote;

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
     * One listed address. Resolves and connects to that name and nothing else.
     *
     * <p>This replaces the constructor that raced every target and kept the first — see
     * {@link #connectAny}, which is gone. Racing was right while the device held one connection and
     * is exactly wrong now: the losers it closed are the other peers.
     */
    public static Connection toPeer(Context ctx, Config cfg, String peer, Network net) throws Exception {
        return new Connection(cfg, new Object[]{connectDirect(peer, cfg.port), "direct", peer}, ctx, net);
    }

    /**
     * The peer found on the local network.
     *
     * <p>Still one connection out of several candidates, and still a race — but the candidates here
     * are the addresses of *one* PC, which typically advertises every adapter it has. Choosing among
     * them is not the same thing as choosing among peers.
     *
     * @param net the active network, so the browse is pinned to it (may be null)
     */
    public static Connection viaMdns(Context ctx, Config cfg, Network net) throws Exception {
        InetSocketAddress cached = freshMdnsCache();
        if (cached != null) {
            try {
                return new Connection(cfg, new Object[]{connectTo(cached, MDNS_CONNECT_TIMEOUT_MS), "mdns", mdnsCachedName}, ctx, net);
            } catch (IOException e) {
                Logger.i("cached mdns address failed: " + e);
                mdnsCached = null;
            }
        }
        Logger.i("mdns: browsing for " + cfg.mdnsTimeoutMs + "ms");
        List<Mdns.Candidate> cands = Mdns.discover(ctx, net, cfg.mdnsTimeoutMs);
        if (cands.isEmpty())
            throw new IOException("mdns: no _clipsync._tcp service found (peer not advertising, "
                    + "UDP 5353 blocked, or AP client isolation)");
        // A PC typically advertises every adapter it has (VMware/Hyper-V/WSL/hotspot subnets
        // included). Put addresses on the phone's own subnet first, then race them Happy-Eyeballs
        // style instead of eating a 3 s timeout per dead address.
        cands = OnLink.sort(ctx, net, cands);
        Logger.i("mdns: " + cands.size() + " candidates, trying " + cands.get(0).addr + " first");
        Won w = race(cands, MDNS_CONNECT_TIMEOUT_MS);
        mdnsCached = w.c.addr;
        mdnsCachedName = w.c.name;
        mdnsCachedAt = System.currentTimeMillis();
        return new Connection(cfg, new Object[]{w.s, "mdns", w.c.name}, ctx, net);
    }

    /** A data connection to a known address (opened by the transfer workers, several in parallel). */
    public static Connection data(Config cfg, Connection control, String sha256, String device) throws Exception {
        Connection c = new Connection(cfg, new Object[]{connectTo(control.remote, control.lanPeer ? MDNS_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS),
                control.via, "data"}, null, null);
        JSONObject hello = new JSONObject();
        hello.put("v", PROTOCOL_VERSION);
        hello.put("device", device);
        hello.put("role", "data");
        hello.put("sha256", sha256);
        c.sendJson(T_HELLO, hello);
        return c;
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
        JSONObject mine = new JSONObject();
        mine.put("v", PROTOCOL_VERSION);
        mine.put("id", Node.id());
        mine.put("device", Node.name());
        mine.put("type", Node.type(ctx));
        mine.put("persistent", Node.persistent(ctx));
        mine.put("battery", Node.battery(ctx));
        mine.put("last_seq", lastSeq);
        mine.put("lan", lanPeer);
        sendJson(T_HELLO, mine);

        Frame f = recv();
        if (f.type != T_HELLO) throw new IOException("expected HELLO, got frame type " + f.type);
        JSONObject theirs = new JSONObject(new String(f.payload, StandardCharsets.UTF_8));
        int v = theirs.optInt("v", -1);
        if (v != PROTOCOL_VERSION)
            throw new IOException("protocol version mismatch (peer speaks " + v + ", we speak " + PROTOCOL_VERSION + ")");
        peerId = theirs.optString("id", null);
        // Protocol 2's premise is that a peer can name itself, and everything downstream assumes it:
        // a link with no id cannot be deduplicated, cannot be recognised as this device, and would
        // sit outside the map that the heartbeat, the broadcast and the status all iterate — running
        // but reaching nobody. Refusing here is much easier to diagnose than that.
        if (peerId == null || peerId.isEmpty()) throw new IOException("peer sent no node id");
        peerType = theirs.optString("type", "?");
        peerPersistent = theirs.optBoolean("persistent", false);
        peerBattery = theirs.optString("battery", "medium");
        String name = theirs.optString("device", "");
        if (!name.isEmpty()) peerLabel = name;
        if (peerId != null && peerId.equals(Node.id())) throw new SelfConnection(peerName);
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

    private Connection(Config cfg, Object[] r, Context ctx, Network net) throws Exception {
        this.maxFrame = cfg.maxFrame();
        socket = (Socket) r[0];
        via = (String) r[1];
        remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        peerName = String.valueOf(r[2]);
        peerLabel = peerName;
        peer = peerName + " [" + remote + "]";
        lanPeer = "mdns".equals(via) || (ctx != null && OnLink.isOnLink(ctx, net, socket.getInetAddress()));
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        // handshake: Nc -> ; <- Ns ; keys = HKDF(psk, Nc||Ns, info)
        byte[] nc = new byte[32];
        Crypto.RNG.nextBytes(nc);
        out.write(nc);
        out.flush();
        byte[] ns = new byte[32];
        in.readFully(ns);
        byte[] salt = ByteBuffer.allocate(64).put(nc).put(ns).array();
        txKey = Crypto.hkdfSha256(cfg.psk, salt, "clipsync c2s".getBytes(StandardCharsets.US_ASCII), 32);
        rxKey = Crypto.hkdfSha256(cfg.psk, salt, "clipsync s2c".getBytes(StandardCharsets.US_ASCII), 32);
    }

    // connectAny() and firstToConnect() are gone with the single connection they served. They raced
    // every listed address and the LAN together and kept whichever answered first, closing the rest
    // — which was the right shape for a device that held one link and is precisely the wrong one for
    // a device that holds several: the sockets it threw away are the other peers. What it did well
    // survives, in two pieces that each race only the things that really are alternatives to each
    // other: toPeer() resolves one name (getAllByName already tries every address that name has),
    // and viaMdns() races the addresses of one PC.
    //
    // The failover it gave for free — a listed address that is down costing nothing but its own
    // timeout, in parallel with the others — survives too, and is now structural: every target has
    // its own dialer and its own back-off, so a dead one cannot delay a live one at all rather than
    // merely not delaying it much.

    private static InetSocketAddress freshMdnsCache() {
        InetSocketAddress c = mdnsCached;
        return c != null && System.currentTimeMillis() - mdnsCachedAt < MDNS_CACHE_MS ? c : null;
    }

    /**
     * Resolve fresh every time — the address behind a name can move, which is the whole point of a
     * dynamic one — prefer IPv6, try each address. A literal is returned by getAllByName without a
     * lookup, so an address entered directly costs nothing extra here.
     */
    private static Socket connectDirect(String host, int port) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> ordered = new ArrayList<>();
        for (InetAddress a : all) if (a instanceof Inet6Address) ordered.add(a);
        for (InetAddress a : all) if (!(a instanceof Inet6Address)) ordered.add(a);
        IOException last = null;
        for (InetAddress a : ordered) {
            try {
                return connectTo(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
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
    private static Won race(List<Mdns.Candidate> cands, int timeoutMs) throws IOException {
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
                    Socket s = connectTo(c.addr, timeoutMs);
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

    private static Socket connectTo(InetSocketAddress addr, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
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
