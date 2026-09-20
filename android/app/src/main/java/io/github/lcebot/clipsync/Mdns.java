package io.github.lcebot.clipsync;

import android.content.Context;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * LAN discovery of the Windows server via mDNS/DNS-SD ({@code _clipsync._tcp}), using the
 * platform NsdManager (no extra permissions, works from a background service).
 * Uses the API 34+ {@code registerServiceInfoCallback} resolution path (minSdk 35).
 *
 * <p>Both halves live here: {@link #discover} finds peers on the LAN, and {@link #advertise} makes
 * this device one of the peers that can be found. The second is what a phone and a tablet need from
 * each other, neither of them having an address the other could be configured with.
 *
 * <p>Several services on the LAN at once is the normal case, not a conflict to arbitrate: there is
 * no server role in this protocol, only peers. What a service advertises is only a label; who a
 * peer is comes from the node id in its HELLO, so a rogue advertiser costs one failed handshake and
 * nothing else.
 */
public final class Mdns {
    static final String SERVICE_TYPE = "_clipsync._tcp.";
    private static final ExecutorService SHARED_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-mdns");
        t.setDaemon(true);
        return t;
    });

    private Mdns() {}

    /**
     * Advertise this device on the LAN, so peers that cannot be configured with an address for it
     * can still find it.
     *
     * <p>Which is every phone and tablet: they have no stable name, and a user cannot type one into
     * the other's peer list. Only one of two devices has to find the other for both to be
     * connected; this is the half that makes the finding possible.
     *
     * <p>Failures are logged and swallowed. A device that cannot advertise can still be reached at a
     * listed address and can still dial out; it is a degradation, not a fault worth stopping for.
     *
     * <p><b>No TXT record on this service type</b>, deliberately, and note that the pairing type is
     * the opposite case, which is the whole of the reasoning. The Windows side advertises
     * {@code {"v": "5"}} here and nobody browses for it: neither this end nor clipsync.py has any
     * code that reads a peer's TXT on {@code _clipsync._tcp}. Matching it would look like a version
     * filter without being one, and a real one is not worth having here, because the version gate
     * belongs in the handshake ({@code Connection.readHello}) where both ends have said who they
     * are. A pre-dial filter would trade one avoided connection to a peer that cannot talk to us,
     * costing a single failed handshake nobody is watching, for the risk of silently skipping a
     * good peer whose advertisement was cached, truncated or merely missing.
     *
     * <p>Pairing does the reverse: {@link PairProvider} sends {@code v} and {@link PairJoiner} (with
     * {@code clipsync_pair.find}) checks it, because there the wasted connection costs a person
     * copying nine digits and waiting for a key, only to be told the code was wrong when it
     * was not. Same field, opposite answer, because the thing being spent is different.
     *
     * <p>The PC's {@code v} on this type is left alone: removing it is a change to a file this side
     * does not own, and if it ever goes, nothing here has to change.
     *
     * @return a handle to unregister with, or null if registration could not even be attempted
     */
    public static Advert advertise(Context ctx, String name, int port) {
        return advertise(ctx, SERVICE_TYPE, name, port, null);
    }

    /**
     * @param type the service type; pairing advertises under its own ({@link Pairing#SERVICE_TYPE})
     *             so that ordinary discovery never has to filter it out and the pairing browse never
     *             turns up ordinary nodes
     * @param txt  TXT record entries, or null. Pairing puts its salt here, public by design, since
     *             a salt is not a secret and only has to be unique, and the joiner needs it before
     *             it can derive the key it would connect with.
     */
    public static Advert advertise(Context ctx, String type, String name, int port, Map<String, String> txt) {
        NsdManager nsd = ctx.getSystemService(NsdManager.class);
        if (nsd == null) return null;
        NsdServiceInfo si = new NsdServiceInfo();
        si.setServiceName(name);
        si.setServiceType(type);
        si.setPort(port);
        if (txt != null) for (Map.Entry<String, String> e : txt.entrySet()) si.setAttribute(e.getKey(), e.getValue());
        Advert a = new Advert(nsd);
        try {
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, SHARED_EXECUTOR, a);
            return a;
        } catch (Exception e) {
            Logger.w("mdns advertise failed: " + e);
            return null;
        }
    }

    /**
     * One live registration.
     *
     * <p>Note that the name may come back changed: mDNS resolves a collision by suffixing, so two
     * devices that call themselves the same thing both keep advertising. That is the right outcome
     * and the reason the advertised name is only ever a label; who a peer <em>is</em> comes from
     * the node id in its HELLO, never from what it advertises.
     */
    public static final class Advert implements NsdManager.RegistrationListener, AutoCloseable {
        private final NsdManager nsd;

        Advert(NsdManager nsd) { this.nsd = nsd; }

        @Override public void onServiceRegistered(NsdServiceInfo si) {
            // The name, as registered: mDNS may have suffixed it to resolve a collision.
            Logger.i("advertising as \"" + si.getServiceName() + "\" " + SERVICE_TYPE);
        }

        @Override public void onRegistrationFailed(NsdServiceInfo si, int err) {
            Logger.w("mdns advertise failed (" + err + "): this device can dial out but will not be discovered");
        }

        @Override public void onServiceUnregistered(NsdServiceInfo si) { }

        @Override public void onUnregistrationFailed(NsdServiceInfo si, int err) { }

        @Override public void close() {
            // Attempted whether or not registration was confirmed: a registration still in flight
            // has no callback yet and would otherwise outlive the service that asked for it. An
            // unregister of something never registered throws, and that is the harmless half.
            try { nsd.unregisterService(this); } catch (Exception ignored) { }
        }
    }

    /** One address of one advertised service. */
    public static final class Candidate {
        public final InetSocketAddress addr;
        public final String name;

        Candidate(InetSocketAddress addr, String name) {
            this.addr = addr;
            this.name = name;
        }
    }

    /**
     * One advertised service: a peer, and every address it can be reached at.
     *
     * <p>The unit of discovery is the <b>instance</b> and not the address, which is the shape the
     * flat address list could not express. A machine typically advertises every adapter it has, so a
     * list of addresses conflates "several ways to one peer" with "several peers", and those are
     * exactly the two cases that have to be told apart now that there can be more than one peer on
     * the LAN. Racing the addresses of one instance is choosing a route; racing instances would be
     * choosing which peer to have, which is not a choice anyone wants made for them.
     */
    public static final class Instance {
        public final String name;
        public final List<InetSocketAddress> addrs;
        /**
         * The TXT record, never null. Empty for an ordinary Android node, which advertises none.
         *
         * <p>Read in exactly one place: {@code PairJoiner} takes the pairing salt out of it. A
         * Windows peer also puts a {@code v} here and nothing on either side reads it; see
         * {@link #advertise(Context, String, int)} for why this end neither sends nor consults one.
         */
        public final Map<String, String> attrs;
        /** When this was last seen advertising, so a peer that goes quiet can be forgotten. */
        public final long foundAt = System.currentTimeMillis();

        Instance(String name, List<InetSocketAddress> addrs, Map<String, String> attrs) {
            this.name = name;
            this.addrs = addrs;
            this.attrs = attrs == null ? Map.of() : attrs;
        }

        @Override public String toString() { return name + " " + addrs; }
    }

    /**
     * Browse for the whole of {@code timeoutMs} and return every service found.
     *
     * <p>The whole window is used deliberately, rather than returning as soon as one service
     * resolves: multicast replies from several devices do not arrive together, and returning early
     * would mean returning whichever peer answered first and never learning about the rest. The
     * window is the user's own setting, so its cost is visible and adjustable where the latency of a
     * missed peer would not be.
     *
     * <p>Blocking; call from a background thread only.
     */
    public static List<Instance> discover(Context ctx, Network net, long timeoutMs) {
        return discover(ctx, net, SERVICE_TYPE, timeoutMs);
    }

    /** @param type which service to browse for: ordinary nodes, or {@link Pairing#SERVICE_TYPE} */
    public static List<Instance> discover(Context ctx, Network net, String type, long timeoutMs) {
        NsdManager nsd = ctx.getSystemService(NsdManager.class);
        if (nsd == null) return new ArrayList<>();
        Session s = new Session(nsd, type);
        try {
            // pinned to the active (Wi-Fi) network: multicast never leaks to a cellular interface
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, net, s.executor, s.browser);
            s.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Logger.w("mdns discovery: " + e);
        } finally {
            s.close();
        }
        List<Instance> out = new ArrayList<>();
        synchronized (s.found) {
            for (Map.Entry<String, List<InetSocketAddress>> e : s.found.entrySet())
                if (!e.getValue().isEmpty()) {
                    out.add(new Instance(e.getKey(), new ArrayList<>(e.getValue()), s.txt.get(e.getKey())));
                }
        }
        return out;
    }

    /** One browse; every found service gets its own ServiceInfoCallback (they may run in parallel). */
    private static final class Session {
        final NsdManager nsd;
        // shared, never shut down: NsdManager may still deliver onDiscoveryStopped /
        // onServiceInfoCallbackUnregistered after close(), and a rejected execute() would throw
        // on the system callback thread
        final ExecutorService executor = SHARED_EXECUTOR;
        /** the type this browse asked for, minus its trailing dot, for matching what comes back */
        final String want;
        /** maps an advertised service name to every address it resolved to, insertion-ordered */
        final Map<String, List<InetSocketAddress>> found = new LinkedHashMap<>();
        /** maps an advertised service name to its TXT record, guarded by {@link #found} like the addresses */
        final Map<String, Map<String, String>> txt = new LinkedHashMap<>();
        /** Counted down only when the browse cannot start: otherwise the full window is the point. */
        final CountDownLatch done = new CountDownLatch(1);
        private final List<NsdManager.ServiceInfoCallback> callbacks = new ArrayList<>();
        /**
         * Set by {@link #close()}, and checked before registering another resolution callback.
         *
         * <p>NsdManager delivers {@code onServiceFound} on its own thread and does not stop at
         * {@code stopServiceDiscovery}, so a discovery arriving after close must not register a
         * resolution callback: nothing would ever unregister it, which would leak inside the system
         * service for the life of the process.
         */
        private boolean closed;

        Session(NsdManager nsd, String type) {
            this.nsd = nsd;
            this.want = type.endsWith(".") ? type.substring(0, type.length() - 1) : type;
        }

        final NsdManager.DiscoveryListener browser = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int err) { Logger.w("mdns start failed " + err); done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int err) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo si) { }

            @Override
            public void onServiceFound(NsdServiceInfo si) {
                // Belt and braces: discovery is per-type, so nothing else should arrive. Note that
                // the two types do not match each other by accident either: "_clipsync-pair._tcp"
                // does not contain "_clipsync._tcp".
                if (!si.getServiceType().contains(want)) return;
                NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                    @Override
                    public void onServiceInfoCallbackRegistrationFailed(int err) {
                        Logger.i("mdns resolve failed " + si.getServiceName() + " err " + err);
                    }

                    @Override
                    public void onServiceUpdated(NsdServiceInfo i) {
                        List<InetSocketAddress> addrs = addressesOf(i);
                        if (addrs.isEmpty()) return;        // SRV arrived before A/AAAA; wait for the next update
                        synchronized (found) {
                            List<InetSocketAddress> have = found.computeIfAbsent(i.getServiceName(), k -> new ArrayList<>());
                            for (InetSocketAddress a : addrs) if (!have.contains(a)) have.add(a);
                            Map<String, String> t = txt.computeIfAbsent(i.getServiceName(), k -> new LinkedHashMap<>());
                            for (Map.Entry<String, byte[]> e : i.getAttributes().entrySet()) {
                                // A TXT value may be present with no value at all ("key" rather than
                                // "key=value"), which arrives as null rather than as an empty array.
                                t.put(e.getKey(), e.getValue() == null ? ""
                                        : new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        Logger.i("mdns found " + i.getServiceName() + " " + addrs);
                    }

                    @Override public void onServiceLost() { }
                    @Override public void onServiceInfoCallbackUnregistered() { }
                };
                synchronized (callbacks) {
                    if (closed) return;         // the browse is over; nothing would unregister this
                    callbacks.add(cb);
                }
                try {
                    nsd.registerServiceInfoCallback(si, executor, cb);
                } catch (Exception e) {
                    Logger.i("mdns resolve " + si.getServiceName() + ": " + e);
                }
            }
        };

        void close() {
            synchronized (callbacks) {
                closed = true;
                for (NsdManager.ServiceInfoCallback cb : callbacks) {
                    try { nsd.unregisterServiceInfoCallback(cb); } catch (Exception ignored) {}
                }
                callbacks.clear();
            }
            try { nsd.stopServiceDiscovery(browser); } catch (Exception ignored) {}
        }
    }

    /** IPv4 first (most reliable on a LAN), then global IPv6, link-local last. */
    private static List<InetSocketAddress> addressesOf(NsdServiceInfo si) {
        int port = si.getPort();
        List<InetSocketAddress> v4 = new ArrayList<>(), v6 = new ArrayList<>(), ll = new ArrayList<>();
        for (InetAddress a : si.getHostAddresses()) {
            if (a == null || a.isLoopbackAddress() || a.isMulticastAddress() || port <= 0) continue;
            InetSocketAddress sa = new InetSocketAddress(a, port);
            if (a instanceof Inet4Address) v4.add(sa);
            else if (a instanceof Inet6Address && a.isLinkLocalAddress()) ll.add(sa);
            else v6.add(sa);
        }
        List<InetSocketAddress> out = new ArrayList<>(v4);
        out.addAll(v6);
        out.addAll(ll);
        return out;
    }
}
