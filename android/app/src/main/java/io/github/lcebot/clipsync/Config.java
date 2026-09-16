package io.github.lcebot.clipsync;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * host/port/psk/mdns from BuildConfig (compile-time defaults), overridden by
 * files/clipsync.conf, which MainActivity writes.
 */
public final class Config {
    public static final String FILE = "clipsync.conf";

    public final String host;           // DDNS name; may be empty -> mDNS only
    public final int port;
    public final byte[] psk;
    public final String pskHex;
    public final int maxBytes;          // text limit
    public final long maxFileBytes;      // image / file limit when the PC is reached over the internet
    public final long maxFileBytesLocal; // ... and when it is on the LAN (mDNS, or on-link address)
    public final int keepHours;         // received files unused for this long are deleted (0 = never)
    public final long keepMaxBytes;     // and oldest-first above this total (0 = unlimited)
    public final boolean mdns;          // LAN discovery as second path (default on)
    public final int mdnsTimeoutMs;     // how long one browse may take
    public final int threads;           // parallel data connections per file transfer

    private Config(String host, int port, String pskHex, int maxBytes, long maxFileBytes, long maxFileBytesLocal,
                   int keepHours, long keepMaxBytes, boolean mdns, int mdnsTimeoutMs, int threads) {
        this.threads = threads;
        this.host = host;
        this.port = port;
        this.pskHex = pskHex;
        this.psk = hex(pskHex);
        this.maxBytes = maxBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxFileBytesLocal = maxFileBytesLocal;
        this.keepHours = keepHours;
        this.keepMaxBytes = keepMaxBytes;
        this.mdns = mdns;
        this.mdnsTimeoutMs = mdnsTimeoutMs;
    }

    public long maxFileAny() {
        return Math.max(maxFileBytes, maxFileBytesLocal);
    }

    /** Largest frame we accept: a DATA chunk, or a CLIP whose JSON escaping doubled the text. */
    public int maxFrame() {
        return Math.max(Connection.CHUNK, maxBytes * 2) + 64 * 1024;
    }

    /** Raw merged properties (defaults + file), without validation. */
    public static Properties raw(Context ctx) {
        Properties p = new Properties();
        p.setProperty("host", BuildConfig.HOST);
        p.setProperty("port", String.valueOf(BuildConfig.PORT));
        p.setProperty("psk", BuildConfig.PSK);
        p.setProperty("max_bytes", "1048576");
        p.setProperty("max_file_bytes", "10485760");
        p.setProperty("max_file_bytes_local", "104857600");
        p.setProperty("keep_hours", "2");
        p.setProperty("keep_max_mb", "256");
        p.setProperty("mdns", BuildConfig.MDNS ? "true" : "false");
        p.setProperty("mdns_timeout_ms", "4000");
        p.setProperty("threads", "8");
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    /** @throws IllegalArgumentException with a user-readable message if a value is invalid */
    public static Config load(Context ctx) {
        return from(raw(ctx));
    }

    public static Config from(Properties p) {
        String host = p.getProperty("host", "").trim();
        String pskHex = p.getProperty("psk", "").trim().toLowerCase();
        if (!pskHex.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("PSK must be 64 hex chars");
        int port = parseInt(p.getProperty("port", "47521"), "port");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1-65535");
        int timeout = parseInt(p.getProperty("mdns_timeout_ms", "4000"), "mDNS timeout");
        if (timeout < 500 || timeout > 60000) throw new IllegalArgumentException("mDNS timeout must be 500-60000 ms");
        String m = p.getProperty("mdns", "true").trim().toLowerCase();
        boolean mdns = m.equals("true") || m.equals("1") || m.equals("yes") || m.equals("on");
        if (host.isEmpty() && !mdns) throw new IllegalArgumentException("host is empty and mDNS is off: nothing to connect to");
        int threads = parseInt(p.getProperty("threads", "8"), "streams");
        if (threads < 1 || threads > 32) throw new IllegalArgumentException("streams must be 1-32");
        return new Config(host, port, pskHex,
                parseInt(p.getProperty("max_bytes", "1048576"), "max_bytes"),
                parseLong(p.getProperty("max_file_bytes", "10485760"), "max_file_bytes"),
                parseLong(p.getProperty("max_file_bytes_local", "104857600"), "max_file_bytes_local"),
                parseInt(p.getProperty("keep_hours", "2"), "keep_hours"),
                (long) parseInt(p.getProperty("keep_max_mb", "256"), "keep_max_mb") * 1024 * 1024,
                mdns, timeout, threads);
    }

    /**
     * Validates, then writes files/clipsync.conf. Returns the parsed config.
     * Sizes come from the UI in KB (text) and MB (file / keep) and are stored in bytes / MB.
     */
    public static Config save(Context ctx, String host, String port, String pskHex, boolean mdns, String mdnsTimeoutMs,
                              String threads, String textKb, String fileMb, String fileMbLocal, String keepHours,
                              String keepMb) throws IOException {
        Properties p = raw(ctx);
        p.setProperty("host", host.trim());
        p.setProperty("port", port.trim());
        p.setProperty("psk", pskHex.trim().toLowerCase());
        p.setProperty("mdns", mdns ? "true" : "false");
        p.setProperty("mdns_timeout_ms", mdnsTimeoutMs.trim());
        p.setProperty("threads", threads.trim());
        int kb = parseInt(textKb, "text limit"), mb = parseInt(fileMb, "file limit"), mbLocal = parseInt(fileMbLocal, "LAN file limit");
        if (kb < 1 || kb > 65536) throw new IllegalArgumentException("text limit must be 1-65536 KB");
        if (mb < 1 || mb > 4096) throw new IllegalArgumentException("file limit must be 1-4096 MB");
        if (mbLocal < 1 || mbLocal > 4096) throw new IllegalArgumentException("LAN file limit must be 1-4096 MB");
        int hours = parseInt(keepHours, "keep hours"), keep = parseInt(keepMb, "keep size");
        if (hours < 0 || keep < 0) throw new IllegalArgumentException("keep hours / keep size must be 0 or more");
        p.setProperty("max_bytes", String.valueOf(kb * 1024));
        p.setProperty("max_file_bytes", String.valueOf((long) mb * 1024 * 1024));
        p.setProperty("max_file_bytes_local", String.valueOf((long) mbLocal * 1024 * 1024));
        p.setProperty("keep_hours", String.valueOf(hours));
        p.setProperty("keep_max_mb", String.valueOf(keep));
        Config c = from(p);                       // throws before anything is written
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            p.store(out, "written by ClipSync MainActivity");
        }
        return c;
    }

    private static long parseLong(String s, String what) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a number");
        }
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a number");
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
