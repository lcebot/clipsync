package io.github.lcebot.clipsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service (specialUse) that keeps one connection to the PC while the screen is on.
 * Clipboard access in background works because Entry hooks ClipboardService in system_server.
 */
public class SyncService extends Service {
    private static final String CHANNEL = "clipsync";
    // keep-alive: server drops a silent client after 90 s. Cellular pings are spaced wider
    // because every one of them pulls the modem out of its idle state.
    private static final long PING_WIFI_MS = 30_000, PING_MOBILE_MS = 45_000;
    // reconnect back-off (doubling), capped per transport. Both caps are 60 s for now — a
    // network change resets the back-off to the minimum immediately, so most reconnects don't
    // wait at all, and a shorter Wi-Fi cap only adds retries while the PC is offline.
    // The split is kept so Wi-Fi can be tuned independently later.
    private static final long BACKOFF_MIN_MS = 1_000, BACKOFF_MAX_WIFI_MS = 60_000, BACKOFF_MAX_MOBILE_MS = 60_000;

    // State for the UI lives in files/status.json (the UI runs in another process): see Status.
    private static volatile boolean suspendedOnce = false;
    private Object[] last = {"stopped", null, null, false, null, null};

    private void setStatus(String state) { setStatus(state, null); }
    private void setStatus(String state, String detail) { publish(state, detail, null, false, null, null); }
    private void setConnected(Connection c) {
        publish("connected", null, c.via, c.lanPeer, c.peerName, String.valueOf(c.remote).replaceFirst("^[^/]*/", ""));
    }
    private void publish(String state, String detail, String via, boolean lan, String host, String addr) {
        last = new Object[]{state, detail, via, lan, host, addr};
        Status.write(this, state, detail, via, lan, host, addr, suspendedOnce);
    }
    /** Re-write the same state with a fresh timestamp (liveness for the UI). */
    private void touchStatus() {
        Object[] l = last;
        Status.write(this, (String) l[0], (String) l[1], (String) l[2], (Boolean) l[3], (String) l[4], (String) l[5], suspendedOnce);
    }

    private volatile Config cfg;
    private FileCache cache;
    private ClipboardManager clipboard;
    private PowerManager power;
    private ConnectivityManager connectivity;

    private final Object lock = new Object();
    private volatile boolean running = true;
    private boolean started = false;
    private volatile boolean screenOn = true;
    private volatile boolean hasNetwork = true;  // default network validated (internet) or at least present
    private volatile boolean onLan = false;      // active network is Wi-Fi or Ethernet
    private volatile long backoff = BACKOFF_MIN_MS;
    private volatile Connection conn;           // current session, null when disconnected
    private Thread worker;

    // sync state
    private String lastRemoteHash;              // content we last wrote into the local clipboard
    private String lastRemoteUri;               // URI we last put on the clipboard (cheap loop check)
    private String lastSentHash;                // content we last sent to the PC
    private long lastServerSeq = 0;             // server seq we last saw (for HELLO catch-up)
    private Object pendingLocal;                // String (text) or Files.Ref not yet delivered

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        startForegroundQuiet();             // must happen promptly after startForegroundService()
        try {
            cfg = Config.load(this);
        } catch (RuntimeException e) {
            Logger.w("invalid config: " + e.getMessage() + " — open the app and fix it");
            setStatus("stopped", "invalid config");
            stopSelf();
            return;
        }
        started = true;
        cache = new FileCache(this);
        clipboard = getSystemService(ClipboardManager.class);
        power = getSystemService(PowerManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        clipboard.addPrimaryClipChangedListener(clipListener);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);   // system broadcasts only
        screenOn = power.isInteractive();

        readNetwork(connectivity.getNetworkCapabilities(connectivity.getActiveNetwork()));
        connectivity.registerDefaultNetworkCallback(netCallback);

        worker = new Thread(this::mainLoop, "clipsync-net");
        worker.setDaemon(true);
        worker.start();
        setStatus("connecting");
        // root: whitelist + app-ops + standby bucket, so vendor battery managers leave us alone
        Thread ka = new Thread(() -> Logger.i(Root.keepAlive(getPackageName())), "clipsync-root");
        ka.setDaemon(true);
        ka.start();
        Logger.i("service started, target " + (cfg.host.isEmpty() ? "(none)" : cfg.host + ":" + cfg.port)
                + (cfg.mdns ? " + mdns" : ""));
    }

    /** Clips pushed by the system_server hook (xposed.Entry): the ClipData rides in the intent. */
    public static final String ACTION_CLIP = "io.github.lcebot.clipsync.CLIP";
    /** Re-read files/clipsync.conf and reconnect, in place (no service restart, no process churn). */
    public static final String ACTION_RELOAD = "io.github.lcebot.clipsync.RELOAD";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started && intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            reload();
            return START_STICKY;
        }
        // the hook uses plain startService(): no startForeground obligation, no notification churn
        if (started && intent != null && ACTION_CLIP.equals(intent.getAction())) {
            ClipData cd = intent.getClipData();
            try {
                if (cd != null) clipWorker.execute(() -> handleClip(cd, "push"));
                else if (intent.getBooleanExtra("fetch", false)) clipWorker.execute(this::readLocalClip);   // clip too big for binder
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
        return START_STICKY;
    }

    private void reload() {
        Config next;
        try {
            next = Config.load(this);
        } catch (RuntimeException e) {
            Logger.w("reload: invalid config, keeping the old one (" + e.getMessage() + ")");
            return;
        }
        cfg = next;
        Logger.i("config reloaded: " + (cfg.host.isEmpty() ? "(no host)" : cfg.host + ":" + cfg.port)
                + " mode " + cfg.mode + (cfg.mdns ? ", browse " + cfg.mdnsTimeoutMs + " ms" : "")
                + ", " + cfg.threads + " streams, files -> " + cfg.filesDir);
        abortTransfers(conn, "configuration changed", null);
        Connection.forgetMdns();
        backoff = BACKOFF_MIN_MS;
        dropConnection();                 // the loop reconnects with the new settings
        wake();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (started) {
            clipboard.removePrimaryClipChangedListener(clipListener);
            unregisterReceiver(screenReceiver);
            try { connectivity.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            dropConnection();
            synchronized (lock) { lock.notifyAll(); }
            setStatus("stopped");
            Logger.i("service stopped");
        }
        clipWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundQuiet() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // LOW, not MIN: silent, but the notification stays visible. Some ROMs treat a foreground
        // service whose notification is collapsed away as freezable in the background.
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ClipSync", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ClipSync")
                .setContentText("Syncing clipboard with the PC")
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                screenOn = true;
                wake();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
                dropConnection();       // let the radio sleep
            }
        }
    };

    // ------------------------------------------------------------------ network state
    /** Derive hasNetwork/onLan from the default network; returns true if the LAN-ness changed. */
    private boolean readNetwork(NetworkCapabilities nc) {
        boolean lan = nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        boolean net = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean changed = lan != onLan || net != hasNetwork;
        onLan = lan;
        hasNetwork = net;
        return changed;
    }

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
            if (readNetwork(nc)) {
                Logger.i("network: " + (onLan ? "lan" : "mobile") + (hasNetwork ? "" : " (no internet)"));
                backoff = BACKOFF_MIN_MS;
                dropConnection();       // a session bound to the old network is dead anyway
                wake();
            }
        }

        @Override
        public void onLost(Network n) {
            hasNetwork = false;
            onLan = false;
            Logger.i("network lost");
            setStatus("no network");
            dropConnection();
        }
    };

    private long backoffMax() {
        return onLan ? BACKOFF_MAX_WIFI_MS : BACKOFF_MAX_MOBILE_MS;
    }

    // ------------------------------------------------------------------ local clipboard -> PC
    // the clipboard listener runs on the main thread; reading a 10 MB image there would freeze
    // MainActivity, so the work is handed to a single worker (serialised: order is preserved)
    private final ExecutorService clipWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-clip");
        t.setDaemon(true);
        return t;
    });

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        try {
            clipWorker.execute(this::readLocalClip);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // service is shutting down
        }
    };

    private void readLocalClip() {
        ClipData cd;
        try {
            cd = clipboard.getPrimaryClip();
        } catch (Throwable t) {
            Logger.w("clipboard listener: getPrimaryClip failed", t);
            return;
        }
        if (cd == null) {
            Logger.i("clipboard listener fired but getPrimaryClip() returned null (access denied in background?)");
            return;
        }
        handleClip(cd, "listener");
    }

    private long lastClipStamp = -1;

    /** Common path for the in-app listener and for clips pushed by the system_server hook. */
    private void handleClip(ClipData cd, String source) {
        try {
            if (cd.getItemCount() == 0) return;
            // In the foreground both the listener and the hook deliver the same clip. The system
            // stamps every clip; dropping the second delivery here saves a full re-read (and
            // re-hash) of a possibly 100 MB file.
            long stamp = cd.getDescription() != null ? cd.getDescription().getTimestamp() : 0;
            synchronized (lock) {
                if (stamp > 0 && stamp == lastClipStamp) {
                    Logger.i("clip (" + source + "): same clip already handled, skipped");   // proves the path is alive
                    return;
                }
                lastClipStamp = stamp;
            }
            // A clip can carry several items and each item text and/or a URI. Prefer the first
            // item that is a readable file (image copied from gallery/browser, file from a file
            // manager); otherwise the first non-empty text. A URI we cannot read is not sent as
            // its "content://..." string — that is useless on the PC.
            Object out = null;
            for (int i = 0; i < cd.getItemCount() && out == null; i++) {
                Uri uri = cd.getItemAt(i).getUri();
                if (uri == null) continue;
                if (uri.toString().equals(lastRemoteUri)) return;       // our own clip, nothing to do
                // hashed now with the widest limit; the link-specific limit is checked at OFFER time
                Files.Ref ref = Files.stat(this, uri, cfg.maxFileAny());
                if (ref != null) out = ref;
            }
            if (out == null) {
                for (int i = 0; i < cd.getItemCount() && out == null; i++) {
                    ClipData.Item it = cd.getItemAt(i);
                    CharSequence cs = it.getText() != null ? it.getText() : it.coerceToText(this);
                    if (cs == null || cs.length() == 0) continue;
                    if (it.getUri() != null && cs.toString().equals(it.getUri().toString())) continue;   // just the URI as text
                    String text = cs.toString();
                    if (text.getBytes(StandardCharsets.UTF_8).length > cfg.maxBytes) {
                        Logger.i("text too long, skipped (" + text.length() + " chars)");
                        continue;
                    }
                    out = text;
                }
            }
            if (out == null) {
                Logger.i("clip (" + source + "): nothing usable in it");
                return;
            }
            String h = hashOf(out);
            synchronized (lock) {
                if (h.equals(lastRemoteHash) || h.equals(lastSentHash)) return;   // echo / duplicate
                pendingLocal = out;
            }
            Logger.i("clip (" + source + "): " + (out instanceof Files.Ref ? out.toString() : "text " + ((String) out).length() + " chars"));
            // copying something new while a file is still moving: stop that transfer first
            abortTransfers(conn, "superseded by a newer clip on the phone", h);
            synchronized (offered) { offered.clear(); }
            wake();                                     // send now (reconnect if needed)
        } catch (Throwable t) {
            Logger.w("read clipboard failed", t);
        }
    }

    private static String hashOf(Object o) {
        return o instanceof Files.Ref ? ((Files.Ref) o).sha256 : Crypto.sha256Hex((String) o);
    }

    /** File-size limit for this session: LAN peers get the larger one. */
    private long limitFor(Connection c) {
        return c.lanPeer ? cfg.maxFileBytesLocal : cfg.maxFileBytes;
    }

    private void flushPending(Connection c) throws Exception {
        Object o;
        synchronized (lock) {
            o = pendingLocal;
            if (o == null) return;
            pendingLocal = null;
            lastSentHash = hashOf(o);
        }
        if (o instanceof Files.Ref) {
            Files.Ref f = (Files.Ref) o;
            long limit = limitFor(c);
            if (f.size > limit) {
                Logger.i("not offering " + f + ": over the " + (c.lanPeer ? "LAN" : "internet") + " limit (" + limit / (1024 * 1024) + " MB)");
                return;
            }
            // two-step: OFFER the hash first; the bytes only go out if the PC answers WANT
            synchronized (offered) {
                offered.put(f.sha256, f);
                while (offered.size() > 8) offered.remove(offered.keySet().iterator().next());
            }
            c.sendJson(Connection.T_OFFER, header(f));
            Logger.i("local -> remote: offered " + f);
        } else {
            String text = (String) o;
            JSONObject j = new JSONObject();
            j.put("seq", System.currentTimeMillis());
            j.put("mime", "text/plain");
            j.put("sha256", Crypto.sha256Hex(text));
            j.put("data", text);
            c.send(Connection.T_CLIP, j.toString().getBytes(StandardCharsets.UTF_8));
            Logger.i("local -> remote (" + text.length() + " chars)");
        }
    }

    // ------------------------------------------------------------------ PC -> local clipboard
    private void onRemoteClip(JSONObject msg) {
        String text = msg.optString("data", null);
        if (text == null || !"text/plain".equals(msg.optString("mime", "text/plain"))) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > cfg.maxBytes) return;
        String h = Crypto.sha256Hex(text);
        if (!h.equals(msg.optString("sha256"))) return;
        synchronized (lock) {
            lastServerSeq = Math.max(lastServerSeq, msg.optLong("seq", 0));
            if (h.equals(lastSentHash) || h.equals(lastRemoteHash)) return;
            lastRemoteHash = h;
            lastRemoteUri = null;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("clipsync", text));
        Logger.i("remote -> local (" + text.length() + " chars)");
    }

    // ---- files: OFFER (hash) -> WANT {ranges} / HAVE / SKIP -> chunks over parallel data connections ----
    /** Files we have offered and may be asked for (bounded; nothing is held in memory, only URIs). */
    private final java.util.LinkedHashMap<String, Files.Ref> offered = new java.util.LinkedHashMap<>();
    /** Files fully uploaded recently; a late re-WANT (lost stream on the PC side) is served from here. */
    private final java.util.LinkedHashMap<String, Files.Ref> sent = new java.util.LinkedHashMap<>();

    private static JSONObject header(Files.Ref f) throws Exception {
        JSONObject hdr = new JSONObject();
        hdr.put("seq", System.currentTimeMillis());
        hdr.put("name", f.name);
        hdr.put("mime", f.mime);
        hdr.put("size", f.size);
        hdr.put("sha256", f.sha256);
        return hdr;
    }

    private static JSONObject shaMsg(String sha) throws Exception {
        return new JSONObject().put("sha256", sha);
    }

    /** The transfers in flight (at most one each way). */
    private volatile Transfer upload, download;
    private static final int DOWNLOAD_RETRIES = 3;

    private static List<int[]> rangesOf(JSONArray a, int n) {
        List<int[]> out = new ArrayList<>();
        if (a == null) { out.add(new int[]{0, n}); return out; }
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r != null && r.length() == 2) out.add(new int[]{Math.max(0, r.optInt(0)), Math.min(n, r.optInt(1))});
        }
        return out;
    }

    private static JSONArray rangesJson(List<int[]> rs) {
        JSONArray a = new JSONArray();
        for (int[] r : rs) a.put(new JSONArray().put(r[0]).put(r[1]));
        return a;
    }

    /** Stop whatever is in flight (a newer clip supersedes it) and tell the PC. */
    private void abortTransfers(Connection c, String reason, String except) {
        for (Transfer t : new Transfer[]{upload, download}) {
            if (t == null || t.isAborted() || t.sha256.equals(except)) continue;
            t.abort();
            Logger.i("aborting " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + reason);
            try {
                if (c != null) c.sendJson(Connection.T_ABORT, shaMsg(t.sha256).put("reason", reason));
            } catch (Exception ignored) {
            }
        }
    }

    /** The PC offers a file: re-use our cached copy (HAVE), refuse (SKIP), or ask for the chunks we miss (WANT) and pull them. */
    private void onOffer(Connection c, JSONObject hdr) throws Exception {
        String sha = hdr.optString("sha256");
        String name = new java.io.File(hdr.optString("name", "clip")).getName();
        if (name.isEmpty()) name = "clip";
        long size = hdr.optLong("size", -1);
        long seq = hdr.optLong("seq", 0);
        boolean echo;
        // last_seq only advances once we actually have the file (HAVE / SKIP / download done), so
        // that after a dropped connection the PC's catch-up OFFER triggers the resume
        synchronized (lock) {
            echo = sha.equals(lastSentHash) || sha.equals(lastRemoteHash);
            if (echo) lastServerSeq = Math.max(lastServerSeq, seq);
        }
        if (echo) {
            c.sendJson(Connection.T_HAVE, shaMsg(sha));
            return;
        }
        Uri cached = cache.get(sha);
        if (cached != null) {
            c.sendJson(Connection.T_HAVE, shaMsg(sha));
            synchronized (lock) {
                lastServerSeq = Math.max(lastServerSeq, seq);
                lastRemoteHash = sha;
                lastRemoteUri = cached.toString();
            }
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), name, cached));
            Logger.i("offer: " + name + " -> already cached, re-used " + cached);
            cache.prune(cfg.keepHours, cfg.keepMaxBytes, cached);
            return;
        }
        long limit = limitFor(c);
        if (size < 0 || size > limit) {
            String why = size + " bytes > " + limit + " (" + (c.lanPeer ? "LAN" : "internet") + " limit)";
            c.sendJson(Connection.T_SKIP, shaMsg(sha).put("reason", why));
            synchronized (lock) { lastServerSeq = Math.max(lastServerSeq, seq); }
            Logger.i("offer: " + name + " -> skipped, " + why);
            return;
        }
        // a newer offer supersedes a download still running (the PC already stopped serving it)
        Transfer d = download;
        if (d != null && !d.sha256.equals(sha)) { d.abort(); d.partial.keep(); }
        Files.Partial p = Files.Partial.resume(this, sha);
        if (p == null) p = Files.Partial.create(this, cfg.relativePath, name, hdr.optString("mime", "application/octet-stream"), size, sha, seq);
        List<int[]> missing = p.missing();
        c.sendJson(Connection.T_WANT, shaMsg(sha).put("ranges", rangesJson(missing)));
        Logger.i("offer: " + name + " (" + size + " bytes) -> want " + (p.haveCount() == 0 ? "all" : (p.n - p.haveCount()) + "/" + p.n + " chunks (resume)"));
        startDownload(c, p, 0);
    }

    private void startDownload(Connection c, Files.Partial p, int attempt) {
        Transfer t = Transfer.download(this, cfg, c, p, (tr, complete) -> {
            if (download == tr) download = null;
            if (complete) {
                finishDownload(p);
            } else if (!tr.isAborted() && conn == c && attempt < DOWNLOAD_RETRIES) {
                Logger.i("retrying " + p + " (" + (attempt + 1) + "/" + DOWNLOAD_RETRIES + ")");
                startDownload(c, p, attempt + 1);
            } else {
                p.keep();          // resumed on the next OFFER of the same file
            }
        });
        download = t;
        t.start();
    }

    private void finishDownload(Files.Partial p) {
        try {
            Uri uri = p.finalizeFile();
            cache.put(p.sha256, uri, p.name, p.mime, p.size);
            synchronized (lock) {
                lastServerSeq = Math.max(lastServerSeq, p.seq);
                if (p.sha256.equals(lastSentHash) || p.sha256.equals(lastRemoteHash)) return;
                lastRemoteHash = p.sha256;
                lastRemoteUri = uri.toString();
            }
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), p.name, uri));
            Logger.i("remote -> local (" + p.mime + " " + p.name + " " + p.size + " bytes) saved as " + uri);
            cache.prune(cfg.keepHours, cfg.keepMaxBytes, uri);
        } catch (Exception e) {
            Logger.w("receive file failed: " + e.getMessage());
        }
    }

    /** The PC wants (part of) a file we offered, or one we still have: push the requested chunks. */
    private void onWant(Connection c, JSONObject msg) throws Exception {
        String sha = msg.optString("sha256");
        Transfer u = upload;
        if (u != null && u.upload && u.sha256.equals(sha) && !u.isAborted()) {
            Logger.i("want: " + sha.substring(0, 12) + " already uploading, ignored");
            return;
        }
        // the offer stays in `offered` until HAVE / SKIP / ABORT or a newer clip: the PC may ask
        // for (parts of) the same file again after a lost stream or a resume
        Files.Ref f;
        synchronized (offered) { f = offered.get(sha); if (f == null) f = sent.get(sha); }
        if (f == null) {
            Uri cachedUri = cache.get(sha);
            if (cachedUri != null) f = Files.stat(this, cachedUri, cfg.maxFileAny());
        }
        if (f == null || !f.sha256.equals(sha)) {
            Logger.w("want: " + sha.substring(0, Math.min(12, sha.length())) + " not available any more");
            c.sendJson(Connection.T_ABORT, shaMsg(sha).put("reason", "not available any more"));
            return;
        }
        if (u != null && !u.isAborted()) u.abort();          // a different file: the newer request wins
        final Files.Ref ref = f;
        Transfer t = Transfer.upload(this, cfg, c, f, rangesOf(msg.optJSONArray("ranges"), Connection.chunks(f.size)), (tr, complete) -> {
            if (upload == tr) upload = null;
            if (complete) {
                // done from our side: no longer "outstanding" (screen-off burst may end), but still
                // servable should the PC ask again for a lost stream
                synchronized (offered) {
                    offered.remove(ref.sha256);
                    sent.put(ref.sha256, ref);
                    while (sent.size() > 8) sent.remove(sent.keySet().iterator().next());
                }
            }
        });
        upload = t;
        t.start();
    }

    private void onHave(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("PC already has " + (f != null ? f.name : sha.substring(0, Math.min(12, sha.length()))) + ", nothing transferred");
    }

    private void onSkip(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("PC skipped " + (f != null ? f.name : sha.substring(0, Math.min(12, sha.length()))) + ": " + msg.optString("reason"));
    }

    private void onAbort(JSONObject msg) {
        String sha = msg.optString("sha256");
        synchronized (offered) { offered.remove(sha); }
        for (Transfer t : new Transfer[]{upload, download}) {
            if (t != null && t.sha256.equals(sha)) {
                t.abort();
                Logger.i("PC aborted " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + msg.optString("reason"));
            }
        }
    }

    /** One place for every frame type, used by both the normal loop and the screen-off burst. */
    private void handleFrame(Connection c, Connection.Frame f) throws Exception {
        switch (f.type) {
            case Connection.T_CLIP -> onRemoteClip(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_OFFER -> onOffer(c, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_WANT -> onWant(c, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_HAVE -> onHave(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_SKIP -> onSkip(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_ABORT -> onAbort(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_PING -> c.send(Connection.T_PONG);
            default -> { }
        }
    }

    private boolean transferBusy() {
        Transfer u = upload, d = download;
        synchronized (offered) {
            return !offered.isEmpty() || (u != null && !u.isAborted()) || (d != null && !d.isAborted());
        }
    }

    // ------------------------------------------------------------------ network loop
    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
        Connection c = conn;
        if (c != null) {
            // already connected: push from a short-lived thread so the listener returns fast
            new Thread(() -> {
                try { flushPending(c); } catch (Exception e) { dropConnection(); }
            }, "clipsync-push").start();
        }
    }

    private void dropConnection() {
        Connection c = conn;
        conn = null;
        if (c != null) c.close();
    }

    private void mainLoop() {
        while (running) {
            // Only hold a connection while the screen is on, or while something is pending —
            // and never try without a network (the callback wakes us when one appears).
            synchronized (lock) {
                while (running && ((!screenOn && pendingLocal == null) || !hasNetwork)) {
                    try { lock.wait(60_000); } catch (InterruptedException ignored) {}
                    touchStatus();                       // keep status.json fresh for the UI
                }
            }
            if (!running) break;

            final boolean lan = onLan;
            try (Connection c = new Connection(this, cfg, lan, connectivity.getActiveNetwork())) {
                JSONObject hello = new JSONObject();
                hello.put("v", Connection.PROTOCOL_VERSION);
                hello.put("device", Build.MODEL);
                hello.put("last_seq", lastServerSeq);
                hello.put("lan", c.lanPeer);
                c.send(Connection.T_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
                conn = c;
                backoff = BACKOFF_MIN_MS;
                String link = c.lanPeer ? "LAN link, file limit " + cfg.maxFileBytesLocal / (1024 * 1024) + " MB"
                                        : "internet link, file limit " + cfg.maxFileBytes / (1024 * 1024) + " MB";
                setConnected(c);
                Logger.i("connected via " + c.via + " to " + c.peer + " (" + link + ")");
                flushPending(c);
                if (!screenOn) {
                    // woke up only to deliver a pending clip: give the server a moment to answer
                    // (WANT for an offered file, or anything newer), then drop the link so the
                    // radio can sleep. While an offer is open or a transfer is running the
                    // window stays open (hard cap 2 min); otherwise 1 s of silence ends it.
                    long until = System.currentTimeMillis() + 120_000;
                    try {
                        while (System.currentTimeMillis() < until) {
                            c.setSoTimeout(transferBusy() ? 10_000 : 1_000);
                            handleFrame(c, c.recv());
                        }
                    } catch (Exception ignored) {
                        // read timeout: the burst is over
                    }
                    continue;   // try-with-resources closes c; finally clears conn
                }

                Thread pinger = new Thread(() -> {
                    try {
                        long interval = lan ? PING_WIFI_MS : PING_MOBILE_MS;
                        while (conn == c) {
                            long before = System.currentTimeMillis();
                            Thread.sleep(interval);
                            long gap = System.currentTimeMillis() - before - interval;
                            if (gap > 20_000) {
                                // a sleep that overshoots by this much means the process was frozen
                                // meanwhile: the OS (or the ROM's battery manager) suspended us
                                Logger.w("process was suspended for ~" + gap / 1000 + " s by the system — "
                                        + "exempt ClipSync from battery optimisation / background limits (see the app)");
                                suspendedOnce = true;
                            }
                            touchStatus();
                            if (conn == c) c.send(Connection.T_PING);
                        }
                    } catch (Exception ignored) {
                    }
                }, "clipsync-ping");
                pinger.setDaemon(true);
                pinger.start();

                while (running && conn == c) {
                    handleFrame(c, c.recv());
                }
            } catch (Exception e) {
                Logger.i("disconnected: " + e);
            } finally {
                if (conn != null) dropConnection();
                // transfers die with the control connection; partial data stays on disk.
                // An unfinished upload / unanswered offer is offered again on the next connection,
                // and the PC then asks only for the chunks it is still missing.
                Files.Ref unanswered = null;
                Transfer u = upload, d = download;
                if (d != null) { d.abort(); d.partial.keep(); download = null; }
                if (u != null) { if (!u.isAborted()) unanswered = u.ref; u.abort(); upload = null; }
                synchronized (offered) {
                    for (Files.Ref r : offered.values()) unanswered = r;
                    offered.clear();
                }
                if (unanswered != null) synchronized (lock) { if (pendingLocal == null) pendingLocal = unanswered; }
                if (running) setStatus(screenOn ? "disconnected" : "idle", screenOn ? null : "screen off");
            }

            if (!running) break;
            if ((screenOn || pendingLocal != null) && hasNetwork) {
                long wait = Math.min(backoff, backoffMax());
                Logger.i("retry in " + wait / 1000 + "s (" + (onLan ? "lan" : "mobile") + ")");
                setStatus("disconnected", "retry in " + wait / 1000 + " s");
                synchronized (lock) {
                    try { lock.wait(wait); } catch (InterruptedException ignored) {}
                }
                if (backoff == wait) backoff = Math.min(wait * 2, backoffMax());   // unless a network change reset it
                else if (backoff > backoffMax()) backoff = backoffMax();
            }
        }
    }
}
