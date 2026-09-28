package io.github.lcebot.clipsync

import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The frame-level rules that more than one read loop has to get right, in one place.
 *
 * There are three read loops in this app and there is no honest way to make them one: the
 * **control** loop (`SyncService.handleFrame`) runs a peer session and understands every
 * frame type; the **inbound data** loop (`FileExchange.serveData`) is one stream of one
 * transfer that a peer opened, so it must also answer PULL; and the **outbound pull** loop
 * (`Transfer.pull`) is one stream this end opened and only ever receives. Two of the three
 * share enough (decoding the chunk index, and what END/ABORT/PING mean) that keeping that logic
 * in one place is what keeps the two loops answering frames the same way.
 *
 * So: the two *data* loops share [dataLoop], which owns the frame switch, and say
 * what they do about each frame through [Data]. Where their answers genuinely differ (only
 * the server loop can be asked to PULL; only the pulling loop cares why a transfer was aborted),
 * the difference is a method one of them overrides and the other does not, which is a statement
 * rather than an omission. The control loop keeps its own switch, because it shares no frame type
 * with either of them; what it borrows from here is [json] and [pong].
 *
 * **Not a dispatcher for everything.** This holds no state and makes no policy decisions: it
 * decodes, it routes, and every choice about what a frame *means* belongs to the caller.
 */
internal object Frames {
    /** A frame's payload as the JSON object every non-CHUNK frame in this protocol carries. */
    internal fun json(f: Connection.Frame): JSONObject {
        return JSONObject(String(f.payload, StandardCharsets.UTF_8))
    }

    /** The one-field message that names a file: `{sha256}`, with extras added by the caller. */
    internal fun shaMsg(sha: String?): JSONObject {
        return JSONObject().put("sha256", sha)
    }

    /**
     * Twelve characters of a hash, or as many as there are. Bounded rather than a plain substring,
     * so a peer sending an empty or short hash cannot turn a log line into a crash.
     */
    internal fun shortSha(sha: String?): String {
        return if (sha == null) "?" else sha.substring(0, Math.min(12, sha.length))
    }

    /**
     * The chunk index at the head of a CHUNK frame: `u32 index ‖ bytes`, big-endian.
     *
     * The length check is the reason this is a method rather than inline code at each call site:
     * a peer that sends a CHUNK frame shorter than its own header must get a clear protocol error
     * from every loop that reads one, not an ArrayIndexOutOfBoundsException from whichever loop
     * forgot to check.
     */
    internal fun chunkIndex(f: Connection.Frame): Int {
        if (f.payload.size < 4) throw IOException("truncated chunk")
        return ((f.payload[0].toInt() and 0xff) shl 24) or ((f.payload[1].toInt() and 0xff) shl 16) or
                ((f.payload[2].toInt() and 0xff) shl 8) or (f.payload[3].toInt() and 0xff)
    }

    /**
     * The control connection's answer to PING: `t1` echoed back beside our own two stamps.
     *
     * Three timestamps and not an empty frame, because the reply is also the peer's clock
     * measurement: it computes `offset = ((t2 - t1) + (t3 - t4)) / 2` from them and uses it to
     * normalise the version stamp on an incoming clip into its own clock domain. A data connection
     * answers a bare PONG instead, because it carries no clip and compares no versions, so the stamps would
     * be measured and thrown away.
     */
    internal fun pong(c: Connection, ping: JSONObject) {
        val t2 = System.currentTimeMillis()
        val pong = JSONObject()
        pong.put("t1", ping.optLong("t1", 0L))
        pong.put("t2", t2)
        pong.put("t3", System.currentTimeMillis())
        c.sendJson(Connection.T_PONG, pong)
    }

    /**
     * What one data connection does with the frames that arrive on it.
     *
     * Every method but [chunk] has a default, and each default is a deliberate answer
     * rather than a gap; see the overrides at the two call sites for why each loop answers as it
     * does.
     */
    internal interface Data {
        /** Stop before the next blocking read. The pull side is cancelled by `Transfer.abort`. */
        fun stopped(): Boolean {
            return false
        }

        /** One chunk of the file, already decoded. `payload[off..off+len)` are the bytes. */
        fun chunk(idx: Int, payload: ByteArray, off: Int, len: Int)

        /**
         * The peer is asking us for chunks. Only a connection the peer opened can be in this
         * position: a stream this end opened is one this end is pulling on, so a PULL arriving there
         * would mean the peer had confused the two roles, and ignoring it is the honest answer.
         */
        fun pull(c: Connection, msg: JSONObject) {
        }

        /**
         * The peer gave up on this transfer. The frame rather than its parsed body, because one of
         * the two loops does not read the reason and must not fail on a malformed one.
         */
        fun aborted(f: Connection.Frame) {
        }

        /** Keep-alive. Answered where a stream may sit idle, ignored where it never can. */
        fun ping(c: Connection) {
        }
    }

    /**
     * Read one data connection until the transfer on it ends.
     *
     * Returns on END, on ABORT, or when [Data.stopped] says so; anything else, such as a closed
     * socket, a frame that will not decrypt, or a handler that threw, comes out as an exception, which
     * is what both callers want: the stream is one of several carrying one file, and the file's fate
     * is decided by whoever counts the streams out, not here.
     *
     * Unknown frame types are ignored rather than refused. A data connection is not a session and
     * has no business ending a transfer over a frame it does not recognise.
     */
    internal fun dataLoop(c: Connection, d: Data) {
        while (true) {
            if (d.stopped()) return
            val f = c.recv()
            when (f.type) {
                Connection.T_CHUNK -> d.chunk(chunkIndex(f), f.payload, 4, f.payload.size - 4)
                Connection.T_PULL -> d.pull(c, json(f))
                Connection.T_END -> {
                    return
                }
                Connection.T_ABORT -> {
                    d.aborted(f)
                    return
                }
                Connection.T_PING -> d.ping(c)
                else -> {
                }
            }
        }
    }
}
