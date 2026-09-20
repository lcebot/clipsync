package io.github.lcebot.clipsync;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The first frame of every connection: what a node declares about itself, in both directions.
 *
 * <p>Kept separate from {@link Connection} because the two are different things: Connection is the
 * transport (a socket, a nonce exchange, a frame codec) and HELLO is the protocol's vocabulary.
 * Keeping the semantics here, off the live socket, is what lets a field like {@code lan} be read
 * and written against one typed object instead of an {@code optString} at each call site. It also
 * keeps Android's {@code Context} out of the semantic layer: building a declaration needs one to
 * ask {@code Node} for the device type and battery, and a data connection has no Context to give.
 * Hello takes values, not a Context; the caller that has a Context looks the values up.
 *
 * <h2>Field ‖ JSON key: this is the protocol document</h2>
 *
 * <p>The Windows side (clipsync.py) sends and reads the same keys, and there is no other written
 * record of them, so this table is the one both ends are kept in step by.
 *
 * <pre>
 *   v           "v"            int      protocol version; both ends must match exactly
 *   id          "id"           string   node id: the key for link dedup and the self-check
 *   device      "device"       string   the name a human sees
 *   type        "type"         string   "pc" | "tablet" | "phone"
 *   persistent  "persistent"   bool     can this node hold a connection while idle
 *   battery     "battery"      string   "mains" | "high" | "medium" | "low"
 *   clipTs      "clip_ts"      long     this node's clipboard timestamp, for catch-up
 *   clipSha     "clip_sha"     string   digest of that clipboard; OPTIONAL (absent when empty)
 *   lan         "lan"          bool     dialler's verdict on whether the two are on one LAN;
 *                                       OPTIONAL: only the end that dialled sends it
 *   port        "port"         int      where this node LISTENS (an accepted socket's remote port
 *                                       is an ephemeral one and reaches nothing)
 *   dataOut     "data_out"     bool     can this node open data connections of its own
 *   role        "role"         string   OPTIONAL: absent on an ordinary peer link, "data" on a
 *                                       file-transfer connection, "pair" on a pairing one
 *   sha256      "sha256"       string   on a "data" connection, which transfer it belongs to
 * </pre>
 *
 * <p>Immutable, and parsed with {@code opt*} throughout: a HELLO arrives from the network, so a
 * missing or mistyped field is a peer bug, not an exception path. The one field whose absence is
 * fatal ({@code id} on a peer link) is checked by {@link Connection#readHello()}, because what to
 * do about it is a connection decision.
 */
public final class Hello {
    // ---- JSON keys, named once so the table above cannot drift from the code below.
    static final String K_V = "v", K_ID = "id", K_DEVICE = "device", K_TYPE = "type";
    static final String K_PERSISTENT = "persistent", K_BATTERY = "battery";
    static final String K_CLIP_TS = "clip_ts", K_CLIP_SHA = "clip_sha";
    static final String K_LAN = "lan", K_PORT = "port", K_DATA_OUT = "data_out";
    static final String K_ROLE = "role", K_SHA256 = "sha256";

    /** The two connections that are not peer links, and say so. */
    public static final String ROLE_DATA = "data", ROLE_PAIR = "pair";

    public final int v;
    /** Null when the peer sent none; that is the one absence {@link Connection} refuses a peer link for. */
    public final String id;
    /** "" when absent; the caller falls back to the address it dialled. */
    public final String device;
    /** "?" when absent. */
    public final String type;
    /** "" when absent, which is the ordinary case on a peer link. */
    public final String role;
    /** "" when the peer's clipboard is empty; it then omits the key entirely. */
    public final String clipSha;
    /** "" unless {@link #role} is {@link #ROLE_DATA}. */
    public final String sha256;
    public final boolean persistent;
    public final boolean dataOut;
    /** Meaningful only when {@link #has}({@code "lan"}); the accepter never sends one back. */
    public final boolean lan;
    /** A bucket, not a percentage: "mains" | "high" | "medium" | "low". "medium" when absent. */
    public final String battery;
    public final long clipTs;
    /** 0 when absent; the caller keeps whatever it already believed. */
    public final int port;

    /**
     * Which keys this object actually carries.
     *
     * <p>Nothing here can be answered from the field values. {@link #audit} and {@link #auditSent}
     * have to distinguish "the peer sent {@code lan=false}" from "the peer sent no {@code lan}", and
     * {@link #toJson} has to reproduce the exact set a given kind of HELLO is supposed to contain:
     * a data connection's HELLO is five fields, and filling the other eight with defaults would be a
     * wire change dressed up as tidiness.
     */
    private final Set<String> present;

    private Hello(int v, String id, String device, String type, String role, String clipSha,
                  String sha256, boolean persistent, boolean dataOut, boolean lan, String battery,
                  long clipTs, int port, Set<String> present) {
        this.v = v;
        this.id = id;
        this.device = device;
        this.type = type;
        this.role = role;
        this.clipSha = clipSha;
        this.sha256 = sha256;
        this.persistent = persistent;
        this.dataOut = dataOut;
        this.lan = lan;
        this.battery = battery;
        this.clipTs = clipTs;
        this.port = port;
        this.present = Collections.unmodifiableSet(present);
    }

    /** Did the peer send this key at all? See {@link #present}. */
    public boolean has(String key) {
        return present.contains(key);
    }

    // ------------------------------------------------------------------ building our own
    /**
     * This node's declaration on an ordinary peer link.
     *
     * <p>Every argument is a value the caller looked up; nothing here touches Android. {@code
     * clipSha} may be null, and then the key is left out rather than sent empty; see
     * {@link #HELLO_OPTIONAL}.
     */
    public static Hello control(String id, String device, String type, boolean persistent,
                                String battery, long clipTs, String clipSha, boolean lan, int port) {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys, K_V, K_ID, K_DEVICE, K_TYPE, K_PERSISTENT, K_BATTERY, K_CLIP_TS);
        if (clipSha != null) keys.add(K_CLIP_SHA);
        Collections.addAll(keys, K_LAN, K_PORT, K_DATA_OUT);
        return new Hello(Connection.PROTOCOL_VERSION, id, device, type, "", clipSha == null ? "" : clipSha,
                "", persistent, true, lan, battery, clipTs, port, keys);
    }

    /**
     * A file-transfer connection's declaration: whose transfer, and for which file.
     *
     * <p>Deliberately not a peer's HELLO. It carries no capability flags and no clipboard state
     * because nothing on the far side enrols a data connection as a peer; {@code role} is read
     * before any of that happens, and the id is here only so the accepting node can tell whose
     * transfer these bytes belong to.
     */
    public static Hello data(String id, String device, String sha256) {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys, K_V, K_ID, K_DEVICE, K_ROLE, K_SHA256);
        return new Hello(Connection.PROTOCOL_VERSION, id, device, "?", ROLE_DATA, "", sha256,
                false, false, false, "medium", 0, 0, keys);
    }

    /**
     * A pairing client's declaration: who is asking, and nothing that only a configured node has.
     *
     * <p>No node id on purpose. The id is what the self-check and the link dedup key on, so an
     * unpaired device offering one would be enrolling itself into machinery it is not part of yet.
     * What it does send, a name and a device type, is exactly what the provider puts in front of
     * the user before handing the key over, and it is authenticated by nothing but the code, which
     * is the point: a guesser still has to appear under a name the person recognises.
     */
    public static Hello pair(String device, String type) {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys, K_V, K_ROLE, K_DEVICE, K_TYPE);
        return new Hello(Connection.PROTOCOL_VERSION, null, device, type, ROLE_PAIR, "", "",
                false, false, false, "medium", 0, 0, keys);
    }

    // ------------------------------------------------------------------ the wire
    /** Parse what a peer sent. Never throws: every field is optional at this layer. */
    public static Hello parse(JSONObject o) {
        Set<String> keys = new LinkedHashSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        return new Hello(
                o.optInt(K_V, -1),
                o.optString(K_ID, null),
                o.optString(K_DEVICE, ""),
                o.optString(K_TYPE, "?"),
                o.optString(K_ROLE, ""),
                o.optString(K_CLIP_SHA, ""),
                o.optString(K_SHA256, ""),
                o.optBoolean(K_PERSISTENT, false),
                o.optBoolean(K_DATA_OUT, false),
                o.optBoolean(K_LAN, false),
                o.optString(K_BATTERY, "medium"),
                o.optLong(K_CLIP_TS, 0),
                o.optInt(K_PORT, 0),
                keys);
    }

    /**
     * Back to the object that goes on the wire.
     *
     * <p>Writes exactly {@link #present} and nothing else, so each kind of HELLO keeps the shape its
     * factory gave it. The {@code if} per field is the mapping in executable form; the table in the
     * class comment is the same list in readable form, and they are meant to be checked against each
     * other.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (present.contains(K_V)) o.put(K_V, v);
        if (present.contains(K_ID)) o.put(K_ID, id);
        if (present.contains(K_DEVICE)) o.put(K_DEVICE, device);
        if (present.contains(K_TYPE)) o.put(K_TYPE, type);
        if (present.contains(K_PERSISTENT)) o.put(K_PERSISTENT, persistent);
        if (present.contains(K_BATTERY)) o.put(K_BATTERY, battery);
        if (present.contains(K_CLIP_TS)) o.put(K_CLIP_TS, clipTs);
        if (present.contains(K_CLIP_SHA)) o.put(K_CLIP_SHA, clipSha);
        if (present.contains(K_LAN)) o.put(K_LAN, lan);
        if (present.contains(K_PORT)) o.put(K_PORT, port);
        if (present.contains(K_DATA_OUT)) o.put(K_DATA_OUT, dataOut);
        if (present.contains(K_ROLE)) o.put(K_ROLE, role);
        if (present.contains(K_SHA256)) o.put(K_SHA256, sha256);
        return o;
    }

    // ------------------------------------------------------------------ the audit
    /**
     * Fields this end puts in a peer-link HELLO. Keep in lockstep with {@link #control}, and
     * {@link #auditSent} says so in the log when they have drifted apart.
     */
    static final Set<String> HELLO_SENT = Set.of(
            K_V, K_ID, K_DEVICE, K_TYPE, K_PERSISTENT, K_BATTERY, K_CLIP_TS, K_CLIP_SHA,
            K_LAN, K_PORT, K_DATA_OUT);
    /** Fields this end actually consumes from a peer's HELLO. Keep in lockstep with {@link #parse}. */
    static final Set<String> HELLO_READ = Set.of(
            K_V, K_ID, K_DEVICE, K_TYPE, K_PERSISTENT, K_BATTERY, K_PORT, K_DATA_OUT, K_LAN,
            K_ROLE, K_CLIP_TS, K_CLIP_SHA);
    /**
     * The three that may legitimately be missing, so the audits do not cry wolf over them.
     *
     * <p>{@code role} marks the two non-peer connections and is absent on every ordinary one,
     * {@code clip_sha} is omitted by a peer with nothing on its clipboard, and {@code lan} is sent
     * only by the end that dialled; the accepter takes the dialler's word for it and never sends
     * one back, so the dialler never sees one.
     *
     * <p>The Windows side's HELLO_OPTIONAL has a fourth entry, {@code sha256}. It needs it because
     * its HELLO_READ lists {@code sha256}, because the PC reads the field on a data connection through the
     * same contract, while this end's {@link #HELLO_READ} does not: a data connection is answered
     * before the audit runs (see {@link Connection#readHello()}), so {@code sha256} can never be
     * missing from anything audited here. Listing it would declare a rule this end has no use for.
     */
    static final Set<String> HELLO_OPTIONAL = Set.of(K_ROLE, K_CLIP_SHA, K_LAN);

    /** So the sent-side audit says its piece once per process, like the Windows side's start-up call. */
    private static final java.util.concurrent.atomic.AtomicBoolean SENT_AUDITED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Check a HELLO this end is about to send against {@link #HELLO_SENT}, once per process.
     *
     * <p>The counterpart of {@link #audit}, and the reason {@code HELLO_SENT} is worth having at
     * all: the audit below is only as good as its constants, and a constant kept by hand beside the
     * method it describes is exactly the thing that drifts. This is what notices that someone added
     * a field to {@link #control} and not to the contract; that is the same job {@code audit_hello_sent}
     * does on the Windows side, where it runs once at start-up for the same reason.
     *
     * <p>Once, not per connection: the answer cannot change within a run, and a line per session
     * would be noise rather than a finding. {@link #HELLO_OPTIONAL} applies on this side too:
     * {@code clip_sha} is left out by a node with an empty clipboard, and that is a state, not an
     * omission.
     */
    void auditSent() {
        if (!SENT_AUDITED.compareAndSet(false, true)) return;
        Set<String> undeclared = new TreeSet<>(present);
        undeclared.removeAll(HELLO_SENT);
        Set<String> unsent = new TreeSet<>(HELLO_SENT);
        unsent.removeAll(HELLO_OPTIONAL);
        unsent.removeAll(present);
        if (!undeclared.isEmpty() || !unsent.isEmpty())
            Logger.w("HELLO_SENT is out of date: we send but do not declare " + undeclared
                    + "; declared but not sent " + unsent);
    }

    /**
     * One line when the two ends do not agree on what HELLO is for.
     *
     * <p>Every asymmetry this protocol has grown has looked the same from the outside: one end sends
     * a field, the other never reads it, and the feature that field exists for simply does not
     * happen: no exception, no timeout, nothing in either log. The two sets above make that
     * checkable at the one moment both halves are in hand, and the cost is a comparison of a dozen
     * strings once per session.
     *
     * <p>Their upkeep is the honest cost: they are maintained by hand beside the methods they
     * describe, so a new field added to one and not to the set here makes this line lie in the
     * opposite direction. {@link #auditSent} closes half of that hole: it compares a real outgoing
     * HELLO against {@link #HELLO_SENT}, but nothing can check {@link #HELLO_READ} the same way,
     * because "which fields does this end actually consume" is not a set the code can be asked for.
     * It is still the cheaper failure: a spurious warning is read, a silent gap is not.
     *
     * @param peerLabel what to call the peer in the warning
     */
    void audit(String peerLabel) {
        Set<String> ignored = new TreeSet<>(present);
        ignored.removeAll(HELLO_READ);
        Set<String> missing = new TreeSet<>(HELLO_READ);
        missing.removeAll(present);
        missing.removeAll(HELLO_OPTIONAL);
        if (!ignored.isEmpty() || !missing.isEmpty())
            Logger.w("hello audit with " + peerLabel + ": peer sent but we ignore " + ignored
                    + "; we expect but peer omits " + missing);
    }
}
