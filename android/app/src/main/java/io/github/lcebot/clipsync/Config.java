package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Compile-time defaults (BuildConfig, from android/clipsync.properties) overridden by
 * files/clipsync.conf, which MainActivity writes.
 *
 * Keys: mode (ddns+mdns | ddns | mdns), host, port, psk, mdns_timeout_ms, threads, max_bytes,
 * max_file_bytes, max_file_bytes_local, files_dir, keep_hours, keep_max_mb.
 * Every value is validated in {@link #from(Properties)}; the UI runs the same checks live
 * through the {@code check*} helpers.
 */
public final class Config {
    public static final String FILE = "clipsync.conf";

    public static final String MODE_BOTH = "ddns+mdns", MODE_DDNS = "ddns", MODE_MDNS = "mdns";
    public static final String DEFAULT_FILES_DIR = "/storage/emulated/0/Download/ClipSync";
    public static final int[] THREAD_STEPS = {1, 2, 4, 8, 16};
    public static final int[] BROWSE_STEPS_MS = {1000, 2000, 3000, 4000, 6000, 8000, 10000};

    public final String mode;
    public final String host;           // DDNS name; "" when mode == mdns
    public final int port;
    public final byte[] psk;
    public final String pskHex;
    public final int maxBytes;          // text limit
    public final long maxFileBytes;     // image / file limit when the PC is reached over the internet
    public final long maxFileBytesLocal;// ... and when it is on the LAN (mDNS, or on-link address)
    public final int keepHours;         // received files unused for this long are deleted (0 = never)
    public final long keepMaxBytes;     // and LRU-first above this total (0 = unlimited)
    public final boolean mdns;          // LAN discovery allowed (mode != ddns)
    public final int mdnsTimeoutMs;     // how long one browse may take
    public final int threads;           // parallel data connections per file transfer (1,2,4,8,16)
    public final String filesDir;       // absolute path on internal storage where received files go
    public final String relativePath;   // the same as a MediaStore RELATIVE_PATH ("Download/ClipSync")

    private Config(Properties p) {
        mode = p.getProperty("mode").trim();
        String h = p.getProperty("host", "").trim();
        host = MODE_MDNS.equals(mode) ? "" : h;
        mdns = !MODE_DDNS.equals(mode);
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

    // ------------------------------------------------------------------ load / save
    /** Raw merged properties (defaults + file), without validation. */
    public static Properties raw(Context ctx) {
        Properties p = new Properties();
        p.setProperty("mode", BuildConfig.MODE);
        p.setProperty("host", BuildConfig.HOST);
        p.setProperty("port", String.valueOf(BuildConfig.PORT));
        p.setProperty("psk", BuildConfig.PSK);
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
        // pre-"mode" files: derive it from the old boolean + host
        if (p.getProperty("mode") == null || p.getProperty("mode").trim().isEmpty()) {
            String m = p.getProperty("mdns", "true").trim().toLowerCase();
            boolean mdns = m.equals("true") || m.equals("1") || m.equals("yes") || m.equals("on");
            boolean host = !p.getProperty("host", "").trim().isEmpty();
            p.setProperty("mode", !host ? MODE_MDNS : mdns ? MODE_BOTH : MODE_DDNS);
        }
        return p;
    }

    /** @throws IllegalArgumentException with a field-prefixed, user-readable message */
    public static Config load(Context ctx) {
        return from(raw(ctx));
    }

    public static Config from(Properties p) {
        String mode = p.getProperty("mode", MODE_BOTH).trim();
        if (!mode.equals(MODE_BOTH) && !mode.equals(MODE_DDNS) && !mode.equals(MODE_MDNS))
            throw new IllegalArgumentException("mode: must be ddns+mdns, ddns or mdns");
        fail("host", MODE_MDNS.equals(mode) ? null : checkHost(p.getProperty("host", "")));
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
    public static String checkHost(String s) {
        s = s.trim();
        if (s.isEmpty()) return "required for DDNS";
        if (s.length() > 253 || !s.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) return "not a valid host name";
        return null;
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

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
