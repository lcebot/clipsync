package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
 * They are named for how a peer is found, not for the route taken to it — a listed address is very
 * often a LAN address too. At least one of them has to be on.
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
     * The names and literals that point at THIS device — typically a domain a dynamic DNS client
     * here keeps pointed at it. See docs/p2p-plan.md §4a. Read whether or not {@link #direct} is on:
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

    private Config(Properties p) {
        direct = bool(p.getProperty("direct", "true"));
        discovery = bool(p.getProperty("discovery", "true"));
        peers = direct ? peerList(p.getProperty("peers", "")) : List.of();
        ownAddresses = peerList(p.getProperty("own_addresses", ""));
        own = Set.copyOf(ownAddresses);          // peerList already normalised and deduped them
        port = Integer.parseInt(p.getProperty("port").trim());
        pskHex = p.getProperty("psk").trim().toLowerCase();
        psk = hex(pskHex);
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
    /** Raw merged properties (defaults + file), without validation. */
    public static Properties raw(Context ctx) {
        Properties p = new Properties();
        p.setProperty("peers", "");
        p.setProperty("own_addresses", "");     // most devices have no name of their own
        p.setProperty("direct", "false");      // nothing to dial until the user adds an address
        p.setProperty("discovery", "true");    // works with no configuration at all
        p.setProperty("port", "47521");
        p.setProperty("psk", "");
        p.setProperty("mdns_timeout_ms", "4000");
        p.setProperty("threads", "8");
        p.setProperty("max_bytes", "1048576");
        p.setProperty("max_file_bytes", "10485760");
        p.setProperty("max_file_bytes_local", "104857600");
        p.setProperty("files_dir", DEFAULT_FILES_DIR);
        p.setProperty("keep_hours", "2");
        p.setProperty("keep_max_mb", "256");
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {
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
        // the old three-way mode made this impossible to express; two switches can, so it is checked
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

    /** Validates, then writes files/clipsync.conf. Returns the parsed config. */
    public static Config save(Context ctx, Properties values) throws IOException {
        Properties p = raw(ctx);
        for (String k : values.stringPropertyNames()) p.setProperty(k, values.getProperty(k).trim());
        Config c = from(p);                       // throws before anything is written
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            p.store(out, "written by ClipSync MainActivity");
        }
        return c;
    }

    // ------------------------------------------------------------------ field checks (null = ok)
    /**
     * One entry of the peers list: a host name, an IPv4 literal or an IPv6 literal. The field never
     * required dynamic DNS — a static address, a LAN address, a {@code .local} name or a VPN address
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
     * <p>Used for both lists, which is the point — {@code peers} and {@code own_addresses} accept
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
     * <p>A string comparison on the normalised form, never a DNS lookup — this runs on every
     * keystroke on the UI thread, which is the same reason {@link #isIpLiteral} uses
     * {@code InetAddresses.isNumericAddress}. It therefore catches the spellings that were declared
     * and nothing else; a second name for the same host still reaches the handshake.
     */
    public static boolean isSelf(String address, Set<String> own) {
        return own.contains(normalisePeer(address));
    }

    /**
     * Literal address in any form the platform accepts — dotted quad, full or compressed IPv6 —
     * brackets already stripped.
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
        if (v < min || v > max) return "must be " + min + "–" + max + u;
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
     * "/storage/emulated/0/Download/ClipSync" -> "Download/ClipSync". MediaStore can only create
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

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
