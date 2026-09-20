package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Everything is configured at runtime, in files/clipsync.conf, which MainActivity writes. There are
 * no compile-time defaults: a released APK carries no configuration at all, which is also why it
 * cannot carry anybody's key.
 *
 * <p>Keys: peers, discovery, direct, port, psk, mdns_timeout_ms, threads, max_bytes,
 * max_file_bytes, max_file_bytes_local, files_dir, keep_hours, keep_max_mb.
 * Every value is validated in {@link #from(Properties)}; the UI runs the same checks live through
 * the {@code check*} helpers.
 *
 * <p>Peers are found two ways, and the two are independent switches rather than a mode: {@code
 * discovery} asks the local network (mDNS), {@code direct} works through the {@code peers} list.
 * They are named for how a peer is found, not for the route taken to it; a listed address is very
 * often a LAN address too. At least one of them has to be on.
 *
 * <h2>Why the PSK is still in a plain text file</h2>
 *
 * <p>{@code psk}, {@code psk_next} and {@code psk_old} are the keys to the user's clipboard, and
 * they sit in this file as hex. The obvious hardening, {@code EncryptedSharedPreferences} or
 * wrapping the value with a Keystore key, was evaluated and <b>rejected on a constraint, not on
 * effort</b>: {@code SyncService} runs in its own {@code :sync} process and the UI runs in the main
 * one, and <em>both</em> read and write this file. {@code EncryptedSharedPreferences} is explicitly
 * not multi-process safe (neither is plain {@code SharedPreferences}); two processes with it open
 * lose writes and can corrupt the store, which for the file that carries the PSK means a device
 * that has to be paired again. Key rotation makes that worse, not better, because the service
 * rewrites the schedule whenever a peer reports one. A Keystore-wrapped blob inside this same file
 * would keep the atomic-rename write and survive the two processes, but it buys very little: what
 * it defends against is an attacker who can already read {@code /data/data/<pkg>/files}, which on a
 * non-rooted device is nobody, and on a rooted one is somebody who can also ask Keystore to
 * unwrap it as this app.
 *
 * <p>So the protection is the three things that actually apply here: the file lives in the app's
 * private directory, {@code android:allowBackup="false"} keeps it out of cloud and adb backups,
 * and it is written through {@link Files#atomicWrite} so it is never observed half-written. The key
 * material in {@code String} form is deliberately confined to this class, {@link Keys.Schedule} and
 * the pairing exchange; everywhere else it is a {@code byte[]} that can be, and is, zeroed.
 */
public final class Config {
    public static final String FILE = "clipsync.conf";

    public static final String DEFAULT_FILES_DIR = "/storage/emulated/0/Download/ClipSync";
    public static final int[] THREAD_STEPS = {1, 2, 4, 8, 16};
    public static final int[] BROWSE_STEPS_MS = {1000, 2000, 3000, 4000, 6000, 8000, 10000};

    public final List<String> peers;    // host names or literal addresses, in the order entered
    public final boolean direct;        // dial the list at all
    public final boolean discovery;     // look for peers on the local network (mDNS)
    /**
     * The names and literals that point at THIS device, typically a domain a dynamic DNS client
     * here keeps pointed at it. Read whether or not {@link #direct} is on:
     * it is what the node knows itself by, which stays true when it is dialling nobody.
     */
    public final List<String> ownAddresses;
    /** {@link #ownAddresses} normalised, for the membership tests that actually run. */
    public final Set<String> own;
    public final int port;
    public final byte[] psk;
    public final String pskHex;
    public final int maxBytes;          // text limit
    public final long maxFileBytes;     // image / file limit for a peer reached over the internet
    public final long maxFileBytesLocal;// ... and for one on the LAN (mDNS, or on-link address)
    public final int keepHours;         // received files unused for this long are deleted (0 = never)
    public final long keepMaxBytes;     // and LRU-first above this total (0 = unlimited)
    public final int mdnsTimeoutMs;     // how long one browse may take
    public final int threads;           // parallel data connections per file transfer (1,2,4,8,16)
    public final String filesDir;       // absolute path on internal storage where received files go
    public final String relativePath;   // the same as a MediaStore RELATIVE_PATH ("Download/ClipSync")
    /** Whether this device replaces its key on a schedule at all; {@link Keys} holds the schedule. */
    public final boolean rotate;
    /**
     * Opt out of relaying files for other devices on the LAN. When true,
     * this node reports {@code persistent=false} in HELLO regardless of its actual state, making
     * it unlikely to be elected, and directly declines any RELAY_ASK with "refused".
     */
    public final boolean relayOptOut;
    /**
     * The key's life: successor, superseded ring, and the deadlines.
     *
     * <p>Read whether or not {@link #rotate} is on, because the <b>ring still applies</b>: a device
     * that rotated and then had rotation turned off must go on accepting the keys it has already
     * superseded, or it would lock out the peers it rotated away from.
     */
    public final Keys.Schedule keys;

    private Config(Properties p) {
        direct = bool(p.getProperty("direct", "true"));
        discovery = bool(p.getProperty("discovery", "true"));
        peers = direct ? peerList(p.getProperty("peers", "")) : List.of();
        ownAddresses = peerList(p.getProperty("own_addresses", ""));
        own = Set.copyOf(ownAddresses);          // peerList already normalised and deduped them
        port = Integer.parseInt(p.getProperty("port").trim());
        pskHex = p.getProperty("psk").trim().toLowerCase();
        psk = Crypto.fromHex(pskHex);
        // Unreachable through the ordinary path, because from() has already run checkPsk, but this
        // constructor takes a Properties and a future caller could skip that. A null key would not
        // fail here; it would fail later, inside a handshake, as an obscure NPE on a worker thread.
        if (psk == null) throw new IllegalArgumentException("psk: must be 64 hex characters");
        rotate = bool(p.getProperty("psk_rotate", "false"));
        relayOptOut = bool(p.getProperty("relay_opt_out", "false"));
        keys = schedule(p);
        maxBytes = Integer.parseInt(p.getProperty("max_bytes").trim());
        maxFileBytes = Long.parseLong(p.getProperty("max_file_bytes").trim());
        maxFileBytesLocal = Long.parseLong(p.getProperty("max_file_bytes_local").trim());
        keepHours = Integer.parseInt(p.getProperty("keep_hours").trim());
        keepMaxBytes = Long.parseLong(p.getProperty("keep_max_mb").trim()) * 1024 * 1024;
        mdnsTimeoutMs = Integer.parseInt(p.getProperty("mdns_timeout_ms").trim());
        threads = snapThreads(Integer.parseInt(p.getProperty("threads").trim()));
        filesDir = p.getProperty("files_dir").trim();
        relativePath = relativePathOf(filesDir);
    }

    public long maxFileAny() {
        return Math.max(maxFileBytes, maxFileBytesLocal);
    }

    /**
     * Read the key schedule, treating a missing activation time as <b>now</b>.
     *
     * <p>Not as zero, which is what a missing number would otherwise mean and which would make any
     * key with no recorded activation time 55 years old, pre-retired on the first read, and rotated
     * out from under a household that had not asked for any of this. "I do not know when this key
     * started" has exactly one safe reading, and it is the generous one.
     *
     * <p><b>Generous is not the same as amnesiac, and that distinction is the whole of
     * {@link #unknownAge()}.</b> Reading a missing activation time as <em>now</em> makes the key's
     * age restart-proof in the wrong direction: every launch re-decides it, so a device that is
     * restarted more often than the rotation deadline never reaches the deadline at all, and the
     * key is immortal by accident. The service writes the answer down the first time it sees the
     * gap, which closes the hole, but only if that write lands, and a fallback whose correctness
     * depends on a write having succeeded is not a fallback.
     *
     * <p>So the anchor is the configuration file's own modification time, not the clock. It is
     * already on disk, it survives restarts because nothing here rewrites it for fun, and it is
     * conservative in the direction that matters: the key cannot be newer than the file that holds
     * it. A device restarted hourly now ages its key by an hour each time rather than by nothing.
     */
    private static Keys.Schedule schedule(Properties p) {
        long since = longOr(p, "psk_since", 0);
        return new Keys.Schedule(
                p.getProperty("psk", "").trim().toLowerCase(),
                p.getProperty("psk_next", "").trim().toLowerCase(),
                peerList(p.getProperty("psk_old", "")),
                since > 0 ? since : unknownAge(),
                longOr(p, "psk_retire", 0),
                longOr(p, "psk_agreed", 0));
    }

    /**
     * When to say a key started, when the file does not say.
     *
     * <p>The file's own mtime, falling back to the clock only when there is no file, which is a first run,
     * where "now" is exactly right because the key is about to be written for the first time.
     */
    private static long unknownAge() {
        long stamp = fileStamp;
        return stamp > 0 ? stamp : System.currentTimeMillis();
    }

    /**
     * The configuration file's modification time as of the last read, or 0 if it does not exist.
     *
     * <p>Kept beside the cache because {@link #raw} already has to stat the file, so this costs
     * nothing, and because {@link #schedule} is handed a {@link Properties} and has no file to ask.
     */
    private static volatile long fileStamp;

    private static long longOr(Properties p, String key, long dflt) {
        try {
            return Long.parseLong(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * Record a key as newly in service: its clock starts now, and any schedule around the old one
     * is cleared.
     *
     * <p>Every way a key arrives goes through here, whether typed, generated, or taken from a peer while
     * pairing, because {@link #save} merges over what is stored, so a caller that sets only
     * {@code psk} inherits the *previous* key's activation time and successor. The key would then be
     * pre-retired on the strength of how long the one it replaced had been in use, which for a key
     * two days old is within minutes of being written.
     */
    public static void freshKey(Properties v, String pskHex) {
        v.setProperty("psk", pskHex);
        v.setProperty("psk_since", String.valueOf(System.currentTimeMillis()));
        v.setProperty("psk_next", "");
        v.setProperty("psk_old", "");
        v.setProperty("psk_retire", "0");
        v.setProperty("psk_agreed", "0");
    }

    /** The schedule as the fields a configuration file holds, for {@link #save}. */
    public static Properties store(Keys.Schedule s, boolean rotate) {
        Properties v = new Properties();
        v.setProperty("psk", s.psk);
        v.setProperty("psk_next", s.next);
        v.setProperty("psk_old", String.join(",", s.old));
        v.setProperty("psk_since", String.valueOf(s.since));
        v.setProperty("psk_retire", String.valueOf(s.retireAt));
        v.setProperty("psk_agreed", String.valueOf(s.agreedAt));
        v.setProperty("psk_rotate", String.valueOf(rotate));
        return v;
    }

    /** Largest frame we accept: a CHUNK, or a CLIP whose JSON escaping doubled the text. */
    public int maxFrame() {
        return Math.max(Connection.CHUNK, maxBytes * 2) + 64 * 1024;
    }

    // ------------------------------------------------------------------ peers list
    /**
     * Splits the stored form. Comma-separated and not colon-separated for a reason: an IPv6 literal
     * is nothing but colons. Blanks are dropped and repeats collapse, so a list that round-trips
     * through the UI cannot grow empty rows.
     */
    public static List<String> peerList(String stored) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : stored.split(",")) {
            String t = normalisePeer(s);
            if (!t.isEmpty()) out.add(t);
        }
        return new ArrayList<>(out);
    }

    public static String storePeers(List<String> peers) {
        return String.join(",", peers);
    }

    /** Trims, drops a bracketed IPv6's brackets, and lower-cases so duplicates compare equal. */
    public static String normalisePeer(String s) {
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) t = t.substring(1, t.length() - 1);
        return t.toLowerCase();
    }

    // ------------------------------------------------------------------ load / save
    /**
     * The last parse of the file, and the stamp it was parsed at.
     *
     * <p>{@link #raw} is called from several places on the UI thread, including every keystroke validation
     * path, the PSK comparison and the pairing sheet, and each call was a file open, a parse and a
     * close. The cache is keyed on the file's modification time <em>and</em> its length, because
     * either alone is guessable: mtime has one-second granularity on some filesystems, and a
     * rewrite that changes only a key's hex digits keeps the length. Together they are enough for a
     * file that is rewritten by a person, not by a loop.
     *
     * <p>It has to be invalidated across processes, not just within one: the UI is in the main
     * process and {@code SyncService} is in {@code :sync}, and both write this file. That is why the
     * stamp is taken from the file rather than from a flag, because a write by the other process moves the
     * mtime, which is all this needs to see. {@link #save} additionally clears it outright, because
     * a write and the read that follows it can land inside the same mtime tick.
     */
    private static Properties cached;
    private static long cachedStamp = -1, cachedLength = -1;

    /** Raw merged properties (defaults + file), without validation. */
    public static synchronized Properties raw(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        long stamp = f.lastModified(), length = f.length();
        fileStamp = stamp;      // the anchor for a key whose activation time was never written
        // A copy every time, never the cached object: callers mutate what they get back (save()
        // merges into it, the UI overwrites fields before validating), and handing out the cache
        // itself would let one caller's edits become the next caller's file contents.
        if (cached != null && stamp == cachedStamp && length == cachedLength) {
            Properties copy = new Properties();
            copy.putAll(cached);
            return copy;
        }
        Properties p = parse(f);
        cached = p;
        cachedStamp = stamp;
        cachedLength = length;
        Properties copy = new Properties();
        copy.putAll(p);
        return copy;
    }

    /** Forget the cached parse. Called after every write, from whichever process performed it. */
    private static synchronized void invalidate() {
        cached = null;
        cachedStamp = -1;
        cachedLength = -1;
    }

    /** The defaults, then the file laid over them. The part {@link #raw} caches. */
    private static Properties parse(File f) {
        Properties p = new Properties();
        p.setProperty("peers", "");
        p.setProperty("own_addresses", "");     // most devices have no name of their own
        p.setProperty("direct", "false");      // nothing to dial until the user adds an address
        p.setProperty("discovery", "true");    // works with no configuration at all
        p.setProperty("port", "47521");
        p.setProperty("psk", "");
        // Key rotation. Off by default: it silently changes the one setting every device has to
        // agree on, and a user who has not asked for that should not get it.
        p.setProperty("psk_rotate", "false");
        p.setProperty("relay_opt_out", "false");
        // When the current key became active. Absent means "unknown", and unknown is read as NOW
        // rather than as the epoch, which is the conservative direction, because the alternative is a first
        // launch that finds a key 55 years old and rotates it before the user has finished setting
        // up the second device.
        p.setProperty("psk_since", "0");
        p.setProperty("psk_next", "");
        p.setProperty("psk_retire", "0");
        p.setProperty("psk_agreed", "0");
        // Superseded keys, newest first, still accepted. A comma list like `peers`, for the same
        // reason: Properties holds strings, and this is the shape the file already uses for one.
        p.setProperty("psk_old", "");
        p.setProperty("mdns_timeout_ms", "4000");
        p.setProperty("threads", "8");
        p.setProperty("max_bytes", "1048576");
        p.setProperty("max_file_bytes", "10485760");
        p.setProperty("max_file_bytes_local", "104857600");
        p.setProperty("files_dir", DEFAULT_FILES_DIR);
        p.setProperty("keep_hours", "2");
        p.setProperty("keep_max_mb", "256");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception e) {
                // Never silently: falling back to the defaults means falling back to psk="", which
                // fails validation, which the user sees as "invalid config" with nothing in the log
                // to say the file could not be read at all. The two have opposite fixes.
                Logger.w("config: cannot read " + FILE + ", falling back to defaults: " + e);
            }
        }
        return p;
    }

    /** @throws IllegalArgumentException with a field-prefixed, user-readable message */
    public static Config load(Context ctx) {
        return from(raw(ctx));
    }

    public static Config from(Properties p) {
        boolean direct = bool(p.getProperty("direct", "true"));
        boolean discovery = bool(p.getProperty("discovery", "true"));
        // discovery and direct are independent switches, so this is the one state they can jointly
        // reach that leaves no way to find any peer at all, and it is checked for exactly that reason
        if (!direct && !discovery)
            throw new IllegalArgumentException("discovery: turn on local network discovery or direct connections");
        fail("own_addresses", checkAddresses(p.getProperty("own_addresses", ""), true, Set.of()));
        // Checked against the own list, so this device's own name pasted into the peer list is
        // refused here rather than dialled, connected, handshaken and discarded. The id comparison
        // in HELLO stays the authority: a second name for the same host looks like any other name.
        if (direct) fail("peers", checkAddresses(p.getProperty("peers", ""), false,
                Set.copyOf(peerList(p.getProperty("own_addresses", "")))));
        fail("port", checkPort(p.getProperty("port", "")));
        fail("psk", checkPsk(p.getProperty("psk", "")));
        // The successor and the ring are checked here for the same reason `psk` is: all three are
        // parsed on every inbound connection, and an authenticated peer can write to psk_next and
        // psk_old through T_KEYS. Connection skips a ring entry that fails to parse rather than
        // failing the whole accept, but this is still the point where a bad value can be refused
        // outright and the user actually told about it, instead of it being silently written and
        // only skipped later.
        fail("psk_next", checkPskOrEmpty(p.getProperty("psk_next", "")));
        for (String k : peerList(p.getProperty("psk_old", ""))) fail("psk_old", checkPsk(k));
        fail("mdns_timeout_ms", checkRange(p.getProperty("mdns_timeout_ms", ""), 500, 60000, "ms"));
        fail("threads", checkRange(p.getProperty("threads", ""), 1, 32, ""));
        fail("max_bytes", checkRange(p.getProperty("max_bytes", ""), 1024, 65536L * 1024, "bytes"));
        fail("max_file_bytes", checkRange(p.getProperty("max_file_bytes", ""), 1024L * 1024, 4096L * 1024 * 1024, "bytes"));
        fail("max_file_bytes_local", checkRange(p.getProperty("max_file_bytes_local", ""), 1024L * 1024, 4096L * 1024 * 1024, "bytes"));
        fail("files_dir", checkPath(p.getProperty("files_dir", "")));
        fail("keep_hours", checkRange(p.getProperty("keep_hours", ""), 0, 8760, "h"));
        fail("keep_max_mb", checkRange(p.getProperty("keep_max_mb", ""), 0, 1024 * 1024, "MB"));
        return new Config(p);
    }

    private static void fail(String key, String problem) {
        if (problem != null) throw new IllegalArgumentException(key + ": " + problem);
    }

    /**
     * Validates, then writes files/clipsync.conf. Returns the parsed config.
     *
     * <p>Atomically, through {@link Files#atomicWrite}: this file carries the PSK, it is rewritten
     * every time a peer reports a key schedule, and a process killed halfway through an in-place
     * rewrite leaves a key file that no longer parses, which the service reports as "invalid
     * config" and which survives every restart, so the only way out is to pair every device again.
     */
    public static Config save(Context ctx, Properties values) throws IOException {
        Properties p = raw(ctx);
        for (String k : values.stringPropertyNames()) p.setProperty(k, values.getProperty(k).trim());
        Config c = from(p);                       // throws before anything is written
        Files.atomicWrite(new File(ctx.getFilesDir(), FILE),
                out -> p.store(out, "written by ClipSync MainActivity"));
        invalidate();       // the next raw() must see what was just written, mtime tick or not
        return c;
    }

    // ------------------------------------------------------------------ field checks (null = ok)
    /**
     * One entry of the peers list: a host name, an IPv4 literal or an IPv6 literal. The field never
     * required dynamic DNS; a static address, a LAN address, a {@code .local} name or a VPN address
     * are all equally valid, and the code always resolved them the same way.
     */
    public static String checkPeer(String s) {
        String t = normalisePeer(s);
        if (t.isEmpty()) return "required";
        if (t.contains("://")) return "just the host or address, without http://";
        if (t.contains("%")) return "an interface name means nothing on another device";
        if (isIpLiteral(t)) return null;                 // covers IPv4 and every IPv6 form
        // only now can a colon mean a port: an IPv6 literal is nothing but colons
        if (t.matches(".+:\\d+")) return "the port has its own field";
        String h = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;   // a trailing dot is legal
        if (h.length() > 253) return "too long for a host name";
        for (String label : h.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) return "not a valid host name";
            if (label.startsWith("-") || label.endsWith("-")) return "not a valid host name";
            if (!label.matches("[a-z0-9-]+")) return "not a valid host name";
        }
        return null;
    }

    /**
     * A whole address list: every entry valid, no repeats, and optionally at least one entry.
     *
     * <p>Used for both lists, which is the point: {@code peers} and {@code own_addresses} accept
     * exactly the same things and must not drift into accepting different ones. They differ in two
     * parameters only: the peer list needs an entry while Direct connections is on and the own list
     * never does, and a peer entry is additionally refused when it names this device.
     *
     * @param own normalised own-address list; empty when checking the own list itself
     */
    public static String checkAddresses(String stored, boolean allowEmpty, Set<String> own) {
        List<String> seen = new ArrayList<>();
        boolean any = false;
        for (String s : stored.split(",")) {
            if (s.trim().isEmpty()) continue;
            any = true;
            String problem = checkPeer(s);
            if (problem != null) return problem;
            String t = normalisePeer(s);
            if (seen.contains(t)) return "listed twice: " + t;
            if (own.contains(t)) return "that is this device: " + t;
            seen.add(t);
        }
        if (!any && !allowEmpty) return "add an address, or turn direct connections off";
        return null;
    }

    /**
     * Does this address name this device, as far as the declared list can tell?
     *
     * <p>A string comparison on the normalised form, never a DNS lookup, because this runs on every
     * keystroke on the UI thread, which is the same reason {@link #isIpLiteral} uses
     * {@code InetAddresses.isNumericAddress}. It therefore catches the spellings that were declared
     * and nothing else; a second name for the same host still reaches the handshake.
     */
    public static boolean isSelf(String address, Set<String> own) {
        return own.contains(normalisePeer(address));
    }

    /**
     * Literal address in any form the platform accepts: dotted quad, full or compressed IPv6,
     * with brackets already stripped.
     *
     * <p>{@link android.net.InetAddresses#isNumericAddress} and not {@code InetAddress.getByName}:
     * the latter performs a DNS lookup for anything that is not a literal, and this runs on the UI
     * thread on every keystroke. "abc" is all hex digits and also a perfectly good host name, so no
     * amount of pattern-matching first makes that safe.
     */
    private static boolean isIpLiteral(String s) {
        try {
            return android.net.InetAddresses.isNumericAddress(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String checkPort(String s) {
        return checkRange(s, 1, 65535, "");
    }

    /** {@link #checkPsk}, but "no key" is a legal answer, which it is for a successor, not for a PSK. */
    public static String checkPskOrEmpty(String s) {
        return s.trim().isEmpty() ? null : checkPsk(s);
    }

    public static String checkPsk(String s) {
        s = s.trim();
        if (s.isEmpty()) return "required";
        if (!s.toLowerCase().matches("[0-9a-f]{64}")) return "must be 64 hex characters (" + s.length() + " given)";
        return null;
    }

    public static String checkPath(String s) {
        s = s.trim();
        if (s.isEmpty()) return "required";
        try {
            relativePathOf(s);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Integer in [min, max]; unit only decorates the message. */
    public static String checkRange(String s, long min, long max, String unit) {
        s = s.trim();
        if (s.isEmpty()) return "required";
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            return "must be a whole number";
        }
        String u = unit.isEmpty() ? "" : " " + unit;
        if (v < min || v > max) return "must be " + min + " to " + max + u;
        return null;
    }

    /** Nearest of 1, 2, 4, 8, 16. */
    public static int snapThreads(int n) {
        int best = THREAD_STEPS[0];
        for (int s : THREAD_STEPS) if (Math.abs(s - n) < Math.abs(best - n)) best = s;
        return best;
    }

    /** Nearest of the browse-time steps. */
    public static int snapBrowse(int ms) {
        int best = BROWSE_STEPS_MS[0];
        for (int s : BROWSE_STEPS_MS) if (Math.abs(s - ms) < Math.abs(best - ms)) best = s;
        return best;
    }

    /**
     * "/storage/emulated/0/Download/ClipSync" becomes "Download/ClipSync". MediaStore can only create
     * files for us under the well-known top-level folders; for arbitrary content that means
     * Download/ or Documents/ (Pictures/, Movies/, Music/ reject non-matching MIME types).
     */
    public static String relativePathOf(String abs) {
        String root = android.os.Environment.getExternalStorageDirectory().getPath();
        String s = abs.replace('\\', '/').trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String prefix : new String[]{root + "/", "/sdcard/", "/storage/emulated/0/", "/storage/self/primary/"}) {
            if (s.startsWith(prefix)) { s = s.substring(prefix.length()); break; }
        }
        if (s.startsWith("/")) throw new IllegalArgumentException("must be on internal storage (" + root + "/…)");
        if (s.isEmpty() || s.contains("..")) throw new IllegalArgumentException("must name a folder under Download/ or Documents/");
        String top = s.contains("/") ? s.substring(0, s.indexOf('/')) : s;
        if (!top.equals("Download") && !top.equals("Documents"))
            throw new IllegalArgumentException("must be under Download/ or Documents/");
        return s;
    }

    private static boolean bool(String s) {
        return Arrays.asList("true", "1", "yes", "on").contains(s.trim().toLowerCase());
    }
}
