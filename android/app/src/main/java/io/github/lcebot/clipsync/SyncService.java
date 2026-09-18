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
    // keep-alive: a peer drops a silent client after 90 s. Cellular pings are spaced wider because
    // every one of them pulls the modem out of its idle state. Here and not in Link, because one
    // device has one radio: the heartbeat is per-device even though the interval is chosen from the
    // transport all its links share.
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
        // peerLabel, not peerName: once HELLO has arrived this is what the peer calls itself, which
        // is more use in the UI than the address we happened to reach it at. It falls back to the
        // address until then, so there is never a blank.
        publish("connected", null, c.via, c.lanPeer, c.peerLabel, String.valueOf(c.remote).replaceFirst("^[^/]*/", ""));
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
    /** One dialer per target, keyed by the target. Rebuilt when the configuration changes. */
    private final java.util.Map<String, Dialer> dialers = new java.util.LinkedHashMap<>();
    /**
     * The live links, keyed by the peer's node id.
     *
     * <p>Keyed by id and not by target because that is what makes a duplicate visible: two targets
     * can be two names for one machine, and the only moment that becomes knowable is when the second
     * handshake returns an id the map already holds.
     */
    private final java.util.Map<String, Link> byPeer = new java.util.concurrent.ConcurrentHashMap<>();
    private Thread heart;

    // sync state
    // volatile, all three: they are written from the clip worker and from up to N clipsync-push
    // threads, and read both under `lock` and (lastRemoteUri, in handleClip) outside it. With one
    // connection there was one writer; with several, a reader that misses a write re-applies a clip
    // this device just sent, which is how a two-peer ping-pong starts.
    private volatile String lastRemoteHash;     // content we last wrote into the local clipboard
    private volatile String lastRemoteUri;      // URI we last put on the clipboard (cheap loop check)
    private volatile String lastSentHash;       // content we last sent, to any peer (echo check)
    /**
     * How far into each peer's stream we have seen, by target.
     *
     * <p>One cursor per target, where there used to be one for the device. With several peers a
     * single number is not merely imprecise, it is meaningless: the peers have independent sequence
     * spaces, and a number from one of them tells another nothing about what we are missing.
     */
    private final java.util.Map<String, Long> seqByTarget = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The newest local clip, or null.
     *
     * <p><b>Not consumed.</b> It used to be taken by whoever flushed it first, which is correct for
     * one peer and silently wrong for several — the first connection to send it took it away from
     * the rest. Each link now records what it has delivered ({@code Link.sentHash}), so this stays
     * put until a newer clip replaces it, and a peer that connects a minute later still gets it.
     */
    private volatile Object pendingLocal;

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        // First line of every run. The id is per-process (see Node), so this is what ties every
        // later "connected" line to the session it belongs to — the thing the old persisted id used
        // to provide for free, and the only thing worth keeping from it.
        Logger.i("ClipSync " + Node.name() + ", node " + Node.shortId(Node.id())
                + " (protocol " + Connection.PROTOCOL_VERSION + ")");
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

        syncDialers();
        heart = new Thread(this::heartbeat, "clipsync-ping");
        heart.setDaemon(true);
        heart.start();
        setStatus("connecting");
        // root: whitelist + app-ops + standby bucket, so vendor battery managers leave us alone
        Thread ka = new Thread(() -> Logger.i(Root.keepAlive(getPackageName())), "clipsync-root");
        ka.setDaemon(true);
        ka.start();
        Logger.i("service started, " + targets(cfg));
    }

    /** How this device looks for peers, for one line of log: the two switches and what they hold. */
    private static String targets(Config cfg) {
        StringBuilder sb = new StringBuilder();
        if (!cfg.peers.isEmpty()) sb.append(String.join(", ", cfg.peers)).append(":").append(cfg.port);
        else sb.append("no addresses listed");
        if (cfg.discovery) sb.append(" + discovery (browse ").append(cfg.mdnsTimeoutMs).append(" ms)");
        return sb.toString();
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
        Logger.i("config reloaded: " + targets(cfg)
                + ", " + cfg.threads + " streams, files -> " + cfg.filesDir);
        abortTransfers(null, "configuration changed", null);
        Connection.forgetMdns();
        dropConnection();                 // the dialers reconnect with the new settings
        syncDialers();                    // ... and the set of targets may itself have changed
        wake();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (started) {
            clipboard.removePrimaryClipChangedListener(clipListener);
            unregisterReceiver(screenReceiver);
            try { connectivity.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            for (Dialer d : snapshotDialers()) d.cancel();
            synchronized (dialers) { dialers.clear(); }
            dropConnection();
            if (heart != null) heart.interrupt();
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
                resetBackoff();         // a new network is a new chance for every target at once
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

    /** Give every target its first retry back. A network appearing is good news for all of them. */
    private void resetBackoff() {
        for (Dialer d : snapshotDialers()) d.resetBackoff();
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
            abortTransfers(null, "superseded by a newer clip on the phone", h);
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

    /**
     * Put one clip on one link.
     *
     * <p>It no longer takes the clip out of the pending slot — {@link Link#deliver()} decides whether
     * this link still owes it, and the slot belongs to every link at once. What used to be
     * {@code flushPending}'s job of marking it sent is now two different marks: the per-link one in
     * Link, and {@code lastSentHash} here, which is the device saying "this content came from us" so
     * that any peer echoing it back is ignored.
     */
    private void sendClip(Link link, Object o) throws Exception {
        Connection c = link.connection();
        if (o instanceof Files.Ref) {
            Files.Ref f = (Files.Ref) o;
            long limit = limitFor(c);
            if (f.size > limit) {
                // Returning before lastSentHash is set, deliberately: a file we declined to offer is
                // not something this device has sent, and marking it would make a peer's own copy of
                // the same file look like our echo and be ignored.
                Logger.i("not offering " + f + ": over the " + (c.lanPeer ? "LAN" : "internet") + " limit (" + limit / (1024 * 1024) + " MB)");
                return;
            }
            lastSentHash = f.sha256;
            // two-step: OFFER the hash first; the bytes only go out if the PC answers WANT
            synchronized (offered) {
                offered.put(f.sha256, f);
                while (offered.size() > 8) offered.remove(offered.keySet().iterator().next());
            }
            c.sendJson(Connection.T_OFFER, header(f));
            Logger.i("local -> remote: offered " + f);
        } else {
            String text = (String) o;
            lastSentHash = Crypto.sha256Hex(text);
            JSONObject j = new JSONObject();
            j.put("seq", System.currentTimeMillis());
            j.put("mime", "text/plain");
            j.put("sha256", lastSentHash);
            j.put("data", text);
            c.send(Connection.T_CLIP, j.toString().getBytes(StandardCharsets.UTF_8));
            Logger.i("local -> remote (" + text.length() + " chars)");
        }
    }

    // ------------------------------------------------------------------ PC -> local clipboard
    private void onRemoteClip(Link l, JSONObject msg) {
        String text = msg.optString("data", null);
        if (text == null || !"text/plain".equals(msg.optString("mime", "text/plain"))) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > cfg.maxBytes) return;
        String h = Crypto.sha256Hex(text);
        if (!h.equals(msg.optString("sha256"))) return;
        advance(l, msg.optLong("seq", 0));
        synchronized (lock) {
            // Content hashes, so this still works with several peers: a clip we sent to A and had
            // relayed back by B is recognised by what it is, not by which link it arrived on.
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

    /**
     * Stop whatever is in flight (a newer clip supersedes it) and tell the peer that was carrying it.
     *
     * <p>{@code c} is null now at both call sites, which is the honest shape: a transfer knows which
     * connection it is running on ({@code Transfer.control}), so there is no need for the caller to
     * guess — and with several links a caller that guessed would tell the wrong peer.
     */
    private void abortTransfers(Connection c, String reason, String except) {
        for (Transfer t : new Transfer[]{upload, download}) {
            if (t == null || t.isAborted() || t.sha256.equals(except)) continue;
            t.abort();
            Logger.i("aborting " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + reason);
            Connection tell = c != null ? c : t.control;
            try {
                if (tell != null) tell.sendJson(Connection.T_ABORT, shaMsg(t.sha256).put("reason", reason));
            } catch (Exception ignored) {
            }
        }
    }

    /** The PC offers a file: re-use our cached copy (HAVE), refuse (SKIP), or ask for the chunks we miss (WANT) and pull them. */
    private void onOffer(Link l, JSONObject hdr) throws Exception {
        Connection c = l.connection();
        String sha = hdr.optString("sha256");
        String name = new java.io.File(hdr.optString("name", "clip")).getName();
        if (name.isEmpty()) name = "clip";
        long size = hdr.optLong("size", -1);
        long seq = hdr.optLong("seq", 0);
        boolean echo;
        // the cursor only advances once we actually have the file (HAVE / SKIP / download done), so
        // that after a dropped connection the peer's catch-up OFFER triggers the resume
        synchronized (lock) {
            echo = sha.equals(lastSentHash) || sha.equals(lastRemoteHash);
        }
        if (echo) {
            advance(l, seq);
            c.sendJson(Connection.T_HAVE, shaMsg(sha));
            return;
        }
        Uri cached = cache.get(sha);
        if (cached != null) {
            c.sendJson(Connection.T_HAVE, shaMsg(sha));
            advance(l, seq);
            synchronized (lock) {
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
            advance(l, seq);
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
        startDownload(l, p, 0);
    }

    private void startDownload(Link l, Files.Partial p, int attempt) {
        Connection c = l.connection();
        Transfer t = Transfer.download(this, cfg, c, p, (tr, complete) -> {
            if (download == tr) download = null;
            if (complete) {
                finishDownload(l, p);
                // the link that offered it is the link whose cursor moves
            } else if (!tr.isAborted() && l.isOpen() && attempt < DOWNLOAD_RETRIES) {
                Logger.i("retrying " + p + " (" + (attempt + 1) + "/" + DOWNLOAD_RETRIES + ")");
                startDownload(l, p, attempt + 1);
            } else {
                p.keep();          // resumed on the next OFFER of the same file
            }
        });
        download = t;
        t.start();
    }

    private void finishDownload(Link l, Files.Partial p) {
        try {
            Uri uri = p.finalizeFile();
            cache.put(p.sha256, uri, p.name, p.mime, p.size);
            advance(l, p.seq);
            synchronized (lock) {
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
    private void handleFrame(Link l, Connection.Frame f) throws Exception {
        Connection c = l.connection();
        switch (f.type) {
            case Connection.T_CLIP -> onRemoteClip(l, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_OFFER -> onOffer(l, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_WANT -> onWant(c, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_HAVE -> onHave(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_SKIP -> onSkip(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_ABORT -> onAbort(new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
            case Connection.T_PING -> c.send(Connection.T_PONG);
            case Connection.T_BYE -> {
                String why = new JSONObject(new String(f.payload, StandardCharsets.UTF_8)).optString("reason", "");
                Logger.i(c.peer + " said goodbye: " + (why.isEmpty() ? "no reason given" : why));
                // Marked, not just closed. Closing alone ends the session and the dialer redials on
                // its usual back-off — handing the peer back exactly the link it discarded, which is
                // the loop BYE exists to prevent. The dialer reads this and waits the long interval.
                l.markBye();
                l.close();
            }
            default -> { }
        }
    }

    /** Remember how far into a peer's stream we have seen. One cursor per target (see seqByTarget). */
    private void advance(Link l, long seq) {
        if (seq <= 0) return;
        seqByTarget.merge(l.target, seq, Math::max);
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
        // Every live link, not the one: a clip has to reach every peer. Each decides for itself
        // whether it has already sent this one, so calling them all is safe however often it happens.
        for (Link l : byPeer.values()) {
            if (!l.isOpen()) continue;
            // a short-lived thread per link, so the clipboard listener returns immediately and one
            // slow peer cannot hold up delivery to the others
            new Thread(() -> {
                try { l.deliver(); } catch (Exception e) { l.close(); }
            }, "clipsync-push").start();
        }
    }

    /** Close every live link. Screen off, network change, reload and shutdown all mean this. */
    private void dropConnection() {
        for (Link l : byPeer.values()) l.close();
        byPeer.clear();
        for (Dialer d : snapshotDialers()) {
            Link l = d.live;
            if (l != null) l.close();
        }
    }

    /**
     * Let go of the pending clip once every live link has it.
     *
     * <p><b>This is what keeps the radio asleep.</b> The old code consumed the clip on the first
     * flush, which cleared the slot as a side effect; making it a broadcast removed that, and the
     * gate every dialer waits on is {@code !screenOn && pendingLocal == null}. Without a release the
     * gate never closes again after the first copy of the session: screen off drops the links, the
     * gate passes at once, each dialer reconnects, runs a burst, returns "success" so skips its
     * back-off, and does it again — N sockets and N radio wake-ups in a tight loop, forever, with
     * the same clip re-sent every cycle because each new link starts with a clean {@code sentHash}.
     *
     * <p>Delivered to every live link is the right condition rather than "to one": a clip that has
     * reached the PC but not the tablet is not delivered. A target that cannot connect at all still
     * holds the slot open, which is the same thing the single-connection version did, and is the
     * behaviour that makes a clip survive a peer being briefly unreachable.
     */
    private void releasePending(String hash) {
        boolean all = true;
        int live = 0;
        for (Link l : byPeer.values()) {
            if (!l.isOpen()) continue;
            live++;
            if (!hash.equals(l.sentHash())) { all = false; break; }
        }
        if (!all || live == 0) return;
        synchronized (lock) {
            Object p = pendingLocal;
            if (p != null && hash.equals(hashOf(p))) pendingLocal = null;
        }
    }

    /** The live connection to show in the single-peer status file. Step (c) replaces this. */
    private Link anyLive() {
        for (Link l : byPeer.values()) if (l.isOpen()) return l;
        return null;
    }

    private java.util.List<Dialer> snapshotDialers() {
        synchronized (dialers) { return new ArrayList<>(dialers.values()); }
    }

    /** What a {@link Link} may ask of the service: state that belongs to the device, not to a peer. */
    private final Link.Owner linkOwner = new Link.Owner() {
        @Override public void onFrame(Link l, Connection.Frame f) throws Exception {
            handleFrame(l, f);
        }

        @Override public void onConnected(Link l) {
            Connection c = l.connection();
            String what = c.lanPeer ? "LAN link, file limit " + cfg.maxFileBytesLocal / (1024 * 1024) + " MB"
                                    : "internet link, file limit " + cfg.maxFileBytes / (1024 * 1024) + " MB";
            Logger.i("connected via " + c.via + " to " + c.peer + " ["
                    + Node.shortId(c.peerId) + "] (" + what + ")");
            setConnected(c);
        }

        @Override public long lastSeq(String target) {
            Long v = seqByTarget.get(target);
            return v == null ? 0 : v;
        }

        @Override public Object pendingClip() {
            return pendingLocal;
        }

        @Override public void send(Link l, Object clip) throws Exception {
            sendClip(l, clip);
        }

        @Override public void delivered(Link l, String hash) {
            releasePending(hash);
        }

        @Override public boolean transferBusy() {
            return SyncService.this.transferBusy();
        }

        @Override public boolean isRunning() { return running; }

        @Override public boolean isScreenOn() { return screenOn; }

        @Override public Config config() { return cfg; }
    };

    // ------------------------------------------------------------------ one dialer per target
    /**
     * Keeps one link to one target alive, with its own back-off.
     *
     * <p><b>Its own</b>, and that is the point of one thread per target rather than one loop over
     * them: a peer that is switched off must not slow the redial of one that is merely rebooting.
     * The single shared back-off this replaces did exactly that, and it was invisible while there
     * was only ever one target to punish.
     */
    private final class Dialer implements Runnable {
        /** A listed address, or {@link #MDNS} for the peer found on the local network. */
        final String target;
        volatile Link live;
        private volatile boolean stop;
        private volatile long backoff = BACKOFF_MIN_MS;
        /**
         * The link that won this target's peer, while a duplicate is being suppressed.
         *
         * <p>Dial-time suppression (§5): once a handshake has proved that two targets are one
         * machine, there is no reason to keep proving it. Held as the winning Link rather than as a
         * flag so it heals itself — the moment that link closes, this target starts dialling again,
         * which is what makes it a suppression rather than a permanent surrender.
         */
        private volatile Link deferredTo;
        /**
         * The back-off's own monitor, and the reason this class has two.
         *
         * <p>It used to wait on {@code lock}, which is also what {@code wake()} notifies on every
         * clipboard copy — so every copy cancelled every dialer's back-off. The log of that is
         * unambiguous: "retry in 60s" followed four seconds later by a full connect and handshake,
         * once per copy, forever. The two waits are asking different questions. The gate asks "is
         * there work", which a new clip answers; the back-off asks "has the world changed", which
         * only a network change or a shutdown answers.
         */
        private final Object retry = new Object();

        Dialer(String target) {
            this.target = target;
        }

        void cancel() {
            stop = true;
            synchronized (retry) { retry.notifyAll(); }
            Link l = live;
            if (l != null) l.close();
        }

        void resetBackoff() {
            synchronized (retry) {
                backoff = BACKOFF_MIN_MS;
                retry.notifyAll();      // a new network is a real new chance: do not sit out the wait
            }
        }

        @Override public void run() {
            while (running && !stop) {
                synchronized (lock) {
                    // Hold a link while the screen is on, or while something is waiting to go out —
                    // and never try without a network (the callback wakes us when one appears).
                    while (running && !stop && ((!screenOn && pendingLocal == null) || !hasNetwork)) {
                        try { lock.wait(60_000); } catch (InterruptedException ignored) {}
                        touchStatus();                   // keep status.json fresh for the UI
                    }
                }
                if (!running || stop) return;

                // Dial-time suppression (§5). Once a handshake has proved this target is a machine
                // another target already holds, stop proving it: a successful connect, handshake and
                // BYE once per back-off is the most expensive way possible to learn something we
                // already know. It lapses the moment the winning link closes, so this is a deferral
                // and not a surrender — if the other route dies, this one takes over.
                Link held = deferredTo;
                if (held != null && held.isOpen()) {
                    waitBackoff();
                    continue;
                }
                deferredTo = null;

                boolean burst = false;
                Link l = null;
                try {
                    l = open();
                    live = l;
                    Link winner = register(l);
                    if (winner == null) {
                        backoff = BACKOFF_MIN_MS;
                        burst = l.run();
                    } else {
                        // A duplicate of a peer another target already holds. Remember which link
                        // won, so the next round skips the dial entirely instead of connecting and
                        // handshaking only to be rejected again.
                        deferredTo = winner;
                        backoff = backoffMax();
                    }
                } catch (Connection.SelfConnection e) {
                    Logger.i(e.getMessage());
                    Connection.rememberSelf(e.target());
                    if (!MDNS.equals(target)) {
                        // A listed address that is this device stays wrong until the user edits it.
                        Logger.i("not retrying " + target + " until the configuration changes");
                        return;
                    }
                    // Discovery is different: finding ourselves says nothing about whether some
                    // other peer is also advertising, and giving up would leave the LAN path dead
                    // for the life of the process. Forget the cached address and try again later.
                    Connection.forgetMdns();
                    backoff = backoffMax();
                } catch (Exception e) {
                    Logger.i(target + ": " + e);
                } finally {
                    if (l != null) {
                        l.close();
                        if (l.peerId() != null) byPeer.remove(l.peerId(), l);
                        teardown(l);
                    }
                    live = null;
                    refreshStatus();
                }
                if (!running || stop) return;
                // A peer that said goodbye closed us on purpose — as a duplicate, most often.
                // Redialling straight away would hand it back exactly what it just discarded.
                if (l != null && l.saidBye()) backoff = backoffMax();
                else if (burst) continue;                // a burst ending is success, not a failure
                waitBackoff();
            }
        }

        private Link open() throws Exception {
            Network net = connectivity.getActiveNetwork();
            if (MDNS.equals(target)) return Link.viaMdns(SyncService.this, linkOwner, net);
            return Link.toPeer(SyncService.this, linkOwner, target, net);
        }

        private void waitBackoff() {
            if ((!screenOn && pendingLocal == null) || !hasNetwork) return;
            long wait = Math.min(backoff, backoffMax());
            Logger.i(target + ": retry in " + wait / 1000 + "s (" + (onLan ? "lan" : "mobile") + ")");
            // A deadline and a loop, not a bare wait(ms): a single wait returns on ANY notify, and
            // the wait it replaced was on the monitor a clipboard copy notifies. Waiting out the
            // remainder each time is what makes the logged interval the interval that is served.
            long until = System.currentTimeMillis() + wait;
            synchronized (retry) {
                for (long left; running && !stop && (left = until - System.currentTimeMillis()) > 0; ) {
                    long was = backoff;
                    try { retry.wait(left); } catch (InterruptedException ignored) { return; }
                    if (backoff != was) return;     // resetBackoff(): a genuinely new chance
                }
            }
            if (backoff == wait) backoff = Math.min(wait * 2, backoffMax());
            else if (backoff > backoffMax()) backoff = backoffMax();
        }
    }

    /**
     * The target name for "whatever local discovery finds".
     *
     * <p>An asterisk because it cannot appear in a host name, so this collides with nothing a user
     * can type — and <b>not</b> a NUL (\\u0000) sentinel, which was the first
     * choice and does not compile: Java resolves backslash-u escapes before lexing, and it does so
     * inside comments too, so writing one puts a real NUL into the source file rather than into the
     * string. Worth knowing before reaching for one again — including while writing the comment
     * that explains why not to, which is how this paragraph came to contain one.
     */
    private static final String MDNS = "*discovery*";

    /**
     * Claim this peer, or discover that we already hold it.
     *
     * <p>Two targets can be two names for one machine — a listed address and its mDNS
     * advertisement, or two listed addresses — and it is only here, with the handshake done and an
     * id in hand, that this becomes knowable.
     *
     * <p>Which link survives: <b>the older one</b>. Both were opened by this device, so §5's rules
     * 1 and 2 do not apply — rule 1 arbitrates by which peer address is on-link and rule 2 by which
     * node opened what, and both need the other end to have dialled us, which Android cannot yet do.
     * Rule 3 is the one that fits, and it is also the stable choice: the older link is the one that
     * is already carrying traffic.
     */
    private Link register(Link l) {
        String id = l.peerId();                         // never null: hello() refuses a peer with no id
        Link existing = byPeer.putIfAbsent(id, l);
        if (existing == null || existing == l) return null;
        // It died between the handshake and now. replace() and not put(): a third link may have
        // registered in the meantime, and overwriting it unconditionally would lose it from the map
        // while it went on running — invisible to the heartbeat, the broadcast and the status.
        if (!existing.isOpen() && byPeer.replace(id, existing, l)) return null;
        Logger.i(l.target + " is " + existing.target + " by another name [" + Node.shortId(id)
                + "] — closing the newer link");
        l.bye("duplicate");
        return false;
    }

    /**
     * After a link goes: stop whatever it was carrying, and keep what can be resumed.
     *
     * <p>Transfers are still one at a time for the whole device, so this only has to tell the one in
     * flight apart from nothing — but it does have to check whose it was, because with several links
     * a closing one must not abort a transfer another is running.
     */
    private void teardown(Link l) {
        Connection c = l.connection();
        Files.Ref unanswered = null;
        Transfer u = upload, d = download;
        // Only what this link was carrying. With one connection "no connection given" could mean
        // "mine"; with several it would mean "everyone's", and a target that merely failed to
        // connect would abort the transfer a different peer was happily running.
        if (d != null && d.control == c) { d.abort(); d.partial.keep(); download = null; }
        if (u != null && u.control == c) {
            if (!u.isAborted()) unanswered = u.ref;
            u.abort();
            upload = null;
        }
        // `offered` is deliberately NOT cleared here. It is what answers a WANT, it is keyed by hash
        // rather than by peer, and the same file is offered to every link — so clearing it when one
        // link dies would make the others' offers unanswerable. It is bounded at 8 and emptied by
        // HAVE / SKIP / ABORT, or wholesale when a newer clip supersedes everything.
        // Back into the pending slot only if nothing newer has taken it: a clip the user copied
        // while the transfer was dying is the one that should win.
        if (unanswered != null) synchronized (lock) { if (pendingLocal == null) pendingLocal = unanswered; }
    }

    /** Recompute the one-peer status file from however many links are live. Step (c) widens this. */
    private void refreshStatus() {
        if (!running) return;
        Link l = anyLive();
        if (l != null) setConnected(l.connection());
        else setStatus(screenOn ? "disconnected" : "idle", screenOn ? null : "screen off");
    }

    /**
     * Start a dialer for every target the configuration names, and stop the ones it no longer does.
     *
     * <p>Called at start-up and on every reload. Dialers for targets that survive a reload are left
     * running: a configuration change that adds a peer should not disconnect the others.
     */
    private void syncDialers() {
        java.util.Set<String> want = new java.util.LinkedHashSet<>(cfg.peers);
        if (cfg.discovery) want.add(MDNS);
        java.util.List<Dialer> cancel = new ArrayList<>();
        synchronized (dialers) {
            for (java.util.Iterator<java.util.Map.Entry<String, Dialer>> it = dialers.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<String, Dialer> e = it.next();
                if (!want.contains(e.getKey())) { cancel.add(e.getValue()); it.remove(); }
            }
            for (String t : want) {
                if (dialers.containsKey(t)) continue;
                Dialer d = new Dialer(t);
                dialers.put(t, d);
                Thread th = new Thread(d, "clipsync-dial-" + (MDNS.equals(t) ? "mdns" : t));
                th.setDaemon(true);
                th.start();
            }
        }
        for (Dialer d : cancel) d.cancel();
    }

    /**
     * One heartbeat for the device, not one per link.
     *
     * <p>A phone has one radio: N pingers would wake it N times to do the same job, and the
     * frozen-process detector and the status timestamp must not run N times either. The interval
     * still depends on the transport, and every link on a phone shares one transport, so there is
     * exactly one right answer to ask for.
     */
    private void heartbeat() {
        while (running) {
            long interval = onLan ? PING_WIFI_MS : PING_MOBILE_MS;
            long before = System.currentTimeMillis();
            try { Thread.sleep(interval); } catch (InterruptedException e) { return; }
            long overshoot = System.currentTimeMillis() - before - interval;
            if (overshoot > 20_000) {
                // a sleep that overshoots by this much means the process was frozen meanwhile:
                // the OS (or the ROM's battery manager) suspended us
                Logger.w("process was suspended for ~" + overshoot / 1000 + " s by the system — "
                        + "exempt ClipSync from battery optimisation / background limits (see the app)");
                suspendedOnce = true;
            }
            touchStatus();
            for (java.util.Map.Entry<String, Link> e : byPeer.entrySet()) {
                Link l = e.getValue();
                if (!l.isOpen()) { byPeer.remove(e.getKey(), l); continue; }
                // A failed ping is a dead link. Closing it is what makes its own dialer's blocking
                // recv() return, which is what gets it retried — so this is the path that notices a
                // peer that went away without closing, and it must also drop it from the map here
                // rather than leave it looking connected until the dialer's finally runs.
                try {
                    l.ping();
                } catch (Exception ex) {
                    l.close();
                    byPeer.remove(e.getKey(), l);
                }
            }
        }
    }
}
