package io.github.lcebot.clipsync;

import org.json.JSONObject;

/**
 * Everything a file transfer needs to know about the peer it is moving bytes to, and nothing else.
 *
 * <p>A {@link Transfer} needs to open a data connection to its peer and, if the transfer fails, say
 * so on the control connection, but nothing more. Giving it the whole control {@link Connection} for
 * that would let it send a CLIP, read a HELLO, or close a peer's link, none of which a transfer has
 * any business doing; every one of those would be one careless line away. This type narrows the
 * surface to exactly the two things a transfer is allowed to do.
 *
 * <p><b>Three methods, and no fields of its own.</b> The two callers that need to know a peer's
 * label, address, or how the link came up ({@code FileExchange}, {@code SyncService}) hold the
 * control connection already and ask it directly; a transfer itself only ever needs to open a
 * stream, say one thing out of band, and be recognised. Keeping no snapshot of the peer's state
 * here avoids a second copy of it that could drift from the connection's own.
 *
 * <p><b>What it is not.</b> It is not a value type, and the one thing stopping it is
 * {@link Connection#data}, which takes the control connection to open a data one. Until that method
 * takes a route instead, the connection is held here, private, and reachable only through
 * {@link #data} and {@link #send}, so the coupling is one field in one class instead of a field on
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
     * twice, and a link that closes must stop only what it was itself carrying; matching on the id
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
