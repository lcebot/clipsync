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
 * <p>Second connection path: used when the DDNS name does not resolve / connect, or when no
 * host is configured at all. Only the PC that owns the DDNS address advertises (see
 * clipsync.py is_ddns_host), so normally exactly one service is found; every candidate is
 * tried anyway and the PSK handshake decides — a rogue advertiser only costs one failed connect.
 */
public final class Mdns {
    static final String SERVICE_TYPE = "_clipsync._tcp.";
    private static final ExecutorService SHARED_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-mdns");
        t.setDaemon(true);
        return t;
    });

    private Mdns() {}

    /** A resolved server: where to connect and what it calls itself. */
    public static final class Candidate {
        public final InetSocketAddress addr;
        public final String name;

        Candidate(InetSocketAddress addr, String name) {
            this.addr = addr;
            this.name = name;
        }
    }

    /**
     * Browse for up to {@code timeoutMs} and return candidates, best first.
     * Returns as soon as one service has been resolved. Blocking; call from the network
     * thread only.
     */
    public static List<Candidate> discover(Context ctx, Network net, long timeoutMs) {
        NsdManager nsd = ctx.getSystemService(NsdManager.class);
        if (nsd == null) return new ArrayList<>();
        Session s = new Session(nsd);
        try {
            // pinned to the active (Wi-Fi) network: multicast never leaks to a cellular interface
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, net, s.executor, s.browser);
            s.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Logger.w("mdns discovery: " + e);
        } finally {
            s.close();
        }
        List<Candidate> out = new ArrayList<>();
        synchronized (s.found) {
            for (Map.Entry<InetSocketAddress, String> e : s.found.entrySet())
                out.add(new Candidate(e.getKey(), e.getValue()));
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
        /** address -> advertised service name ("ClipSync on HOSTNAME"), insertion-ordered */
        final Map<InetSocketAddress, String> found = new LinkedHashMap<>();
        final CountDownLatch done = new CountDownLatch(1);
        private final List<NsdManager.ServiceInfoCallback> callbacks = new ArrayList<>();

        Session(NsdManager nsd) {
            this.nsd = nsd;
        }

        final NsdManager.DiscoveryListener browser = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int err) { Logger.w("mdns start failed " + err); done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int err) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo si) { }

            @Override
            public void onServiceFound(NsdServiceInfo si) {
                if (!si.getServiceType().contains("_clipsync._tcp")) return;
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
                            for (InetSocketAddress a : addrs) found.putIfAbsent(a, i.getServiceName());
                        }
                        Logger.i("mdns found " + i.getServiceName() + " " + addrs);
                        done.countDown();
                    }

                    @Override public void onServiceLost() { }
                    @Override public void onServiceInfoCallbackUnregistered() { }
                };
                synchronized (callbacks) { callbacks.add(cb); }
                try {
                    nsd.registerServiceInfoCallback(si, executor, cb);
                } catch (Exception e) {
                    Logger.i("mdns resolve " + si.getServiceName() + ": " + e);
                }
            }
        };

        void close() {
            synchronized (callbacks) {
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
