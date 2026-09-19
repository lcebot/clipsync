package io.github.lcebot.clipsync;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The frame-level rules that more than one read loop has to get right, in one place.
 *
 * <p>There are three read loops in this app and there is no honest way to make them one: the
 * <b>control</b> loop ({@code SyncService.handleFrame}) runs a peer session and understands every
 * frame type; the <b>inbound data</b> loop ({@code FileExchange.serveData}) is one stream of one
 * transfer that a peer opened, so it must also answer PULL; and the <b>outbound pull</b> loop
 * ({@code Transfer.pull}) is one stream this end opened and only ever receives. What they had in
 * common was written out three times and had drifted three ways — the chunk index was decoded with a
 * length check in one place and without it in another, and END/ABORT/PING each meant something
 * slightly different depending on which copy you read.
 *
 * <p>So: the two <em>data</em> loops share {@link #dataLoop}, which owns the frame switch, and say
 * what they do about each frame through {@link Data}. Where their answers genuinely differ — only
 * the server loop can be asked to PULL; only the pulling loop cares why a transfer was aborted —
 * the difference is now a method one of them overrides and the other does not, which is a statement
 * rather than an omission. The control loop keeps its own switch, because it shares no frame type
 * with either of them; what it borrows from here is {@link #json} and {@link #pong}.
 *
 * <p><b>Not a dispatcher for everything.</b> This holds no state and makes no policy decisions: it
 * decodes, it routes, and every choice about what a frame <em>means</em> belongs to the caller.
 */
final class Frames {
    private Frames() {
    }

    /** A frame's payload as the JSON object every non-CHUNK frame in this protocol carries. */
    static JSONObject json(Connection.Frame f) throws Exception {
        return new JSONObject(new String(f.payload, StandardCharsets.UTF_8));
    }

    /** The one-field message that names a file: {@code {sha256}}, with extras added by the caller. */
    static JSONObject shaMsg(String sha) throws Exception {
        return new JSONObject().put("sha256", sha);
    }

    /**
     * Twelve characters of a hash, or as many as there are. Every log line wants this and one of
     * them used to do it without the bound, which a peer could turn into a crash by sending "".
     */
    static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(12, sha.length()));
    }

    /**
     * The chunk index at the head of a CHUNK frame: {@code u32 index ‖ bytes}, big-endian.
     *
     * <p>The length check is the reason this is a method. The inbound loop had it and the outbound
     * pull loop did not, so a peer that sent a CHUNK frame shorter than its own header crashed one
     * of the two with an ArrayIndexOutOfBoundsException where the other reported a protocol error.
     * Both end the stream; only one of them says what happened.
     */
    static int chunkIndex(Connection.Frame f) throws IOException {
        if (f.payload.length < 4) throw new IOException("truncated chunk");
        return ((f.payload[0] & 0xff) << 24) | ((f.payload[1] & 0xff) << 16)
                | ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
    }

    /**
     * The control connection's answer to PING: {@code t1} echoed back beside our own two stamps.
     *
     * <p>Three timestamps and not an empty frame, because the reply is also the peer's clock
     * measurement: it computes {@code offset = ((t2 - t1) + (t3 - t4)) / 2} from them and uses it to
     * normalise the version stamp on an incoming clip into its own clock domain. A data connection
     * answers a bare PONG instead — it carries no clip and compares no versions, so the stamps would
     * be measured and thrown away.
     */
    static void pong(Connection c, JSONObject ping) throws Exception {
        long t2 = System.currentTimeMillis();
        JSONObject pong = new JSONObject();
        pong.put("t1", ping.optLong("t1", 0));
        pong.put("t2", t2);
        pong.put("t3", System.currentTimeMillis());
        c.sendJson(Connection.T_PONG, pong);
    }

    /**
     * What one data connection does with the frames that arrive on it.
     *
     * <p>Every method but {@link #chunk} has a default, and each default is a deliberate answer
     * rather than a gap — see the overrides at the two call sites for why each loop answers as it
     * does.
     */
    interface Data {
        /** Stop before the next blocking read. The pull side is cancelled by {@code Transfer.abort}. */
        default boolean stopped() {
            return false;
        }

        /** One chunk of the file, already decoded. {@code payload[off..off+len)} are the bytes. */
        void chunk(int idx, byte[] payload, int off, int len) throws Exception;

        /**
         * The peer is asking us for chunks. Only a connection the peer opened can be in this
         * position: a stream this end opened is one this end is pulling on, so a PULL arriving there
         * would mean the peer had confused the two roles, and ignoring it is the honest answer.
         */
        default void pull(Connection c, JSONObject msg) throws Exception {
        }

        /**
         * The peer gave up on this transfer. The frame rather than its parsed body, because one of
         * the two loops does not read the reason and must not fail on a malformed one.
         */
        default void aborted(Connection.Frame f) {
        }

        /** Keep-alive. Answered where a stream may sit idle, ignored where it never can. */
        default void ping(Connection c) throws Exception {
        }
    }

    /**
     * Read one data connection until the transfer on it ends.
     *
     * <p>Returns on END, on ABORT, or when {@link Data#stopped()} says so; anything else — a closed
     * socket, a frame that will not decrypt, a handler that threw — comes out as an exception, which
     * is what both callers want: the stream is one of several carrying one file, and the file's fate
     * is decided by whoever counts the streams out, not here.
     *
     * <p>Unknown frame types are ignored rather than refused. A data connection is not a session and
     * has no business ending a transfer over a frame it does not recognise.
     */
    static void dataLoop(Connection c, Data d) throws Exception {
        while (true) {
            if (d.stopped()) return;
            Connection.Frame f = c.recv();
            switch (f.type) {
                case Connection.T_CHUNK -> d.chunk(chunkIndex(f), f.payload, 4, f.payload.length - 4);
                case Connection.T_PULL -> d.pull(c, json(f));
                case Connection.T_END -> {
                    return;
                }
                case Connection.T_ABORT -> {
                    d.aborted(f);
                    return;
                }
                case Connection.T_PING -> d.ping(c);
                default -> {
                }
            }
        }
    }
}
