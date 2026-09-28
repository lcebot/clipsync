package io.github.lcebot.clipsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import org.json.JSONObject

/**
 * One authenticated TCP session with one peer, dialled or accepted. Wire format in clipsync.py.
 */
class Connection : AutoCloseable {

    companion object {
        // ---- frame types, grouped by purpose.
        // No external release has shipped, so the numbering is free to be organised by purpose rather
        // than by the order types were added.

        // Session lifecycle.
        const val T_HELLO = 1

        /**
         * "I am closing this connection, and here is why", followed by `{reason}`.
         *
         * It exists because **a close without one becomes a loop**, and that failure needs no
         * network trouble to trigger. When a duplicate link is dropped, the far side sees nothing but a
         * disconnect, its reconnect logic fires, and it rebuilds exactly the link that was discarded, so
         * that link can then be discarded again. A peer that receives BYE does not schedule a redial.
         */
        const val T_BYE = 2
        const val T_PING = 3
        const val T_PONG = 4

        /**
         * The `BYE` reason a device sends as it goes to sleep, and the one that does not mean
         * "this link was redundant".
         *
         * The two must not be confused, because they ask for opposite things: a duplicate tells the
         * peer to defer to the link that won, while an idle peer has no winning link to defer to and
         * wants to be left alone until it dials out again. It lives here, beside the frame type, because
         * both ends compare against it and neither owns it.
         */
        const val BYE_IDLE = "idle"

        // Key rotation: the PSK is replaced on a schedule, and both ends accept the outgoing key for a
        // while so the changeover is invisible. Keys holds the arithmetic.
        /**
         * Sent after HELLO on a control connection, carrying this device's key schedule: `{psk,
         * since, next}`. Both ends send one if rotation is enabled; the receiver reconciles with
         * [Keys.betterNext] and persists the result.
         */
        const val T_KEYS = 5

        // Clipboard.
        const val T_CLIP = 6

        // File transfer.
        const val T_OFFER = 7
        const val T_WANT = 8
        const val T_HAVE = 9
        const val T_SKIP = 10
        const val T_CHUNK = 11
        const val T_PULL = 12
        const val T_END = 13
        const val T_ABORT = 14

        // Pairing: getting the PSK onto a second device without typing 64 hex characters into it. The
        // channel is an ordinary one keyed by a nine-digit code instead of the PSK; see Pairing.
        /**
         * The joiner sends `PAIR_ASK` to the provider, "give me the key", and the provider sends
         * `PAIR_KEY` back with it.
         *
         * Ordinary frames on an ordinary channel, reached after an ordinary handshake and an ordinary
         * `HELLO`; only the key differs. Nothing here is allowed to be parsed before a key has
         * been proven: a pairing frame sent instead of HELLO, ahead of any authentication, would open a
         * parsing path an unproven caller could reach on an open port. Varying the key and adding a role
         * costs nothing by comparison, and keeps that one rule intact.
         */
        const val T_PAIR_ASK = 15
        const val T_PAIR_KEY = 16

        // Relay coordination: a node that cannot reach a sender directly asks a peer that can to hold
        // the file and offer it on.
        /**
         * The waiter sends `RELAY_ASK` to the relay, "I am waiting for this sha; offer it to me
         * when you have it." The relay sends `RELAY_OK` back to the waiter, "accepted." The relay
         * sends `RELAY_NO` back to the waiter, "declined", with a reason: `busy` (retryable)
         * or `refused` (final: opted out, over the size limit, or battery too low).
         */
        const val T_RELAY_ASK = 17
        const val T_RELAY_OK = 18
        const val T_RELAY_NO = 19

        // Peer roster exchange: a node tells each direct peer about its other direct peers, so every
        // node knows the 2-hop neighbourhood and can build a complete OFFER `to`.
        const val T_PEERS = 20

        /**
         * 2: HELLO is exchanged in both directions and carries the node id, type, persistence and
         * battery bucket (see [Hello]). A clean break rather than a tolerated one, which is this
         * project's standing rule for protocol changes, because the two ends ship together, so a version
         * mismatch is refused with a plain message instead of being worked around. A version 1 peer
         * cannot name itself, and a peer that cannot name itself cannot be deduplicated or recognised
         * as self.
         *
         * 3: HELLO also carries `port` and `data_out`, and every device listens.
         *
         * The bump is not bookkeeping. The two new fields have defaults, so a version-2 peer would
         * connect and work, and then answer a WANT by opening its own data connections at the same
         * moment as this end opens its, because the rule that stops that is the field it does not send.
         * Every file would move twice. A failure a version check turns into one refused connection with
         * a plain message is worth a version number; both ends are updated together regardless.
         *
         * 5: the clipboard hash is taken over **newline-normalised** text (CRLF read as LF), OFFER
         * headers carry `forwarded`, and RELAY_ASK carries `size`. The first of those is why
         * this is a version and not three optional fields: the sha is a value on the wire that both ends
         * compute independently, so a peer using the other rule does not degrade, it disagrees; every
         * clip containing a Windows line ending would be dropped as corrupt, silently from the user's
         * side. The two ends ship together, so a refused handshake with a plain message is the cheapest
         * way for a mismatched pair to say so.
         */
        const val PROTOCOL_VERSION = 5

        /** Chunk size (CHUNK frames carry u32 index ‖ bytes); also the largest frame anyone buffers. */
        const val CHUNK = 512 * 1024

        fun chunks(size: Long): Int {
            return Math.max(1L, (size + CHUNK - 1) / CHUNK).toInt()
        }

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MDNS_CONNECT_TIMEOUT_MS = 3_000   // LAN: fail fast
        private const val READ_TIMEOUT_MS = 90_000

        /**
         * How long an accepted socket may stay silent before it has said who it is.
         *
         * Much shorter than [READ_TIMEOUT_MS] on purpose. An authenticated peer is allowed to
         * be quiet for a heartbeat interval; before that, silence is either a stuck network or something
         * that is not a peer at all, and either way it is holding one of a bounded number of accept
         * workers. Raised to the session timeout the moment the HELLO lands.
         */
        private const val HANDSHAKE_TIMEOUT_MS = 15_000

        // Discovered LAN instances live in the service's own map, refreshed by a scheduled browse, not
        // cached here: a single cached address could not represent several peers at once, and could not
        // notice one of them leaving.

        /**
         * Targets a handshake has proved to be this device.
         *
         * Held per process rather than written to the configuration: it is a *discovery*, not a
         * setting, and a name that resolves here today may resolve elsewhere tomorrow. It is cleared
         * whenever the configuration changes, because the user editing the list is exactly the moment to
         * stop believing what the old list implied.
         */
        private val selfTargets: MutableSet<String> = ConcurrentHashMap.newKeySet()

        internal fun isKnownSelf(target: String): Boolean {
            return selfTargets.contains(Config.normalisePeer(target))
        }

        internal fun rememberSelf(target: String?) {
            if (target != null) selfTargets.add(Config.normalisePeer(target))
        }

        /** Forget everything learned at run time about targets (the configuration changed). */
        fun forgetLearned() {
            selfTargets.clear()
        }

        /**
         * One listed address. Resolves and connects to that name and nothing else.
         *
         * Deliberately not a race across every target: the losers such a race closes are the other
         * peers. [connectDirect] still tries every address *that one name* resolves to,
         * which is the only sense in which alternatives exist here.
         */
        fun toPeer(ctx: Context, cfg: Config, peer: String, net: Network?): Connection {
            return Connection(cfg.psk, cfg.maxFrame(), cfg.port,
                arrayOf<Any?>(connectDirect(peer, cfg.port, net), "direct", peer), ctx, net, false)
        }

        /**
         * A connection a peer opened to us.
         *
         * The device listens, which is what makes phone-to-tablet possible at all: neither of
         * them has a stable address the other can be configured with, and only one of the two needs to
         * find the other for both to be connected.
         *
         * The name is the address until the peer says otherwise: on this side the HELLO arrives
         * before we answer, so there is no window in which the peer is anonymous for long.
         *
         * Multi-key: every key in [Keys.Schedule.accepted] is tried against the first
         * frame, so a peer using the current key, the successor, or any key still in the ring is let
         * in. Which key matched is exposed via [matchedSecret] once the first frame lands.
         */
        fun accept(ctx: Context, cfg: Config, s: Socket, net: Network?): Connection {
            // One unusable entry skipped, not the whole ring abandoned: Crypto.fromHex answers null for
            // a malformed key rather than throwing, which is what makes skipping just that entry the
            // natural thing to do here. The validation in Config should keep a bad value out in the first
            // place; this is what stops one that got in anyway from taking the listening socket down
            // with it; a single bad ring entry must not make every inbound connection fail.
            val usable = ArrayList<ByteArray>()
            for (h in cfg.keys.accepted()) {
                val k = Crypto.fromHex(h)
                if (k == null) Logger.w("keys: skipping a malformed key in the ring")
                else usable.add(k)
            }
            if (usable.isEmpty()) throw IOException("no usable key to authenticate with")
            val secrets: Array<ByteArray> = usable.toTypedArray()
            val ra: SocketAddress? = s.remoteSocketAddress
            return Connection(secrets, cfg.maxFrame(), cfg.port,
                arrayOf<Any?>(s, "inbound", ra.toString()
                    .replaceFirst(Regex("^[^/]*/"), "")), ctx, net, true)
        }

        /**
         * One peer found on the local network, reached at whichever of its addresses answers first.
         *
         * It takes an instance rather than browsing for itself, and that is the difference between
         * one peer and several. Browsing inside the connect would mean "find the LAN peer and connect to
         * it", which cannot express two of them; the browse belongs to the service, which keeps one
         * dialler per instance it has found, and this is only the connect.
         *
         * Still a race, and still the right one: a machine typically advertises every adapter it has
         * (VMware/Hyper-V/WSL/hotspot subnets included), so these are several routes to *one*
         * peer. Addresses on our own subnet go first, then Happy-Eyeballs, rather than eating a 3 s
         * timeout per dead address.
         *
         * @param net the active network, used to rank addresses by whether they are on-link
         */
        fun toInstance(ctx: Context, cfg: Config, inst: Mdns.Instance, net: Network?): Connection {
            val found = ArrayList<Mdns.Candidate>()
            for (a in inst.addrs) found.add(Mdns.Candidate(a, inst.name))
            if (found.isEmpty()) throw IOException("mdns: " + inst.name + " advertised no usable address")
            val cands = OnLink.sort(ctx, net, found)
            val w = race(cands, MDNS_CONNECT_TIMEOUT_MS, net)
            return Connection(cfg.psk, cfg.maxFrame(), cfg.port,
                arrayOf<Any?>(w.s, "mdns", inst.name), ctx, net, false)
        }

        /**
         * A data connection to a known address (opened by the transfer workers, several in parallel).
         *
         * Opened only by the end that [drivesTransfer] names, and aimed at the peer's
         * declared listening port rather than at the control socket's remote port.
         */
        fun data(cfg: Config, control: Connection, sha256: String, device: String): Connection {
            // The peer's LISTENING port, not the port of the control socket. On a connection we opened
            // they are the same number; on one we accepted, the control socket's is the ephemeral source
            // port of the peer's dial and connecting to it reaches nothing at all.
            val to = InetSocketAddress(control.remote.address, control.peerPort)
            val c = Connection(cfg.psk, cfg.maxFrame(), cfg.port,
                arrayOf<Any?>(
                    connectTo(to, if (control.lanPeer) MDNS_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS,
                        control.network),
                    control.via, "data"), null, control.network, false)
            // Copied from the control connection rather than left to the constructor's guess. A data
            // connection is built with ctx == null, so the constructor can only fall back to "did the
            // link come up over mDNS", which says true for an mdns link and false for a `direct` or
            // `inbound` one even when the peer is a hop away on the same LAN. Nothing reads it on a data
            // connection today, and a wrong answer waiting for its first reader is worse than no answer:
            // the control connection settled this during its handshake, so take its verdict.
            c.lanPeer = control.lanPeer
            // The id is here so an accepting peer can tell whose transfer this is; the sha names which
            // file. Everything a peer link declares is deliberately absent: see Hello.data.
            c.sendJson(T_HELLO, Hello.data(Node.id(), device, sha256).toJson())
            return c
        }

        /**
         * The two ends of a pairing channel: the same machinery with a code-derived key.
         *
         * Neither takes a [Config], and that is not tidiness; a device being paired has no
         * usable configuration yet, which is the whole reason it is being paired. The three things a
         * channel actually needs are a secret, a frame cap and, for HELLO, a port; a Config is merely
         * where an ordinary link finds them.
         *
         * `maxFrame` is small on purpose: a pairing channel carries two short JSON frames and
         * nothing else, so the cap is a bound on what an unauthenticated caller can make this end
         * allocate before the handshake proves anything.
         */
        fun pairTo(addr: InetSocketAddress, key: ByteArray, net: Network?): Connection {
            // Bound to the network the browse ran on, like every other connect here: the address came
            // from a LAN advertisement, and a phone whose default route is cellular would otherwise send
            // a private address out of the modem and wait for the timeout.
            return Connection(key, PAIR_MAX_FRAME, 0,
                arrayOf<Any?>(connectTo(addr, MDNS_CONNECT_TIMEOUT_MS, net), "pair", addr.toString()),
                null, net, false)
        }

        fun pairAccept(s: Socket, key: ByteArray): Connection {
            val ra: SocketAddress? = s.remoteSocketAddress
            return Connection(key, PAIR_MAX_FRAME, 0,
                arrayOf<Any?>(s, "pair", ra.toString().replaceFirst(Regex("^[^/]*/"), "")),
                null, null, true)
        }

        /** Two short JSON frames is all a pairing channel ever carries. */
        private const val PAIR_MAX_FRAME = 4096

        /**
         * The most an **unauthenticated** caller may make this end allocate.
         *
         * The same rule the pairing channel has, applied to every connection. Until the
         * first frame decrypts, nothing about the peer is known; the length prefix is plaintext and
         * anybody who can reach the port can write one, so the ordinary frame cap (a chunk, over half a
         * megabyte) times the inbound worker limit is what an attacker gets to allocate for the cost of
         * a TCP handshake. A real HELLO is a dozen short fields; 8 KiB leaves room for a long device
         * name and still ends that.
         */
        private const val PRE_AUTH_MAX_FRAME = 8192

        /**
         * Resolve fresh every time, because the address behind a name can move, which is the whole point
         * of a dynamic one; prefer IPv6, try each address. A literal is returned by getAllByName without a
         * lookup, so an address entered directly costs nothing extra here.
         */
        private fun connectDirect(host: String, port: Int, net: Network?): Socket {
            // Resolved on the network too, when there is one: the default resolver can answer from a
            // different interface's DNS than the one the socket will use, which on a phone with Wi-Fi
            // and cellular both up is how a LAN name resolves to nothing.
            val all: Array<InetAddress> = if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)
            val ordered = ArrayList<InetAddress>()
            for (a in all) if (a is Inet6Address) ordered.add(a)
            for (a in all) if (a !is Inet6Address) ordered.add(a)
            var last: IOException? = null
            for (a in ordered) {
                try {
                    return connectTo(InetSocketAddress(a, port), CONNECT_TIMEOUT_MS, net)
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("no address for $host")
        }

        private class Won(val s: Socket, val c: Mdns.Candidate)

        private val RACE_POOL: ExecutorService = Executors.newCachedThreadPool { r ->
            val t = Thread(r, "clipsync-race")
            t.isDaemon = true
            t
        }
        private const val RACE_STAGGER_MS = 250

        /**
         * Happy-Eyeballs style: start a connect to each candidate in order, [RACE_STAGGER_MS]
         * apart, return the first that succeeds and close the rest. Total bound is roughly the stagger
         * times n, plus the timeout.
         */
        private fun race(cands: List<Mdns.Candidate>, timeoutMs: Int, net: Network?): Won {
            val cs: CompletionService<Won> = ExecutorCompletionService(RACE_POOL)
            val futures = ArrayList<Future<Won>>()
            val errors = ArrayList<String>()
            val finished = AtomicBoolean(false)
            var won: Won? = null
            try {
                for (i in 0 until cands.size) {
                    val c = cands[i]
                    val startAt = System.currentTimeMillis() + i.toLong() * RACE_STAGGER_MS
                    futures.add(cs.submit(Callable<Won> {
                        val d = startAt - System.currentTimeMillis()
                        if (d > 0) Thread.sleep(d)
                        if (finished.get()) throw IOException("race already won")
                        val s = connectTo(c.addr, timeoutMs, net)
                        if (finished.get()) {           // late winner: don't leave a half-open client on the server
                            s.close()
                            throw IOException("race already won")
                        }
                        Won(s, c)
                    }))
                }
                // One deadline for the whole race, not one per candidate: polling with a fresh full
                // timeout on every iteration would let the total wait grow with the number of
                // candidates instead of staying near the bound above (stagger times n, plus the
                // timeout): eight adapters on one PC would be forty-eight seconds with the dialler
                // thread blocked throughout, for a peer that is simply not there.
                val until = System.currentTimeMillis() +
                    RACE_STAGGER_MS.toLong() * cands.size + timeoutMs + 1000
                var done = 0
                while (done < cands.size && won == null) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: break
                    try {
                        won = f.get()
                    } catch (e: ExecutionException) {
                        val cause = e.cause
                        errors.add((if (cause != null) cause.message else e).toString())
                    }
                    done++
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                finished.set(true)
                for (f in futures) {
                    if (won != null && f.isDone && !f.isCancelled) {
                        try {
                            val other = f.get()
                            if (other !== won) other.s.close()
                        } catch (ignored: Exception) {
                        }
                    } else {
                        f.cancel(true)
                    }
                }
            }
            if (won == null) {
                throw IOException(if (errors.isEmpty()) "no candidate answered" else errors[errors.size - 1])
            }
            return won
        }

        /**
         * @param net the network to pin this socket to, or null to use the default route. Binding must
         *            happen before the connect, which is why it is here and not in the constructor.
         */
        private fun connectTo(addr: InetSocketAddress, timeoutMs: Int, net: Network?): Socket {
            val s = Socket()
            try {
                if (net != null) net.bindSocket(s)
                s.connect(addr, timeoutMs)
                return s
            } catch (e: IOException) {
                try { s.close() } catch (ignored: IOException) {}
                throw e
            }
        }
    }

    private val socket: Socket
    private val `in`: DataInputStream
    private val out: OutputStream

    /**
     * The two halves of the channel, one per direction.
     *
     * Stateful on purpose. They own the nonce counters themselves, which turns "a nonce is never
     * reused under one key" from a convention this class would otherwise have to keep into something
     * it cannot break: there is no way from outside to set a counter, and no way to seal a frame
     * without advancing one. `tx` is touched only under [sendLock]; `rx` only on
     * the single thread that reads this connection.
     */
    private var tx: Crypto.Sealer? = null
    private var rx: Crypto.Opener? = null
    private val maxFrame: Int

    /** This device's own listening port, declared in HELLO so an accepted peer can reach us back. */
    private val listenPort: Int

    /**
     * Candidate channels for an inbound connection that has not yet identified its peer's key.
     *
     * Null once the key is resolved: either immediately for outbound connections (which always
     * use the current PSK) or on the first [recv] for inbound ones. The candidate list is
     * [Keys.Schedule.accepted], so every key the device considers valid is tried, in the
     * order worth trying: current first, then successor, then the ring.
     *
     * One [Crypto.Opener] per candidate rather than one shared counter, which is what makes
     * trial decryption safe to write: an Opener that fails has not advanced, so every loser is still
     * at frame 0 and the winner is at frame 1, which is exactly the state the connection needs to keep.
     */
    private var candidateSealers: Array<Crypto.Sealer?>? = null
    private var candidateOpeners: Array<Crypto.Opener?>? = null
    private var candidateSecrets: Array<ByteArray>? = null
    private var keyResolved = false

    /**
     * Whether the peer has proved it holds a key we accept, by sending one frame that decrypts.
     *
     * Not the same question as [keyResolved], which is only "do we know *which* of
     * several keys to use" and is true from the start whenever there is exactly one candidate, the
     * ordinary case, which is precisely the case the limits below exist for. True from the start for
     * a connection we opened: we chose the address, and a data connection's first inbound frame is a
     * 512 KiB chunk, which no pre-auth cap may refuse.
     */
    private var peerAuthenticated: Boolean

    /**
     * When an unauthenticated connection has run out of time, on the monotonic clock.
     *
     * Absolute, where [HANDSHAKE_TIMEOUT_MS] as a socket timeout is per read. The two
     * differ for exactly the caller worth stopping: one byte every fourteen seconds never trips a
     * per-read timeout, so it can hold an inbound worker for as long as it likes at no cost to
     * itself. A deadline set at construction is the only thing that bounds it.
     */
    private val handshakeDeadline: Long

    /**
     * The secret that authenticated this channel, or null for outbound connections where it is
     * always the current PSK.
     *
     * Exposed so that `SyncService` can tell whether the peer is using the current key,
     * the successor, or an old one, and act accordingly: a peer on the successor has promoted
     * ahead of us, and one on an old key is behind.
     */
    var matchedSecret: ByteArray? = null
    private val sendLock = Any()

    /** "direct", "mdns" or "inbound": which path this session came up on (for logs and the UI). */
    val via: String

    /**
     * True when the peer opened this connection, not us.
     *
     * It decides **which half of the nonce exchange** to perform, and which key label is ours:
     * the labels are named for who opened the socket, so an accepted connection is the "s" side of
     * the peer's link. Getting that backwards does not fail at the handshake, which exchanges
     * plaintext nonces; it fails at the first frame, as a decrypt error.
     *
     * It is also half of [drivesTransfer], the other half being what the peer declares
     * it can do, and it is why [peerPort] exists: an accepted socket's remote port is the
     * peer's ephemeral source port, so the listening port has to be declared rather than observed.
     */
    val inbound: Boolean

    /** Human-readable peer: listed address or mDNS service name, plus the address actually used. */
    val peer: String

    /** Just the name part: the listed address, or the advertised mDNS service name. */
    val peerName: String

    /** What the peer calls itself, once it has said so in HELLO; the address until then. */
    var peerLabel: String

    /**
     * True when the peer is on our LAN: reached via mDNS, or its address is on one of the prefixes
     * of the network we are using. Decides which file-size limit applies (sent in HELLO too).
     *
     * Mutable, because an accepted connection learns it from the peer's HELLO and the
     * HELLO arrives after the constructor. Taking the dialler's word rather than re-deciding is
     * deliberate and is what the PC does: both ends must hold the *same* value,
     * because link dedup prefers the LAN link over the internet one and two ends that disagree
     * about which link is which can pick opposite winners and close both.
     *
     * On a data connection it is not decided here at all: [data] copies the control
     * connection's value over the constructor's guess, because a data connection has no Context to
     * answer [OnLink] with and `via` alone would call a LAN peer remote on every link
     * that did not come up over mDNS.
     */
    @Volatile
    var lanPeer: Boolean

    /** Where this session connected; data connections for file transfer go to the same place. */
    val remote: InetSocketAddress

    /**
     * The network this session rides on, or null if it could not be established.
     *
     * Two things need it. A socket **bound** to a network fails immediately when that network
     * goes away, instead of hanging until the 90-second read timeout; switching between Wi-Fi and
     * cellular does not close a TCP socket, it leaves it half-open, and that delay is the whole of
     * what "the drop is noticed at once" asks for. And with several peers, "which connections died"
     * is a question that cannot be answered at all while sockets ride the default network
     * anonymously.
     */
    val network: Network?

    // ---- what the peer declared in its HELLO (protocol 2 onwards; see Hello). Set by readHello().
    /** The peer's node id. The key for link dedup, OFFER recipients and priority tie-breaks. */
    var peerId: String? = null

    /** `pc` | `tablet` | `phone`. */
    var peerType: String = "?"

    /** Whether the peer can hold a connection while idle, a capability it declares, not a guess. */
    var peerPersistent = false

    /** `mains` | `high` | `medium` | `low`. */
    var peerBattery: String = "medium"

    /**
     * Where the peer listens, from its HELLO; the port of [remote] until it says.
     *
     * Needed only on an accepted connection, and needed badly there: data connections go to the
     * peer's *listening* port, and the one visible on an accepted socket is the ephemeral
     * source port of its dial, which reaches nothing. On a connection we opened the two are the same
     * number, so this changes nothing in that direction.
     */
    var peerPort: Int

    /**
     * Whether the peer can open data connections of its own.
     *
     * A file's bytes move over separate connections, and **exactly one** of the two nodes must
     * open them: both opening transfers the file twice, neither opening transfers it not at all.
     * Which end that is is not a structural constant, since every node can in principle dial and
     * accept, so it is a negotiation rather than a fixed role: both ends declare `true` here, and the
     * rule that actually decides who opens the connection is "whoever dialled drives", computed by
     * [drivesTransfer]. The field stays because the negotiation is real: an end that cannot
     * dial out says so, and the other one takes the job.
     */
    var peerDataOut = false

    /**
     * Our half of a pairing declaration: who is asking, and nothing that only a configured node has.
     *
     * Deliberately not [sendHello]: that one carries a node id, a sequence cursor and the
     * capability flags of a peer, none of which a device without a key has any business claiming,
     * and the id is what the self-check and the dedup map key on, so an unpaired device offering one
     * would be enrolling itself into machinery it is not part of yet.
     */
    fun sendPairHello(ctx: Context) {
        sendJson(T_HELLO, Hello.pair(Node.name(), Node.type(ctx)).toJson())
    }

    /**
     * The control handshake: declare ourselves, then read what the peer declares back.
     *
     * Both directions matter: each end has to learn who the other is in order to tell two peers
     * apart, to notice that two addresses lead to one machine, or to notice that one of them leads
     * back here.
     *
     * Which is the last thing this does: **if the peer's id is ours, the connection is dropped.**
     * Both ends hold the same PSK, so the handshake succeeds and the node would otherwise enrol
     * itself as a peer, broadcasting to itself and comparing versions against its own clips. The
     * user's declared own-addresses list catches the common spellings before a socket is ever
     * opened; this catches everything else, and is the authority.
     *
     * @return the peer's HELLO: the dialler's half of catch-up reads [Hello.clipTs] and
     *         [Hello.clipSha] from it to decide whether the peer is behind. Both ends send
     *         these fields, so both must consume them, or two devices can reconnect and never
     *         converge.
     * @throws SelfConnection when the peer turns out to be this device
     */
    fun hello(ctx: Context, clipTs: Long, clipSha: String?): Hello {
        sendHello(ctx, clipTs, clipSha)
        return readHello()
    }

    /**
     * Our half of the declaration.
     *
     * Separate from [readHello] because the two ends do them in opposite orders, and
     * the order is not a detail: the dialler declares first because it has nothing to wait for,
     * and the accepter answers, which means an accepted connection knows who the peer is
     * *before* it has to say anything, and can therefore send a sequence cursor that is
     * actually about that peer rather than a zero.
     */
    fun sendHello(ctx: Context, clipTs: Long, clipSha: String?) {
        // Values, not a Context: Hello is the semantic layer and knows nothing about Android. The
        // two fields nothing else could supply are listenPort (an accepted connection cannot see
        // our listening port any other way) and lanPeer, which is this end's verdict on whether the
        // two are on one LAN and which the accepter takes as final.
        val mine = Hello.control(Node.id(), Node.name(), Node.type(ctx),
            Node.persistent(ctx), Node.battery(ctx), clipTs, clipSha, lanPeer, listenPort)
        // Once per process, and here rather than at start-up because this is the only place a real
        // outgoing declaration exists: the thing being checked is what control() built, not a
        // reconstruction of it. See Hello.auditSent.
        mine.auditSent()
        sendJson(T_HELLO, mine.toJson())
    }

    /**
     * Read what the peer declares, and refuse it here if it cannot be talked to.
     *
     * Parsing is [Hello.parse]'s job; what is left here is everything that is about
     * *this connection* rather than about the message: the version gate, the fields that
     * become connection state, the self-check, and raising the read timeout now that the peer has
     * proved it is one.
     *
     * @return the peer's declaration, for the fields only the caller cares about
     *         ([Hello.clipTs], [Hello.clipSha], and on an accepted connection
     *         [Hello.role] and [Hello.sha256])
     * @throws SelfConnection when the peer turns out to be this device
     */
    fun readHello(): Hello {
        val f = recv()
        if (f.type != T_HELLO) throw IOException("expected HELLO, got frame type " + f.type)
        val theirs = Hello.parse(JSONObject(String(f.payload, StandardCharsets.UTF_8)))
        if (theirs.v != PROTOCOL_VERSION)
            throw IOException("protocol version mismatch (peer speaks " + theirs.v +
                ", we speak " + PROTOCOL_VERSION + ")")
        peerId = theirs.id
        peerType = theirs.type
        peerPersistent = theirs.persistent
        peerBattery = theirs.battery
        // Only if it said: a peer that omits the field leaves us with the remote port we already
        // have, which is the right number on a connection we opened.
        if (theirs.has(Hello.K_PORT)) peerPort = theirs.port
        peerDataOut = theirs.dataOut
        if (!theirs.device.isEmpty()) peerLabel = theirs.device
        socket.setSoTimeout(READ_TIMEOUT_MS)      // it has spoken; the short leash was for silence
        // A stream or a pairing exchange, not a peer: neither enrols anything, so neither needs the
        // node checks below. Both still filled peerLabel and peerType above, and on the pairing path
        // those two are the only description of the caller the provider has to put in front of the
        // user before it hands the key over; the joiner has no node id to show.
        if (Hello.ROLE_DATA == theirs.role || Hello.ROLE_PAIR == theirs.role) return theirs
        theirs.audit(peerLabel)
        // Protocol 2's premise is that a peer can name itself, and everything downstream assumes it:
        // a link with no id cannot be deduplicated, cannot be recognised as this device, and would
        // sit outside the map that the heartbeat, the broadcast and the status all iterate, running
        // but reaching nobody. Refusing here is much easier to diagnose than that.
        val id = peerId
        if (id == null || id.isEmpty()) throw IOException("peer sent no node id")
        if (id == Node.id()) throw SelfConnection(peerName)
        // On an accepted connection the dialler's verdict is the one that counts (see lanPeer). The
        // `has` test is what distinguishes "the peer says we are not on one LAN" from "the peer said
        // nothing", which are different answers and must not collapse into false.
        if (inbound && theirs.has(Hello.K_LAN)) lanPeer = theirs.lan
        return theirs
    }

    /**
     * The peer reached by this connection turned out to be this device.
     *
     * Carries no address of its own: the dialler marks the target it was *dialling*, which
     * is the string its own map is keyed by, and not the name this connection happened to reach.
     * The two differ for a discovery target, and the one that stops the redial is the dialler's.
     */
    class SelfConnection internal constructor(target: String) : IOException("that is this device: $target")

    /**
     * Single-key constructor: outbound connections, pairing, and data channels.
     *
     * @param secret the shared key this channel's per-direction keys are derived from. The PSK for
     *               an ordinary link and a code-derived key for a pairing one, which is the whole
     *               of what makes pairing possible without new machinery: confidentiality,
     *               authentication and replay resistance all come along unchanged, and a caller
     *               without the code fails at the handshake exactly as a wrong PSK does.
     */
    private constructor(
        secret: ByteArray, maxFrame: Int, listenPort: Int,
        r: Array<Any?>, ctx: Context?, net: Network?, inbound: Boolean,
    ) : this(arrayOf(secret), maxFrame, listenPort, r, ctx, net, inbound)

    /**
     * Multi-key constructor: inbound connections try every candidate.
     *
     * The nonce exchange is key-independent (plaintext), so it runs once. If there is a single
     * key the channel is ready immediately; with several, key derivation is done for each and the
     * actual selection is deferred to the first [recv], which trial-decrypts until one
     * succeeds.
     */
    private constructor(
        secrets: Array<ByteArray>, maxFrame: Int, listenPort: Int,
        r: Array<Any?>, ctx: Context?, net: Network?, inbound: Boolean,
    ) {
        this.maxFrame = maxFrame
        this.listenPort = listenPort
        this.inbound = inbound
        this.network = net
        this.handshakeDeadline = android.os.SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
        this.peerAuthenticated = !inbound
        socket = r[0] as Socket
        via = r[1] as String
        remote = socket.remoteSocketAddress as InetSocketAddress
        // The right answer for a connection we opened, and a placeholder for one we accepted until
        // its HELLO corrects it.
        peerPort = remote.port
        peerName = r[2].toString()
        peerLabel = peerName
        peer = "$peerName [$remote]"
        lanPeer = "mdns" == via || (ctx != null && OnLink.isOnLink(ctx, net, socket.inetAddress))
        socket.setSoTimeout(if (inbound) HANDSHAKE_TIMEOUT_MS else READ_TIMEOUT_MS)
        socket.setTcpNoDelay(true)
        // A hint to the kernel and nothing more: Android's default idle time before the first probe
        // is two hours, which is far past every timeout here, and it is not settable from this API.
        // The heartbeat is what actually notices a dead peer; this only helps on the ROMs that
        // shorten the default themselves.
        socket.setKeepAlive(true)
        `in` = DataInputStream(socket.getInputStream())
        out = socket.getOutputStream()

        // handshake: this side sends Nc, then receives Ns; keys = HKDF(psk, Nc||Ns, info).
        //
        // The labels are named for who OPENED the connection, not for who is a server; a device
        // both dials and accepts, so an accepted connection is the "s" side of the peer's link
        // and must read the client nonce first. Getting this backwards does not fail at the
        // handshake, which exchanges plaintext nonces; it fails at the first frame, as a decrypt
        // error, which is a much more expensive way to find out.
        val mine = ByteArray(32)
        val theirs = ByteArray(32)
        Crypto.RNG.nextBytes(mine)
        if (inbound) {
            `in`.readFully(theirs)
            out.write(mine)
            out.flush()
        } else {
            out.write(mine)
            out.flush()            // before the read, always: the peer is waiting for these bytes
            `in`.readFully(theirs)
        }
        val nc = if (inbound) theirs else mine
        val ns = if (inbound) mine else theirs
        val salt = ByteBuffer.allocate(64).put(nc).put(ns).array()

        if (secrets.size == 1) {
            val c2s = Crypto.hkdfSha256(secrets[0], salt, "clipsync c2s".toByteArray(StandardCharsets.US_ASCII), 32)
            val s2c = Crypto.hkdfSha256(secrets[0], salt, "clipsync s2c".toByteArray(StandardCharsets.US_ASCII), 32)
            tx = Crypto.Sealer(if (inbound) s2c else c2s)
            rx = Crypto.Opener(if (inbound) c2s else s2c)
            matchedSecret = secrets[0]
            keyResolved = true
        } else {
            // Derive a channel for every candidate; the first recv() picks the winner.
            candidateSecrets = secrets
            val sealers = arrayOfNulls<Crypto.Sealer>(secrets.size)
            val openers = arrayOfNulls<Crypto.Opener>(secrets.size)
            candidateSealers = sealers
            candidateOpeners = openers
            for (i in 0 until secrets.size) {
                val c2s = Crypto.hkdfSha256(secrets[i], salt, "clipsync c2s".toByteArray(StandardCharsets.US_ASCII), 32)
                val s2c = Crypto.hkdfSha256(secrets[i], salt, "clipsync s2c".toByteArray(StandardCharsets.US_ASCII), 32)
                sealers[i] = Crypto.Sealer(if (inbound) s2c else c2s)
                openers[i] = Crypto.Opener(if (inbound) c2s else s2c)
            }
            keyResolved = false
        }
    }

    /** Orders candidates so that addresses on one of the phone's own prefixes come first. */
    private object OnLink {
        fun mine(ctx: Context, net: Network?): List<LinkAddress> {
            val mine = ArrayList<LinkAddress>()
            val cm: ConnectivityManager? = ctx.getSystemService(ConnectivityManager::class.java)
            val lp: LinkProperties? = if (cm != null && net != null) cm.getLinkProperties(net) else null
            if (lp != null) mine.addAll(lp.linkAddresses)
            return mine
        }

        fun isOnLink(ctx: Context, net: Network?, a: InetAddress?): Boolean {
            return a != null && onLink(a, mine(ctx, net))
        }

        fun sort(ctx: Context, net: Network?, `in`: List<Mdns.Candidate>): List<Mdns.Candidate> {
            val mine = mine(ctx, net)
            val first = ArrayList<Mdns.Candidate>()
            val rest = ArrayList<Mdns.Candidate>()
            for (c in `in`) (if (onLink(c.addr.address, mine)) first else rest).add(c)
            first.addAll(rest)
            return first
        }

        fun onLink(a: InetAddress, mine: List<LinkAddress>): Boolean {
            val x = a.address
            for (la in mine) {
                val y = la.address.address
                if (y.size != x.size) continue
                val bits = la.prefixLength
                if (a is Inet6Address && a.isLinkLocalAddress) return true   // fe80:: is by definition on-link
                var same = true
                var i = 0
                while (i < bits && same) {
                    val mask = 0x80 shr (i and 7)
                    same = ((x[i shr 3].toInt() xor y[i shr 3].toInt()) and mask) == 0
                    i++
                }
                if (same) return true
            }
            return false
        }
    }

    fun send(type: Int, payload: ByteArray) {
        val pt = ByteArray(1 + payload.size)
        pt[0] = type.toByte()
        System.arraycopy(payload, 0, pt, 1, payload.size)
        synchronized(sendLock) {
            val ct = (tx ?: throw NullPointerException("tx")).seal(pt)
            val frame = ByteBuffer.allocate(4 + ct.size).putInt(ct.size).put(ct).array()
            out.write(frame)
            out.flush()
        }
    }

    /**
     * Does this end open the data connections for files on this link?
     *
     * Exactly one of the two must, and this is the rule that decides it:
     *
     * - if the peer **cannot** open them, we do, whoever dialled, since the PC is in exactly this
     *   position, having gained a client role for control connections and none for data ones;
     * - otherwise **the node that dialled** does. It has proved it can reach the other's
     *   listening port, which is precisely what a data connection needs, and the far end reaches
     *   the same verdict from the same two facts.
     *
     * Getting this wrong in either direction is a visible failure rather than an inefficiency:
     * both ends opening moves every file twice, and neither opening leaves a WANT unanswered
     * forever.
     */
    fun drivesTransfer(): Boolean {
        return !peerDataOut || !inbound
    }

    fun setSoTimeout(ms: Int) {
        socket.setSoTimeout(ms)
    }

    fun send(type: Int) {
        send(type, ByteArray(0))
    }

    fun sendJson(type: Int, o: JSONObject) {
        send(type, o.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * CHUNK frame: u32 index ‖ bytes.
     *
     * Through two buffers held for the life of the connection, rather than three allocations per
     * frame. A data connection sends nothing but chunks, so the buffers are as long-lived as it is
     * and are allocated on first use, since an ordinary control connection never sends a chunk and never
     * pays for them. Both are written only under [sendLock], which is what makes holding them
     * on the connection safe.
     *
     * `chunkFrame` is sized exactly, not generously, and the margin is zero: Conscrypt
     * refuses a write-through `doFinal` whose destination has fewer than
     * `plaintextLen + 16` bytes left, and the largest plaintext this sends is
     * `1 + 4 + CHUNK`. So `4 + 1 + 4 + CHUNK + 16` is the smallest array that works and
     * one byte less is a ShortBufferException on every full chunk. See `Crypto.Sealer.sealInto`
     * for why 16 is a constant here and not something to ask the provider for.
     *
     * Two separate arrays rather than one, also deliberately: Conscrypt tolerates sealing within
     * a single array but does it by copying the input range first, which is the copy this whole
     * arrangement exists to avoid.
     */
    private var chunkPlain: ByteArray? = null
    private var chunkFrame: ByteArray? = null

    fun sendChunk(index: Int, data: ByteArray, len: Int) {
        synchronized(sendLock) {
            if (chunkPlain == null) {
                chunkPlain = ByteArray(1 + 4 + CHUNK)
                chunkFrame = ByteArray(4 + 1 + 4 + CHUNK + 16)   // length prefix ‖ ciphertext ‖ tag
            }
            val plain = chunkPlain ?: throw NullPointerException("chunkPlain")
            val frame = chunkFrame ?: throw NullPointerException("chunkFrame")
            plain[0] = T_CHUNK.toByte()
            plain[1] = (index ushr 24).toByte(); plain[2] = (index ushr 16).toByte()
            plain[3] = (index ushr 8).toByte(); plain[4] = index.toByte()
            System.arraycopy(data, 0, plain, 5, len)
            val n = (tx ?: throw NullPointerException("tx")).sealInto(plain, 0, 5 + len, frame, 4)
            frame[0] = (n ushr 24).toByte(); frame[1] = (n ushr 16).toByte()
            frame[2] = (n ushr 8).toByte(); frame[3] = n.toByte()
            out.write(frame, 0, 4 + n)
            out.flush()
        }
    }

    /** Blocks until a frame arrives. Returns {type, payload}. */
    fun recv(): Frame {
        if (!peerAuthenticated) {
            // Three checks and not one, because a read timeout is per read: the socket timeout is
            // trimmed to what is left of the deadline so a single stalled read cannot outlive it,
            // and the deadline is tested again after the body because a caller that dribbles bytes
            // restarts that timeout with every one of them.
            val left = handshakeDeadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) throw IOException("handshake did not finish in " +
                HANDSHAKE_TIMEOUT_MS / 1000 + "s")
            socket.setSoTimeout(left.toInt())
        }
        val len = `in`.readInt()
        // min, not the constant: a pairing channel's own cap is smaller still, and this must only
        // ever tighten a limit, never raise one.
        val cap = if (peerAuthenticated) maxFrame else Math.min(maxFrame, PRE_AUTH_MAX_FRAME)
        if (len < 17 || len > cap) throw IOException("bad frame length $len")
        val ct = ByteArray(len)
        `in`.readFully(ct)
        if (!peerAuthenticated && android.os.SystemClock.elapsedRealtime() > handshakeDeadline)
            throw IOException("handshake did not finish in " + HANDSHAKE_TIMEOUT_MS / 1000 + "s")

        if (!keyResolved) {
            // Trial-decrypt with each candidate. The first that succeeds is the peer's key.
            val openers = candidateOpeners ?: throw NullPointerException("candidateOpeners")
            for (i in 0 until openers.size) {
                try {
                    val pt = (openers[i] ?: throw NullPointerException("candidateOpeners[$i]")).open(ct)
                    // Commit to this channel. The winning Opener has already advanced past this
                    // frame and the losers have not advanced at all, so nothing needs correcting.
                    rx = openers[i]
                    tx = (candidateSealers ?: throw NullPointerException("candidateSealers"))[i]
                    matchedSecret = (candidateSecrets ?: throw NullPointerException("candidateSecrets"))[i]
                    keyResolved = true
                    peerAuthenticated = true
                    candidateOpeners = null
                    candidateSealers = null
                    candidateSecrets = null
                    val payload = ByteArray(pt.size - 1)
                    System.arraycopy(pt, 1, payload, 0, payload.size)
                    return Frame(pt[0].toInt() and 0xff, payload)
                } catch (ignored: GeneralSecurityException) {
                    // Wrong key: try the next candidate.
                }
            }
            throw IOException("no accepted key could authenticate this peer")
        }

        val pt = (rx ?: throw NullPointerException("rx")).open(ct)
        peerAuthenticated = true       // it decrypted under a key we accept; that is the proof
        val payload = ByteArray(pt.size - 1)
        System.arraycopy(pt, 1, payload, 0, payload.size)
        return Frame(pt[0].toInt() and 0xff, payload)
    }

    override fun close() {
        try { socket.close() } catch (ignored: IOException) {}
    }

    class Frame internal constructor(val type: Int, val payload: ByteArray)
}
