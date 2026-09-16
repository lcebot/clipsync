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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One authenticated TCP session to the Windows server. Wire format documented in clipsync.py.
 */
public final class Connection implements AutoCloseable {
    public static final int T_HELLO = 1, T_CLIP = 2, T_PING = 3, T_PONG = 4, T_OFFER = 6, T_WANT = 7,
            T_HAVE = 8, T_SKIP = 9, T_END = 11, T_ABORT = 12, T_CHUNK = 13, T_PULL = 14;
    public static final int PROTOCOL_VERSION = 1;
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

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] txKey, rxKey;
    private final int maxFrame;
    private long txCtr = 0, rxCtr = 0;
    private final Object sendLock = new Object();
    /** "ddns" or "mdns" — which path this session came up on (for logs). */
    public final String via;
    /** Human-readable peer: DDNS name or mDNS service name, plus the address actually used. */
    public final String peer;
    /**
     * True when the PC is on our LAN: reached via mDNS, or its address is on one of the prefixes
     * of the network we are using. Decides which file-size limit applies (sent in HELLO too).
     */
    public final boolean lanPeer;
    /** Where this session connected; data connections for file transfer go to the same place. */
    public final InetSocketAddress remote;

    /**
     * @param lan true when the active network is Wi-Fi/Ethernet — the only place mDNS can work
     * @param net the active network, so the mDNS browse is pinned to it (may be null)
     */
    public Connection(Context ctx, Config cfg, boolean lan, Network net) throws Exception {
        this(cfg, connectAny(ctx, cfg, lan, net), ctx, net);
    }

    /** A data connection to a known address (opened by the transfer workers, several in parallel). */
    public static Connection data(Config cfg, Connection control, String sha256, String device) throws Exception {
        Connection c = new Connection(cfg, new Object[]{connectTo(control.remote, control.lanPeer ? MDNS_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS),
                control.via, "data"}, null, null);
        org.json.JSONObject hello = new org.json.JSONObject();
        hello.put("v", PROTOCOL_VERSION);
        hello.put("device", device);
        hello.put("role", "data");
        hello.put("sha256", sha256);
        c.sendJson(T_HELLO, hello);
        return c;
    }

    private Connection(Config cfg, Object[] r, Context ctx, Network net) throws Exception {
        this.maxFrame = cfg.maxFrame();
        socket = (Socket) r[0];
        via = (String) r[1];
        remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        peer = r[2] + " [" + remote + "]";
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

    /**
     * Path 1: DDNS name (fresh lookup, IPv6 first) — works on LAN and over the internet.
     * Path 2: mDNS — last known LAN address, then a fresh {@code _clipsync._tcp} browse.
     * Returns {socket, "ddns"|"mdns", peer name}.
     */
    private static Object[] connectAny(Context ctx, Config cfg, boolean lan, Network net) throws IOException {
        IOException last = null;

        if (!cfg.host.isEmpty()) {
            try {
                return new Object[]{connectDdns(cfg.host, cfg.port), "ddns", cfg.host};
            } catch (IOException e) {
                last = e;
                Logger.i("ddns path failed: " + e);
            }
        }

        // mDNS is link-local multicast: pointless (and a radio wake-up) on cellular
        if (cfg.mdns && lan) {
            InetSocketAddress cached = mdnsCached;
            if (cached != null && System.currentTimeMillis() - mdnsCachedAt < MDNS_CACHE_MS) {
                try {
                    return new Object[]{connectTo(cached, MDNS_CONNECT_TIMEOUT_MS), "mdns", mdnsCachedName};
                } catch (IOException e) {
                    Logger.i("cached mdns address failed: " + e);
                    mdnsCached = null;
                }
            }
            Logger.i("mdns: browsing for " + cfg.mdnsTimeoutMs + "ms");
            List<Mdns.Candidate> cands = Mdns.discover(ctx, net, cfg.mdnsTimeoutMs);
            if (cands.isEmpty()) {
                Logger.i("mdns: no _clipsync._tcp service found (PC not advertising, UDP 5353 blocked, or AP client isolation)");
                last = new IOException((last != null ? "ddns: " + last.getMessage() + "; " : "") + "mdns: no service found");
            } else {
                // A PC typically advertises every adapter it has (VMware/Hyper-V/WSL/hotspot
                // subnets included). Put addresses on the phone's own subnet first, then race
                // them Happy-Eyeballs style instead of eating a 3 s timeout per dead address.
                cands = OnLink.sort(ctx, net, cands);
                Logger.i("mdns: " + cands.size() + " candidates, trying " + cands.get(0).addr + " first");
                try {
                    Won w = race(cands, MDNS_CONNECT_TIMEOUT_MS);
                    mdnsCached = w.c.addr;
                    mdnsCachedName = w.c.name;
                    mdnsCachedAt = System.currentTimeMillis();
                    return new Object[]{w.s, "mdns", w.c.name};
                } catch (IOException e) {
                    Logger.i("mdns: no candidate accepted: " + e.getMessage());
                    last = e;
                }
            }
        } else if (cfg.mdns) {
            Logger.i("mdns: skipped (not on Wi-Fi/Ethernet)");
        }

        throw last != null ? last : new IOException(lan ? "no host configured and mdns disabled" : "no host configured; mdns needs Wi-Fi");
    }

    /** Resolve fresh every time (DDNS), prefer IPv6, try each address. */
    private static Socket connectDdns(String host, int port) throws IOException {
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
