package io.github.lcebot.clipsync

import org.json.JSONObject

/**
 * Everything a file transfer needs to know about the peer it is moving bytes to, and nothing else.
 *
 * A [Transfer] needs to open a data connection to its peer and, if the transfer fails, say
 * so on the control connection, but nothing more. Giving it the whole control [Connection] for
 * that would let it send a CLIP, read a HELLO, or close a peer's link, none of which a transfer has
 * any business doing; every one of those would be one careless line away. This type narrows the
 * surface to exactly the two things a transfer is allowed to do.
 *
 * **Three methods, and no fields of its own.** The two callers that need to know a peer's
 * label, address, or how the link came up (`FileExchange`, `SyncService`) hold the
 * control connection already and ask it directly; a transfer itself only ever needs to open a
 * stream, say one thing out of band, and be recognised. Keeping no snapshot of the peer's state
 * here avoids a second copy of it that could drift from the connection's own.
 *
 * **What it is not.** It is not a value type, and the one thing stopping it is
 * [Connection.data], which takes the control connection to open a data one. Until that method
 * takes a route instead, the connection is held here, private, and reachable only through
 * [data] and [send], so the coupling is one field in one class instead of a field on
 * every transfer. It is also not an identity: two routes to one peer are two routes, and
 * [carriedBy] asks about the connection rather than about the peer, because a link closing
 * must abort what *that link* was carrying and not what a second link to the same device is.
 */
internal class PeerRoute private constructor(c: Connection) {
    private val control: Connection = c

    /**
     * Is this route the one riding `c`?
     *
     * Asked by identity and not by peer id on purpose. With several links a peer can be reachable
     * twice, and a link that closes must stop only what it was itself carrying; matching on the id
     * would let a target that merely failed to connect abort the transfer its twin is happily
     * running.
     */
    internal fun carriedBy(c: Connection): Boolean {
        return control === c
    }

    /** One data connection to this peer, for one stripe of one file. */
    internal fun data(cfg: Config, sha256: String, device: String): Connection {
        return Connection.data(cfg, control, sha256, device)
    }

    /**
     * Say something on the **control** connection: an ABORT, which is the only thing a transfer
     * has to say out of band. The peer that is carrying the file is the peer that has to hear it,
     * and with several links a caller that guessed would tell the wrong one.
     */
    internal fun send(type: Int, msg: JSONObject) {
        control.sendJson(type, msg)
    }

    companion object {
        /** The route a transfer on this control connection takes. Call it after the handshake. */
        internal fun of(c: Connection): PeerRoute {
            return PeerRoute(c)
        }
    }
}
