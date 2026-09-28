package io.github.lcebot.clipsync

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LAN discovery of the Windows server via mDNS/DNS-SD (`_clipsync._tcp`), using the
 * platform NsdManager (no extra permissions, works from a background service).
 * Uses the API 34+ `registerServiceInfoCallback` resolution path (minSdk 35).
 *
 * Both halves live here: [discover] finds peers on the LAN, and [advertise] makes
 * this device one of the peers that can be found. The second is what a phone and a tablet need from
 * each other, neither of them having an address the other could be configured with.
 *
 * Several services on the LAN at once is the normal case, not a conflict to arbitrate: there is
 * no server role in this protocol, only peers. What a service advertises is only a label; who a
 * peer is comes from the node id in its HELLO, so a rogue advertiser costs one failed handshake and
 * nothing else.
 */
object Mdns {
    internal const val SERVICE_TYPE = "_clipsync._tcp."
    private val SHARED_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "clipsync-mdns")
        t.isDaemon = true
        t
    }

    /**
     * Advertise this device on the LAN, so peers that cannot be configured with an address for it
     * can still find it.
     *
     * Which is every phone and tablet: they have no stable name, and a user cannot type one into
     * the other's peer list. Only one of two devices has to find the other for both to be
     * connected; this is the half that makes the finding possible.
     *
     * Failures are logged and swallowed. A device that cannot advertise can still be reached at a
     * listed address and can still dial out; it is a degradation, not a fault worth stopping for.
     *
     * **No TXT record on this service type**, deliberately, and note that the pairing type is
     * the opposite case, which is the whole of the reasoning. The Windows side advertises
     * `{"v": "5"}` here and nobody browses for it: neither this end nor clipsync.py has any
     * code that reads a peer's TXT on `_clipsync._tcp`. Matching it would look like a version
     * filter without being one, and a real one is not worth having here, because the version gate
     * belongs in the handshake (`Connection.readHello`) where both ends have said who they
     * are. A pre-dial filter would trade one avoided connection to a peer that cannot talk to us,
     * costing a single failed handshake nobody is watching, for the risk of silently skipping a
     * good peer whose advertisement was cached, truncated or merely missing.
     *
     * Pairing does the reverse: [PairProvider] sends `v` and [PairJoiner] (with
     * `clipsync_pair.find`) checks it, because there the wasted connection costs a person
     * copying nine digits and waiting for a key, only to be told the code was wrong when it
     * was not. Same field, opposite answer, because the thing being spent is different.
     *
     * The PC's `v` on this type is left alone: removing it is a change to a file this side
     * does not own, and if it ever goes, nothing here has to change.
     *
     * @return a handle to unregister with, or null if registration could not even be attempted
     */
    fun advertise(ctx: Context, name: String, port: Int): Advert? {
        return advertise(ctx, SERVICE_TYPE, name, port, null)
    }

    /**
     * @param type the service type; pairing advertises under its own ([Pairing.SERVICE_TYPE])
     *             so that ordinary discovery never has to filter it out and the pairing browse never
     *             turns up ordinary nodes
     * @param txt  TXT record entries, or null. Pairing puts its salt here, public by design, since
     *             a salt is not a secret and only has to be unique, and the joiner needs it before
     *             it can derive the key it would connect with.
     */
    fun advertise(ctx: Context, type: String, name: String, port: Int, txt: Map<String, String>?): Advert? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        val si = NsdServiceInfo()
        si.serviceName = name
        si.serviceType = type
        si.port = port
        if (txt != null) for ((k, v) in txt) si.setAttribute(k, v)
        val a = Advert(nsd)
        try {
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, SHARED_EXECUTOR, a)
            return a
        } catch (e: Exception) {
            Logger.w("mdns advertise failed: $e")
            return null
        }
    }

    /**
     * One live registration.
     *
     * Note that the name may come back changed: mDNS resolves a collision by suffixing, so two
     * devices that call themselves the same thing both keep advertising. That is the right outcome
     * and the reason the advertised name is only ever a label; who a peer *is* comes from
     * the node id in its HELLO, never from what it advertises.
     */
    class Advert internal constructor(private val nsd: NsdManager) : NsdManager.RegistrationListener, AutoCloseable {

        override fun onServiceRegistered(si: NsdServiceInfo?) {
            // The name, as registered: mDNS may have suffixed it to resolve a collision.
            Logger.i("advertising as \"" + si?.serviceName + "\" " + SERVICE_TYPE)
        }

        override fun onRegistrationFailed(si: NsdServiceInfo?, err: Int) {
            Logger.w("mdns advertise failed ($err): this device can dial out but will not be discovered")
        }

        override fun onServiceUnregistered(si: NsdServiceInfo?) {}

        override fun onUnregistrationFailed(si: NsdServiceInfo?, err: Int) {}

        override fun close() {
            // Attempted whether or not registration was confirmed: a registration still in flight
            // has no callback yet and would otherwise outlive the service that asked for it. An
            // unregister of something never registered throws, and that is the harmless half.
            try {
                nsd.unregisterService(this)
            } catch (ignored: Exception) {
            }
        }
    }

    /** One address of one advertised service. */
    class Candidate internal constructor(
        val addr: InetSocketAddress,
        val name: String,
    )

    /**
     * One advertised service: a peer, and every address it can be reached at.
     *
     * The unit of discovery is the **instance** and not the address. A machine typically advertises
     * every adapter it has, so a flat list of addresses conflates "several ways to one peer" with
     * "several peers", and those are exactly the two cases that have to be told apart when there
     * can be more than one peer on the LAN. Racing the addresses of one instance is choosing a
     * route; racing instances would be choosing which peer to have, which is not a choice anyone
     * wants made for them.
     */
    class Instance internal constructor(
        val name: String,
        val addrs: List<InetSocketAddress>,
        attrs: Map<String, String>?,
    ) {
        /**
         * The TXT record, never null. Empty for an ordinary Android node, which advertises none.
         *
         * Read in exactly one place: `PairJoiner` takes the pairing salt out of it. A
         * Windows peer also puts a `v` here and nothing on either side reads it; see
         * [advertise] (the three-argument one) for why this end neither sends nor consults one.
         */
        val attrs: Map<String, String> = attrs ?: emptyMap()

        /** When this was last seen advertising, so a peer that goes quiet can be forgotten. */
        val foundAt: Long = System.currentTimeMillis()

        override fun toString(): String = "$name $addrs"
    }

    /**
     * Browse for the whole of `timeoutMs` and return every service found.
     *
     * The whole window is used deliberately, rather than returning as soon as one service
     * resolves: multicast replies from several devices do not arrive together, and returning early
     * would mean returning whichever peer answered first and never learning about the rest. The
     * window is the user's own setting, so its cost is visible and adjustable where the latency of a
     * missed peer would not be.
     *
     * Blocking; call from a background thread only.
     */
    fun discover(ctx: Context, net: Network?, timeoutMs: Long): List<Instance> {
        return discover(ctx, net, SERVICE_TYPE, timeoutMs)
    }

    /** @param type which service to browse for: ordinary nodes, or [Pairing.SERVICE_TYPE] */
    fun discover(ctx: Context, net: Network?, type: String, timeoutMs: Long): List<Instance> {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return ArrayList()
        val s = Session(nsd, type)
        try {
            // pinned to the active (Wi-Fi) network: multicast never leaks to a cellular interface
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, net, s.executor, s.browser)
            s.done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Logger.w("mdns discovery: $e")
        } finally {
            s.close()
        }
        val out = ArrayList<Instance>()
        synchronized(s.found) {
            for ((k, v) in s.found) {
                if (v.isNotEmpty()) {
                    out.add(Instance(k, ArrayList(v), s.txt[k]))
                }
            }
        }
        return out
    }

    /** One browse; every found service gets its own ServiceInfoCallback (they may run in parallel). */
    private class Session(val nsd: NsdManager, type: String) {
        // shared, never shut down: NsdManager may still deliver onDiscoveryStopped /
        // onServiceInfoCallbackUnregistered after close(), and a rejected execute() would throw
        // on the system callback thread
        val executor: ExecutorService = SHARED_EXECUTOR

        /** the type this browse asked for, minus its trailing dot, for matching what comes back */
        val want: String = if (type.endsWith(".")) type.substring(0, type.length - 1) else type

        /** maps an advertised service name to every address it resolved to, insertion-ordered */
        val found: MutableMap<String, MutableList<InetSocketAddress>> = LinkedHashMap()

        /** maps an advertised service name to its TXT record, guarded by [found] like the addresses */
        val txt: MutableMap<String, MutableMap<String, String>> = LinkedHashMap()

        /** Counted down only when the browse cannot start: otherwise the full window is the point. */
        val done = CountDownLatch(1)
        private val callbacks: MutableList<NsdManager.ServiceInfoCallback> = ArrayList()

        /**
         * Set by [close], and checked before registering another resolution callback.
         *
         * NsdManager delivers `onServiceFound` on its own thread and does not stop at
         * `stopServiceDiscovery`, so a discovery arriving after close must not register a
         * resolution callback: nothing would ever unregister it, which would leak inside the system
         * service for the life of the process.
         */
        private var closed = false

        val browser: NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, err: Int) {
                Logger.w("mdns start failed $err")
                done.countDown()
            }

            override fun onStopDiscoveryFailed(t: String?, err: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(si: NsdServiceInfo?) {}

            override fun onServiceFound(si: NsdServiceInfo?) {
                // Belt and braces: discovery is per-type, so nothing else should arrive. Note that
                // the two types do not match each other by accident either: "_clipsync-pair._tcp"
                // does not contain "_clipsync._tcp".
                if (si == null || si.serviceType?.contains(want) != true) return
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(err: Int) {
                        Logger.i("mdns resolve failed " + si.serviceName + " err " + err)
                    }

                    override fun onServiceUpdated(i: NsdServiceInfo) {
                        val addrs = addressesOf(i)
                        if (addrs.isEmpty()) return        // SRV arrived before A/AAAA; wait for the next update
                        synchronized(found) {
                            val have = found.computeIfAbsent(i.serviceName) { ArrayList() }
                            for (a in addrs) if (!have.contains(a)) have.add(a)
                            val t = txt.computeIfAbsent(i.serviceName) { LinkedHashMap() }
                            for ((k, v) in i.attributes) {
                                // A TXT value may be present with no value at all ("key" rather than
                                // "key=value"), which arrives as null rather than as an empty array.
                                t[k] = if (v == null) "" else String(v, StandardCharsets.UTF_8)
                            }
                        }
                        Logger.i("mdns found " + i.serviceName + " " + addrs)
                    }

                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                synchronized(callbacks) {
                    if (closed) return         // the browse is over; nothing would unregister this
                    callbacks.add(cb)
                }
                try {
                    nsd.registerServiceInfoCallback(si, executor, cb)
                } catch (e: Exception) {
                    Logger.i("mdns resolve " + si.serviceName + ": " + e)
                }
            }
        }

        fun close() {
            synchronized(callbacks) {
                closed = true
                for (cb in callbacks) {
                    try {
                        nsd.unregisterServiceInfoCallback(cb)
                    } catch (ignored: Exception) {
                    }
                }
                callbacks.clear()
            }
            try {
                nsd.stopServiceDiscovery(browser)
            } catch (ignored: Exception) {
            }
        }
    }

    /** IPv4 first (most reliable on a LAN), then global IPv6, link-local last. */
    private fun addressesOf(si: NsdServiceInfo): List<InetSocketAddress> {
        val port = si.port
        val v4 = ArrayList<InetSocketAddress>()
        val v6 = ArrayList<InetSocketAddress>()
        val ll = ArrayList<InetSocketAddress>()
        for (a in si.hostAddresses) {
            if (a == null || a.isLoopbackAddress || a.isMulticastAddress || port <= 0) continue
            val sa = InetSocketAddress(a, port)
            if (a is Inet4Address) v4.add(sa)
            else if (a is Inet6Address && a.isLinkLocalAddress) ll.add(sa)
            else v6.add(sa)
        }
        val out = ArrayList<InetSocketAddress>(v4)
        out.addAll(v6)
        out.addAll(ll)
        return out
    }
}
