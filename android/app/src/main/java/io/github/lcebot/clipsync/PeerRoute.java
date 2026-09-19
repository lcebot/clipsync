package io.github.lcebot.clipsync;

import org.json.JSONObject;

/**
 * Everything a file transfer needs to know about the peer it is moving bytes to, and nothing else.
 *
 * <p>{@link Transfer} used to hold the whole control {@link Connection} for this. It read five
 * fields off it — the address, the peer's <em>listening</em> port, whether the peer is on our LAN,
 * which network the session rides on, and how the link came up — and it kept the object so that the
 * service could later ask "whose transfer is this?". Holding a live session to answer those is
 * more coupling than the question deserves: a Transfer has no business being able to send a CLIP,
 * read a HELLO or close a peer's link, and while it could, every one of those was one careless line
 * away.
 *
 * <p><b>Three methods, and no fields of its own.</b> It began as a snapshot — peer id, label, via,
 * lanPeer, drivesTransfer, all copied at construction — on the assumption that a transfer would
 * want to read them. None of them ever acquired a reader: the two callers that ask those questions
 * ({@code FileExchange}, {@code SyncService}) hold the control connection already and ask it
 * directly, and a transfer only ever needs to open a stream, say one thing out of band, and be
 * recognised. Fields nobody reads are worse than absent ones here, because each of them is a second
 * copy of a connection's state that looks authoritative and is not.
 *
 * <p><b>What it is not.</b> It is not a value type, and the one thing stopping it is
 * {@link Connection#data}, which takes the control connection to open a data one. Until that method
 * takes a route instead, the connection is held here, private, and reachable only through
 * {@link #data} and {@link #send} — so the coupling is one field in one class instead of a field on
 * every transfer. It is also not an identity: two routes to one peer are two routes, and
 * {@link #carriedBy} asks about the connection rather than about the peer, because a link closing
 * must abort what <em>that link</em> was carrying and not what a second link to the same device is.
 */
final class PeerRoute {
    private final Connection control;

    private PeerRoute(Connection c) {
        this.control = c;
    }

    /** The route a transfer on this control connection takes. Call it after the handshake. */
    static PeerRoute of(Connection c) {
        return new PeerRoute(c);
    }

    /**
     * Is this route the one riding {@code c}?
     *
     * <p>Asked by identity and not by peer id on purpose. With several links a peer can be reachable
     * twice, and a link that closes must stop only what it was itself carrying — matching on the id
     * would let a target that merely failed to connect abort the transfer its twin is happily
     * running.
     */
    boolean carriedBy(Connection c) {
        return control == c;
    }

    /** One data connection to this peer, for one stripe of one file. */
    Connection data(Config cfg, String sha256, String device) throws Exception {
        return Connection.data(cfg, control, sha256, device);
    }

    /**
     * Say something on the <b>control</b> connection: an ABORT, which is the only thing a transfer
     * has to say out of band. The peer that is carrying the file is the peer that has to hear it,
     * and with several links a caller that guessed would tell the wrong one.
     */
    void send(int type, JSONObject msg) throws Exception {
        control.sendJson(type, msg);
    }
}
