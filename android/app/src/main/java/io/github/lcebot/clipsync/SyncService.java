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
import android.os.SystemClock;

import com.google.android.material.color.MaterialColors;

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
    /**
     * How long a dialer waits after a screen-off delivery burst before trying again.
     *
     * <p>Not the ordinary minimum: a burst ends in success, so the doubling ladder does not apply,
     * but the gate that should close afterwards only closes once every live peer has the clip. When
     * it does not, this is the only thing standing between the device and a connect-per-second while
     * it is asleep. Thirty seconds still delivers promptly to a peer that comes back, and costs two
     * wake-ups a minute instead of sixty.
     */
    private static final long BURST_RETRY_MS = 30_000;
    // reconnect back-off (doubling), capped per transport. Both caps are 60 s for now — a
    // network change resets the back-off to the minimum immediately, so most reconnects don't
    // wait at all, and a shorter Wi-Fi cap only adds retries while the PC is offline.
    // The split is kept so Wi-Fi can be tuned independently later.
    private static final long BACKOFF_MIN_MS = 1_000, BACKOFF_MAX_WIFI_MS = 60_000, BACKOFF_MAX_MOBILE_MS = 60_000;

    // State for the UI lives in files/status.json (the UI runs in another process): see Status.
    /**
     * The heartbeat has caught this process being frozen <em>while the device was awake</em>.
     *
     * <p>The qualifier is the whole meaning of the flag, and it was missing: the check looked at the
     * wall clock alone, so every deep sleep set it. It drives the app's battery card, which tells the
     * user the system is still freezing ClipSync despite the exemption — advice that is right for a
     * freeze the device was awake through and wrong for the one that happens every time the phone is
     * locked, where being suspended is what the service asks for by idling. See {@link #heartbeat()}.
     */
    private static volatile boolean suspendedOnce = false;
    private volatile String lastState = "stopped", lastDetail;

    private void setStatus(String state) { setStatus(state, null); }

    private void setStatus(String state, String detail) {
        lastState = state;
        lastDetail = detail;
        writeStatus();
    }

    /**
     * Work out what the device is doing, from what it is actually doing.
     *
     * <p>Five states, and the reason they are distinct is that the answer to "what do I do now"
     * differs for each: *Stopped* needs a button pressed, *No network* needs the network fixed,
     * *Idle* needs nothing at all. The old *Disconnected* is folded into *Connecting…*: with one
     * connection the difference between "never connected" and "connected, then lost" carried the
     * retry information, and with several it carries none — which target failed and why is per
     * target now, and lives in the sheet.
     */
    private void refreshStatus() {
        if (!running) { setStatus("stopped"); return; }
        if (!hasNetwork) { setStatus("no network"); return; }
        if (!byPeer.isEmpty()) { setStatus("connected"); return; }
        if (!screenOn) { setStatus("idle", "screen off"); return; }
        setStatus("connecting");
    }

    /**
     * Re-derive and re-write the status: liveness for the UI, and a fresh answer.
     *
     * <p>It re-derives rather than re-writing what was last said, and that is not an optimisation to
     * skip. The state is computed from volatiles that change without anyone calling in here —
     * `screenOn`, `hasNetwork`, `byPeer` — so re-writing the old string publishes a claim that has
     * already stopped being true. Two of those were visible: the chip stuck on *Connecting…* for the
     * whole time the screen was off, because nothing recomputed after SCREEN_OFF when no link had
     * been up to die; and `Connected (0)`, from a stale "connected" written beside an emptied peer
     * map. Deriving here means every caller of this is also a state transition, which is exactly
     * what it should be.
     */
    private void touchStatus() {
        refreshStatus();
    }

    private void writeStatus() {
        List<Status.Peer> peers = new ArrayList<>();
        for (Link l : byPeer.values()) {
            if (!l.isOpen()) continue;
            Connection c = l.connection();
            // peerLabel, not peerName: once HELLO has arrived this is what the peer calls itself,
            // which is more use than the address we happened to reach it at. It falls back to the
            // address until then, so there is never a blank.
            peers.add(Status.peer(c.peerId, c.peerLabel, c.peerType, c.via,
                    String.valueOf(c.remote).replaceFirst("^[^/]*/", ""), c.lanPeer));
        }
        // Everything configured that is not up, with its last reason. Three addresses of which one
        // is failing is invisible in "Connected (2)", and that is exactly the thing someone opens
        // the sheet to find out.
        List<Status.Target> targets = new ArrayList<>();
        for (Dialer d : snapshotDialers()) {
            Link l = d.live;
            if (l != null && l.isOpen()) continue;
            String name = isMdns(d.target) ? instanceOf(d.target) : d.target;
            // Asked of the live map, not of what this dialler last wrote down.
            //
            // A dialler knows only about its own link, and since this device started accepting there
            // is a second way for its peer to be connected: the peer dials *us*. A phone that went
            // idle and then woke does exactly that — so it appears under "on this network" while the
            // dialler that used to reach it is still holding the words "Idle — screen off", and goes
            // on holding them until it next wakes, dials, loses the dedup and rewrites itself. Up to
            // a minute of the sheet saying a device is both connected and not.
            //
            // This is the same rule the state string already follows and this list did not: derive
            // it, never remember it. The peer map is the truth about who is connected; a dialler's
            // memory is only the truth about its own last attempt.
            Link byOther = d.lastPeerId == null ? null : byPeer.get(d.lastPeerId);
            if (byOther != null && byOther.isOpen()) {
                targets.add(Status.target(name, getString(R.string.peer_same_as, byOther.target),
                        Status.Why.NOTED));
                continue;
            }
            targets.add(Status.target(name, d.lastError, d.lastWhy));
        }
        // Discovery itself, when it is on and has nothing to show for it. Without this the sheet is
        // simply empty in exactly the case someone opens it to understand: the switch is on, no peer
        // has been found, and there is no target row to carry the reason because there is no target.
        String searching = discoveryState;
        // Waiting, not a fault: browsing and finding nothing is a fact about the network, not a
        // failure of this device, and the three ordinary causes are all outside it.
        if (searching != null) {
            targets.add(Status.target(getString(R.string.target_discovery), searching, Status.Why.WAITING));
        }
        Status.write(this, lastState, lastDetail, suspendedOnce, peers, targets);
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
    private Thread browser;

    // sync state
    // volatile, all three: they are written from the clip worker and from up to N clipsync-push
    // threads, and read both under `lock` and (lastRemoteUri, in handleClip) outside it. With one
    // connection there was one writer; with several, a reader that misses a write re-applies a clip
    // this device just sent, which is how a two-peer ping-pong starts.
    private volatile String lastRemoteHash;     // content we last wrote into the local clipboard
    private volatile String lastRemoteUri;      // URI we last put on the clipboard (cheap loop check)
    /**
     * Content this device has sent recently, so a peer echoing it back is recognised and ignored.
     *
     * <p><b>A set, not a slot.</b> One field was enough while there was one peer: send A, A comes
     * back, drop it. With three nodes it is not — send A to peer 1, then relay B, and the slot now
     * holds B, so peer 1's echo of A is no longer recognised and is written back to the clipboard.
     * Making the field volatile fixed the visibility half of that and left the structural half; this
     * is the structural half.
     *
     * <p>Bounded at sixteen and ordered by insertion, so it forgets the oldest rather than growing.
     * Sixteen is far more than the number of clips that can be in flight and small enough that a
     * linear scan never matters.
     */
    private final java.util.Set<String> sentHashes = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) {
                    return size() > 16;
                }
            });

    private void markSent(String hash) {
        if (hash == null) return;
        synchronized (sentHashes) { sentHashes.add(hash); }
    }

    private boolean wasSentByUs(String hash) {
        synchronized (sentHashes) { return sentHashes.contains(hash); }
    }
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
    /**
     * When the clip now in the slot <b>first</b> went into it, for {@link #worthWaking()}.
     *
     * <p>First, not last, and that distinction is what makes {@link #PENDING_WAKE_MS} a bound at all.
     * A file is released from the slot as soon as every link has been <em>offered</em> it, and put
     * back by {@link #teardown} when the transfer that followed died with the file unanswered — so a
     * file too large for the link it is crossing cycles through the slot indefinitely. Stamping the
     * re-entry with the current time restarted the five-minute window on every cycle, which meant a
     * clip that could never be delivered kept the radio awake for as long as it stayed on the
     * clipboard. {@link #pendingHash} is how a re-entry is told from a new clip.
     */
    private volatile long pendingSince;
    /** The content in the slot, so {@link #teardown} can tell "still this one" from "a new one". */
    private volatile String pendingHash;
    /**
     * The content already reported as stuck, so the diagnosis is one line per clip.
     *
     * <p>Kept past the release of the slot, and past the clip itself: the question "have I said this"
     * is about the content, and the alternative is a warning once per heartbeat for as long as the
     * situation lasts — which is precisely the noise that made the last one useless.
     */
    private volatile String staleReported;
    /**
     * How long an undelivered clip may keep the radio awake with the screen off.
     *
     * <p>Without a bound this is a permanent cost, not a transient one: the slot is released only
     * when a live link has taken the clip, so with no peer reachable at all it never clears, the
     * screen-off gate never closes, and every dialer goes on dialling — once a minute each once the
     * back-off ladder tops out, for as long as the phone is locked. That is not the old tight loop,
     * but it is a peer that is switched off draining the phone all night.
     *
     * <p>The clip is <b>not</b> discarded when this expires. It stops being a reason to wake the
     * radio, and is still delivered the moment a link comes up for any other reason — which is the
     * honest split: undelivered is not the same as unwanted.
     *
     * <p>It is also the moment the device has learned something worth saying, so it is where
     * {@link #reportStalePending()} speaks. And it was reachable only in the simple case until
     * {@link #pendingSince} started measuring from the clip's <em>first</em> entry into the slot: a
     * file that re-enters on every failed transfer used to reset the window and so never expire.
     */
    private static final long PENDING_WAKE_MS = 5 * 60_000;

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
        startListening();
        heart = new Thread(this::heartbeat, "clipsync-ping");
        heart.setDaemon(true);
        heart.start();
        browser = new Thread(this::discovery, "clipsync-browse");
        browser.setDaemon(true);
        browser.start();
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

    /**
     * Get the service running on whatever the configuration now says.
     *
     * <p>Two branches, and which one applies is not the caller's business: a running service takes a
     * reload in place — no restart, no process churn, no reconnect storm — and one that was never
     * started has to be started. Here rather than in an Activity because pairing reaches it from two
     * screens now, and a second copy would be a second thing to keep in step.
     *
     * @return true if this was a cold start, which is the only case with anything to wait for
     */
    public static boolean startOrReload(Context ctx) {
        Intent svc = new Intent(ctx, SyncService.class);
        if (Status.read(ctx).alive()) {
            ctx.startService(svc.setAction(ACTION_RELOAD));
            return false;
        }
        ctx.startForegroundService(svc);
        return true;
    }

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
        // The size limits may be exactly what just changed, so a clip already reported as stuck
        // deserves to be judged again rather than stay silently written off.
        staleReported = null;
        Connection.forgetLearned();
        discovered.clear();               // the browse repopulates it; a stale instance is worse than none
        dropConnection();                 // the dialers reconnect with the new settings
        syncDialers();                    // ... and the set of targets may itself have changed
        startListening();                 // the port or the discovery switch may have changed too
        rebrowse();
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
            stopListening();
            dropConnection();
            if (heart != null) heart.interrupt();
            if (browser != null) browser.interrupt();
            synchronized (lock) { lock.notifyAll(); }
            setStatus("stopped");
            Logger.i("service stopped");
        }
        clipWorker.shutdownNow();
        pushWorker.shutdownNow();
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
                // The shade tints the small icon with this. Read through the dynamic overlay rather
                // than from this Service's own theme: a Service never passes through the Activity
                // overlay that applies the system palette, so the theme alone would give the app's
                // fallback blue on a device showing a green palette everywhere else.
                .setColor(MaterialColors.getColor(ClipSyncApp.themed(this),
                        androidx.appcompat.R.attr.colorPrimary, 0))
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
                // Like a network arriving: the user picking the phone up is a real new chance, and
                // without this a target that had climbed to a 60 s back-off makes them wait it out
                // while they are looking at the screen. wake() alone cannot do it — it notifies the
                // gate, deliberately not the back-off.
                resetBackoff();
                rebrowse();             // the LAN may be a different one since the screen went off
                wake();
                refreshStatus();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
                // On a worker, because saying goodbye is a socket write and this is the main thread.
                // Worth saying: a link that simply closes leaves the peer unable to tell a device
                // that went to sleep from one that crashed, for as long as its read timeout — and
                // the two call for opposite responses, redial soon versus leave it alone.
                try {
                    pushWorker.execute(() -> { goIdle(); refreshStatus(); });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    dropConnection();   // shutting down: the close alone still ends the links
                    refreshStatus();    // ... and say so, even when there was no link to lose
                }
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
                // A different network is a different LAN with different peers on it, so what was
                // discovered on the old one is not merely stale, it is about somewhere else.
                discovered.clear();
                rebrowse();
                wake();
            }
        }

        @Override
        public void onLost(Network n) {
            hasNetwork = false;
            onLan = false;
            Logger.i("network lost");
            // Only the links that were on it. With one connection "the network went" and "the
            // connection is dead" were the same statement; with several they are not, and a link
            // riding a network that is still up has no reason to be torn down. Sockets are bound to
            // the network they were dialled on (Connection.network), which is what makes the
            // question answerable at all.
            //
            // Close first, then publish: the other order writes "no network" beside a peer list that
            // is about to be emptied, and a sheet opened in that window lists dead peers as live.
            dropConnection(n);
            discovered.clear();     // that LAN's peers are not reachable from wherever we are next
            refreshStatus();
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
                if (h.equals(lastRemoteHash) || wasSentByUs(h)) return;   // echo / duplicate
                pendingLocal = out;
                // Unconditionally, even for content that was in the slot before: the user copying
                // something is a deliberate new attempt and deserves the full window. Only teardown's
                // re-entry preserves the original stamp.
                pendingHash = h;
                pendingSince = System.currentTimeMillis();
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
     * Link, and {@link #markSent} here, which is the device saying "this content came from us" so
     * that any peer echoing it back is ignored.
     */
    private void sendClip(Link link, Object o) throws Exception {
        Connection c = link.connection();
        if (o instanceof Files.Ref) {
            Files.Ref f = (Files.Ref) o;
            long limit = limitFor(c);
            if (f.size > limit) {
                // Returning before it is marked sent, deliberately: a file we declined to offer is
                // not something this device has sent, and marking it would make a peer's own copy of
                // the same file look like our echo and be ignored.
                Logger.i("not offering " + f + ": over the " + (c.lanPeer ? "LAN" : "internet") + " limit (" + limit / (1024 * 1024) + " MB)");
                return;
            }
            markSent(f.sha256);
            // two-step: OFFER the hash first; the bytes only go out if the peer answers WANT
            synchronized (offered) {
                offered.put(f.sha256, f);
                while (offered.size() > 8) offered.remove(offered.keySet().iterator().next());
            }
            c.sendJson(Connection.T_OFFER, header(f));
            Logger.i("local -> remote: offered " + f);
        } else {
            String text = (String) o;
            String h = Crypto.sha256Hex(text);
            markSent(h);
            JSONObject j = new JSONObject();
            j.put("seq", System.currentTimeMillis());
            j.put("mime", "text/plain");
            j.put("sha256", h);
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
            // Content hashes, so this works whichever link it arrived on: a clip we sent to A and had
            // relayed back by B is recognised by what it is, not by where it came from.
            if (wasSentByUs(h) || h.equals(lastRemoteHash)) return;
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
    /**
     * The transfers in flight — at most one each way for the whole device, still.
     *
     * <p>Atomic references, and installed with {@code getAndSet}, because "at most one" stopped being
     * enforced by there being one peer. Two dialer threads can reach {@code onWant} for different
     * peers at the same moment, both pass a plain null-check, and both assign: the loser's eight
     * worker threads keep pushing bytes at a peer nobody is tracking, its completion callback never
     * matches, and neither {@code teardown} nor {@code abortTransfers} can see it to stop it. A
     * volatile field makes that race visible; it does not make it safe.
     */
    private final java.util.concurrent.atomic.AtomicReference<Transfer> upload = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<Transfer> download = new java.util.concurrent.atomic.AtomicReference<>();
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
        for (Transfer t : new Transfer[]{upload.get(), download.get()}) {
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
            echo = wasSentByUs(sha) || sha.equals(lastRemoteHash);
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
        // a newer offer supersedes a download still running (the peer already stopped serving it)
        Transfer d = download.get();
        if (d != null && !d.sha256.equals(sha)) { d.abort(); d.partial.keep(); }
        // One Partial per hash for the whole device, from a registry rather than from the disk.
        // Two peers offering the same file is the normal way a relayed clip arrives, and both would
        // otherwise create their own: two pending MediaStore rows, and two instances writing the same
        // chunk map over each other until whichever finalises second fails its hash check.
        Files.Partial p = partialFor(sha, name, hdr.optString("mime", "application/octet-stream"), size, seq);
        if (p == null) {
            c.sendJson(Connection.T_SKIP, shaMsg(sha).put("reason", "cannot open a file for it"));
            return;
        }
        List<int[]> missing = p.missing();
        if (missing.isEmpty()) {
            // Already complete on disk — ask for nothing. An empty range list means "everything" to
            // the peer, so sending it would re-stream the whole file to be discarded chunk by chunk.
            finishDownload(l, p);
            c.sendJson(Connection.T_HAVE, shaMsg(sha));
            return;
        }
        c.sendJson(Connection.T_WANT, shaMsg(sha).put("ranges", rangesJson(missing)));
        Logger.i("offer: " + name + " (" + size + " bytes) -> want " + (p.haveCount() == 0 ? "all" : (p.n - p.haveCount()) + "/" + p.n + " chunks (resume)"));
        // Only one end opens the data connections (Connection.drivesTransfer). When it is not this
        // one, the WANT above is the whole of our part: the peer pushes the chunks over connections
        // it opens, and serveData() receives them.
        if (c.drivesTransfer()) startDownload(l, p, 0);
    }

    /**
     * The one {@link Files.Partial} for this hash, created on first sight and shared after.
     *
     * <p>Keyed by hash because the file is: {@code files/partial/<sha>.json} was always the chunk
     * map's path, so two instances for one hash were always two writers of one file. With one peer
     * that could not happen; with several it is the common case.
     */
    private final java.util.Map<String, Files.Partial> partials = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @return the shared Partial, or null when one cannot be made — a MediaStore insert that fails
     *         because the configured path is gone or storage is full. Caught here rather than thrown,
     *         because the caller can answer SKIP and keep the link: letting it out of
     *         {@code handleFrame} would tear down a working session over one undeliverable file, and
     *         the peer would learn nothing about why. The reason goes to the log; the peer gets a
     *         refusal it can act on.
     */
    private Files.Partial partialFor(String sha, String name, String mime, long size, long seq) {
        Files.Partial p = partials.get(sha);
        if (p != null) return p;
        synchronized (partials) {
            p = partials.get(sha);
            if (p != null) return p;
            p = Files.Partial.resume(this, sha);
            if (p == null) try {
                p = Files.Partial.create(this, cfg.relativePath, name, mime, size, sha, seq);
            } catch (Exception e) {
                Logger.w("cannot open a file for " + name + " under " + cfg.relativePath + ": " + e);
                return null;
            }
            if (p != null) partials.put(sha, p);
            return p;
        }
    }

    private void startDownload(Link l, Files.Partial p, int attempt) {
        Connection c = l.connection();
        Transfer t = Transfer.download(this, cfg, c, p, (tr, complete) -> {
            download.compareAndSet(tr, null);
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
        Transfer displaced = download.getAndSet(t);
        if (displaced != null && displaced != t) displaced.abort();   // never leave one running untracked
        t.start();
    }

    /** @param l the link the file came in on, or null when a peer pushed it over data connections */
    private void finishDownload(Link l, Files.Partial p) {
        try {
            partials.remove(p.sha256);              // the file exists now; a later OFFER hits the cache
            Uri uri = p.finalizeFile();
            cache.put(p.sha256, uri, p.name, p.mime, p.size);
            if (l != null) advance(l, p.seq);
            synchronized (lock) {
                if (wasSentByUs(p.sha256) || p.sha256.equals(lastRemoteHash)) return;
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
        if (!c.drivesTransfer()) {
            // The other end opens the streams on this link (Connection.drivesTransfer), so there is
            // nothing to start: it will PULL, and servePull() answers. Exactly what the PC does in
            // this position, and for the same reason.
            Logger.i("want: " + shortSha(sha) + " — waiting for " + c.peerLabel + "'s data connections");
            return;
        }
        Transfer u = upload.get();
        if (u != null && u.upload && u.sha256.equals(sha) && !u.isAborted()) {
            Logger.i("want: " + shortSha(sha) + " already uploading, ignored");
            return;
        }
        Files.Ref f = refFor(sha);
        if (f == null) {
            Logger.w("want: " + shortSha(sha) + " not available any more");
            c.sendJson(Connection.T_ABORT, shaMsg(sha).put("reason", "not available any more"));
            return;
        }
        if (u != null && !u.isAborted()) u.abort();          // a different file: the newer request wins
        final Files.Ref ref = f;
        Transfer t = Transfer.upload(this, cfg, c, f, rangesOf(msg.optJSONArray("ranges"), Connection.chunks(f.size)), (tr, complete) -> {
            upload.compareAndSet(tr, null);
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
        Transfer displaced = upload.getAndSet(t);
        if (displaced != null && displaced != t) displaced.abort();
        t.start();
    }

    /**
     * A file we can serve, wherever it is: still offered, recently sent, or in the cache.
     *
     * <p>All three are needed and none of them is a fallback for the others. An offer stays
     * answerable until HAVE/SKIP/ABORT because a lost stream is re-asked for; a sent file stays
     * answerable because the far end may have lost its last chunk; and the cache answers a peer that
     * asks for something this device received rather than originated — which is how a file reaches a
     * third device at all.
     */
    private Files.Ref refFor(String sha) {
        Files.Ref f;
        synchronized (offered) { f = offered.get(sha); if (f == null) f = sent.get(sha); }
        if (f == null) {
            Uri cached = cache.get(sha);
            if (cached != null) f = Files.stat(this, cached, cfg.maxFileAny());
        }
        return f != null && f.sha256.equals(sha) ? f : null;
    }

    // ---- inbound data connections: the peer opened the link, so the peer drives the transfer ----
    /**
     * How many data connections are currently pushing each hash at us.
     *
     * <p>Counted from the moment a connection <b>opens</b> rather than from its first chunk, and
     * that is not a detail: several streams carry one file, and a fast one finishing before a slow
     * one has sent anything would otherwise look like "every stream closed, file incomplete" and
     * throw away a transfer that is going perfectly well.
     */
    private final java.util.Map<String, Integer> pushing = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One data connection a peer opened to us: it will push the chunks of a file we asked for, or
     * pull the chunks of one we offered.
     *
     * <p>Which of the two is not declared — it is whichever frame arrives first — and it does not
     * need to be: a connection that sends PULL is pulling and one that sends CHUNK is pushing, and
     * nothing else is accepted.
     */
    private void serveData(Connection c, JSONObject hello) throws Exception {
        String sha = hello.optString("sha256");
        Files.Partial p = partials.get(sha);
        boolean counted = p != null;
        if (counted) pushing.merge(sha, 1, Integer::sum);
        try {
            while (true) {
                Connection.Frame f = c.recv();
                if (f.type == Connection.T_PULL) {
                    servePull(c, sha, new JSONObject(new String(f.payload, StandardCharsets.UTF_8)));
                } else if (f.type == Connection.T_CHUNK) {
                    if (p == null) throw new java.io.IOException("no transfer in progress for " + shortSha(sha));
                    if (f.payload.length < 4) throw new java.io.IOException("truncated chunk");
                    int idx = ((f.payload[0] & 0xff) << 24) | ((f.payload[1] & 0xff) << 16)
                            | ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
                    p.write(idx, f.payload, 4, f.payload.length - 4);
                } else if (f.type == Connection.T_END || f.type == Connection.T_ABORT) {
                    return;
                } else if (f.type == Connection.T_PING) {
                    c.send(Connection.T_PONG);
                }
            }
        } finally {
            if (counted) endPush(sha, p);
        }
    }

    /** A push stream ended, cleanly or not. The last one out finishes the file or keeps it for later. */
    private void endPush(String sha, Files.Partial p) {
        // computeIfPresent, not get-then-put: another stream for the same file may be opening at
        // this instant, and a counter read and written in two steps loses one of the two.
        boolean last = pushing.computeIfPresent(sha, (k, v) -> v <= 1 ? null : v - 1) == null;
        if (!last) return;
        if (p.complete()) finishDownload(null, p);
        else p.keep();                      // whatever arrived stays; the peer's next OFFER resumes it
    }

    /** Stream the chunks a peer asked for, then END — the far side of Transfer's pull worker. */
    private void servePull(Connection c, String sha, JSONObject msg) throws Exception {
        Files.Ref f = refFor(sha);
        if (f == null) {
            Logger.w("pull: " + shortSha(sha) + " not available any more");
            c.sendJson(Connection.T_ABORT, shaMsg(sha).put("reason", "not available any more"));
            return;
        }
        byte[] buf = new byte[Connection.CHUNK];
        try (Files.ChunkSource src = new Files.ChunkSource(this, f)) {
            for (int[] r : rangesOf(msg.optJSONArray("ranges"), Connection.chunks(f.size)))
                for (int i = r[0]; i < r[1]; i++) {
                    int len = src.read(i, buf);
                    c.sendChunk(i, buf, len);
                }
        }
        c.sendJson(Connection.T_END, shaMsg(sha));
    }

    /** Twelve characters of a hash, or as many as there are. Every log line wants this and one of
     *  them used to do it without the bound, which a peer could turn into a crash by sending "". */
    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(12, sha.length()));
    }

    private void onHave(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("peer already has " + (f != null ? f.name : shortSha(sha)) + ", nothing transferred");
    }

    private void onSkip(JSONObject msg) {
        String sha = msg.optString("sha256");
        Files.Ref f;
        synchronized (offered) { f = offered.remove(sha); }
        Logger.i("peer skipped " + (f != null ? f.name : shortSha(sha)) + ": " + msg.optString("reason"));
    }

    private void onAbort(JSONObject msg) {
        String sha = msg.optString("sha256");
        synchronized (offered) { offered.remove(sha); }
        for (Transfer t : new Transfer[]{upload.get(), download.get()}) {
            if (t != null && t.sha256.equals(sha)) {
                t.abort();
                Logger.i("peer aborted " + (t.upload ? "upload" : "download") + " of " + (t.upload ? t.ref.name : t.partial.name) + ": " + msg.optString("reason"));
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
                // Marked with the reason, not just closed. Closing alone ends the session and the
                // dialer redials on its usual back-off — handing the peer back exactly the link it
                // discarded, which is the loop BYE exists to prevent. The reason then says which
                // long wait this is: another route won, or the device went to sleep.
                l.markBye(why);
                // Recorded against the peer rather than the link, because the dialler that needs to
                // know may not be the one this arrived on. See idlePeers.
                if (Connection.BYE_IDLE.equals(why) && c.peerId != null) idlePeers.add(c.peerId);
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
        Transfer u = upload.get(), d = download.get();
        // `pushing` too: a peer streaming a file at us over data connections it opened is a transfer
        // this device is in the middle of, even though no Transfer of ours is tracking it. Without
        // it the screen-off burst would close the control link under a download in progress.
        if (!pushing.isEmpty()) return true;
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
        //
        // On a bounded pool, not a thread each. This is called on the main thread from the screen
        // receiver, so `new Thread` here made NON-daemon threads, one per link per clipboard change,
        // each blocking on a socket that may never drain — rapid copying multiplied them and they
        // outlived the work.
        for (Link l : byPeer.values()) {
            if (!l.isOpen()) continue;
            try {
                pushWorker.execute(() -> {
                    try { l.deliver(); } catch (Exception e) { l.close(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down
            }
        }
    }

    /**
     * Delivers a clip to one link, off the caller's thread.
     *
     * <p>Four threads, not one: a slow peer must not hold up delivery to the others, which is the
     * whole reason this is not done inline. Daemon, so none of them can keep the process alive.
     */
    private final ExecutorService pushWorker = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "clipsync-push");
        t.setDaemon(true);
        return t;
    });

    /**
     * Peers that said they were going idle, by node id.
     *
     * <p>By <b>peer</b>, not by dialler, and that is the whole reason this map exists rather than a
     * field on the Dialer. Two devices that found each other over mDNS both dial, one of the two
     * links is dropped as a duplicate, and the survivor may be the <em>inbound</em> one — so when
     * that peer goes to sleep, the goodbye arrives on a link the dialler does not own and would
     * never hear about. The dialler asks this instead, and is told.
     *
     * <p>Cleared by {@link #register}, because a peer that has just completed a handshake is by
     * definition awake, and by a failed dial, because a device that will not answer is not merely
     * asleep and the real error is the better thing to show.
     */
    private final java.util.Set<String> idlePeers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Announce that this device is going idle, then close every link.
     *
     * <p>Called on a worker: {@link Link#bye} writes a frame before it closes.
     */
    private void goIdle() {
        for (Link l : byPeer.values()) {
            if (l.isOpen()) l.bye(Connection.BYE_IDLE);
        }
        dropConnection();
    }

    /** Close every live link. Network loss, reload and shutdown all mean this. */
    private void dropConnection() {
        dropConnection(null);
    }

    /**
     * Close the links on one network, or every link when {@code gone} is null.
     *
     * <p>Inbound links count as being on the network they were accepted from, which is the active
     * one at that moment — near enough, and the alternative is to leave a link the peer cannot reach
     * us on looking alive until its read times out.
     */
    private void dropConnection(Network gone) {
        for (java.util.Map.Entry<String, Link> e : byPeer.entrySet()) {
            Link l = e.getValue();
            if (spared(l, gone)) continue;
            l.close();
            byPeer.remove(e.getKey(), l);
        }
        for (Dialer d : snapshotDialers()) {
            Link l = d.live;
            if (l == null || spared(l, gone)) continue;
            l.close();
        }
    }

    /**
     * Does this link survive the loss of {@code gone}?
     *
     * <p>Only if it is demonstrably on another network. A link whose network is unknown is closed
     * with the rest, because the failure that costs something here is keeping a dead link — it looks
     * connected until a read times out ninety seconds later — and not closing a live one, which
     * costs a reconnect.
     */
    private static boolean spared(Link l, Network gone) {
        Network on = l.connection().network;
        return gone != null && on != null && !gone.equals(on);
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

    /** Is there a clip recent enough to justify waking the radio for it? See {@link #PENDING_WAKE_MS}. */
    private boolean worthWaking() {
        return pendingLocal != null && System.currentTimeMillis() - pendingSince < PENDING_WAKE_MS;
    }

    /**
     * Say once, when a clip has been waiting long enough to stop justifying wake-ups, why it has not
     * gone out.
     *
     * <p>This is the diagnosis the frozen-process warning used to be mistaken for. A phone does not
     * reach deep sleep until it has been locked for some time, so a clip still retrying by then is
     * not evidence that the system is interfering — the system suspending an idle process is the
     * system being right. It is evidence that <b>delivery is failing</b>, and the three ways it can
     * fail want three different things from the user, which is why this branches rather than printing
     * one line:
     *
     * <ul>
     *   <li><b>no peer connected</b> — a reachability problem; the sheet already says why each target
     *       is down, so this only points at it;
     *   <li><b>connected, but the clip never went out</b> — sends are failing, and the log above this
     *       line has the exception;
     *   <li><b>connected and offered, and still here</b> — the transfer started and did not finish.
     *       For a file this is almost always the interesting one: a link that drops before the file
     *       is through will retry for as long as the clip is on the clipboard, and the fix is the
     *       size limit, not the battery settings.
     * </ul>
     */
    private void reportStalePending() {
        Object p = pendingLocal;
        if (p == null) return;
        long age = System.currentTimeMillis() - pendingSince;
        if (age < PENDING_WAKE_MS) return;
        String h = hashOf(p);
        if (h.equals(staleReported)) return;
        staleReported = h;

        long mins = Math.max(1, age / 60_000);
        boolean isFile = p instanceof Files.Ref;
        // Bytes, not MB: a small file would read as "0 MB", and the size is the point of the line.
        String what = isFile
                ? ((Files.Ref) p).name + " (" + ((Files.Ref) p).size + " bytes)"
                : ((String) p).length() + " characters of text";
        int live = 0, owing = 0;
        long limit = Long.MAX_VALUE;
        for (Link l : byPeer.values()) {
            if (!l.isOpen()) continue;
            live++;
            limit = Math.min(limit, limitFor(l.connection()));
            if (!h.equals(l.sentHash())) owing++;
        }
        String head = what + " has not been delivered in " + mins + " min";
        if (live == 0) {
            Logger.w(head + ": no peer has been connected. It stops waking the radio now and goes out"
                    + " as soon as one is — open the app's peer list to see why each target is down.");
        } else if (owing > 0) {
            Logger.w(head + ": " + owing + " of " + live + " connected peer(s) never received it, so a"
                    + " send is failing — the reason is in the lines above this one.");
        } else if (isFile) {
            Logger.w(head + ": every peer was offered it and no transfer finished. A link that drops"
                    + " before the whole file is through will keep retrying for as long as this file is"
                    + " on the clipboard; the limit in use on these link(s) is " + mb(limit) + " MB, and"
                    + " lowering it is what ends this. Not a battery-optimisation problem — a system"
                    + " that suspends an idle app is behaving correctly.");
        } else {
            Logger.w(head + ": every peer took it, yet it is still queued. This should not happen;"
                    + " please report it with the log.");
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
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
            refreshStatus();
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

    // ------------------------------------------------------------------ listening
    private volatile Server server;
    private volatile Mdns.Advert advert;

    /**
     * Bind the port and advertise, replacing whatever was there before.
     *
     * <p>Called at start-up and on every reload, because both halves depend on settings the user can
     * change: the port on one, the discovery switch on the other. Restarting them is cheap and is
     * the only thing that is certainly correct — a port that did not change is rebound to itself.
     *
     * <p>Neither failure is fatal. A device that cannot listen can still dial out, and one that
     * cannot advertise can still be reached at a listed address; both are worth a line in the log
     * and nothing more, because stopping the service over them would take away what still works.
     */
    private void startListening() {
        stopListening();
        try {
            server = Server.start(this, serverHandler, cfg.port);
            Logger.i("listening on [::]:" + cfg.port);
        } catch (Exception e) {
            Logger.w("cannot listen on port " + cfg.port + ": " + e
                    + " — this device can still reach peers, but no peer can reach it");
        }
        // Tied to the same switch as browsing: "Local network discovery" is one idea to the user,
        // and a device that looks for peers on the LAN but hides from them is not one of the ways
        // anybody wants it to work.
        if (cfg.discovery && server != null) advert = Mdns.advertise(this, Node.name(), cfg.port);
    }

    private void stopListening() {
        Server s = server;
        server = null;
        if (s != null) s.close();
        Mdns.Advert a = advert;
        advert = null;
        if (a != null) a.close();
    }

    private final Server.Handler serverHandler = new Server.Handler() {
        @Override public void onControl(Connection c) throws Exception { serveInbound(c); }

        @Override public void onData(Connection c, JSONObject hello) throws Exception { serveData(c, hello); }

        @Override public Config config() { return cfg; }
    };

    /**
     * One session with a peer that dialled us.
     *
     * <p>The mirror of {@link Dialer}'s body with everything about retrying removed, because there
     * is nothing to retry: we did not choose this link and cannot rebuild it. Past the handshake the
     * two are the same thing — same {@link Link}, same registry, same broadcast — which is the
     * property that makes a peer a peer regardless of who reached whom.
     */
    private void serveInbound(Connection c) throws Exception {
        Link l = Link.accepted(this, linkOwner, c);
        try {
            Link winner = register(l);
            if (winner != null) return;      // a duplicate; register() has already said goodbye
            l.run();
        } finally {
            l.close();
            if (l.peerId() != null) byPeer.remove(l.peerId(), l);
            teardown(l);
            refreshStatus();
        }
    }

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
        /** Why this target is not connected, for the sheet. Null while it is. */
        volatile String lastError;
        /**
         * What kind of reason {@link #lastError} is.
         *
         * <p>Set only through {@link #note}, so the two cannot drift apart — which they would, being
         * assigned at seven different points in one loop. See {@link Status.Why}.
         */
        volatile Status.Why lastWhy = Status.Why.WAITING;
        /**
         * The node the last handshake on this target reached.
         *
         * <p>The link is gone by the time the dialler asks what its silence means, so the id has to
         * outlive it — it is the only handle on {@link #idlePeers}, which is keyed by peer and not
         * by target precisely because a target is not who you reach.
         */
        private volatile String lastPeerId;

        private void note(String reason, Status.Why why) {
            lastError = reason;
            lastWhy = why;
        }

        private void connected() {
            lastError = null;
            lastWhy = Status.Why.WAITING;
        }
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
                    //
                    // No timeout. There was a 60 s one, and it was a pure cost: every waker of this
                    // gate — wake(), resetBackoff(), the network callback, onDestroy — already
                    // notifies, so the poll woke N threads a minute to discover nothing had changed,
                    // and each then rewrote status.json. On a sleeping phone that is N CPU wakeups
                    // and N file writes per minute, which is exactly what Doze batching exists to
                    // prevent. The heartbeat keeps status.json fresh; this does not need to.
                    while (running && !stop && ((!screenOn && !worthWaking()) || !hasNetwork)) {
                        try { lock.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                if (!running || stop) return;

                // Dial-time suppression (§5). Once a handshake has proved this target is a machine
                // another target already holds, stop proving it: a successful connect, handshake and
                // BYE once per back-off is the most expensive way possible to learn something we
                // already know. It lapses the moment the winning link closes, so this is a deferral
                // and not a surrender — if the other route dies, this one takes over.
                // Already connected by another route — most often because the peer dialled us while
                // this dialler was waiting out its back-off. Adopting that link as the one to defer
                // to costs nothing and saves the round trip this would otherwise make to be told the
                // same thing: connect, handshake, lose the dedup, BYE. The deferral lapses when that
                // link closes, exactly as it does when this dialler loses a tiebreak itself.
                if (deferredTo == null && lastPeerId != null) {
                    Link other = byPeer.get(lastPeerId);
                    if (other != null && other.isOpen()) deferredTo = other;
                }
                Link held = deferredTo;
                if (held != null && held.isOpen()) {
                    // Quietly: this is not a retry, and logging "retry in 60s" once a minute for a
                    // target that is deliberately not being dialled reads as a fault when it is the
                    // fix working. The one line when it started is the whole story.
                    waitBackoff(false);
                    continue;
                }
                deferredTo = null;

                boolean burst = false;
                Link l = null;
                try {
                    l = open();
                    live = l;
                    lastPeerId = l.peerId();
                    Link winner = register(l);
                    if (winner == null) {
                        backoff = BACKOFF_MIN_MS;
                        connected();
                        burst = l.run();
                    } else {
                        // Not a fault: two routes to one machine, and this is the pair choosing the
                        // one already carrying traffic. Reported so the row is not silent, coloured
                        // as ordinary information because that is what it is.
                        note(getString(R.string.peer_same_as, winner.target), Status.Why.NOTED);
                        // A duplicate of a peer another target already holds. Remember which link
                        // won, so the next round skips the dial entirely instead of connecting and
                        // handshaking only to be rejected again.
                        deferredTo = winner;
                        backoff = backoffMax();
                        Logger.i(target + ": deferring to " + winner.target + " while that link is open");
                    }
                } catch (Connection.SelfConnection e) {
                    // One rule for both kinds of target now. A listed address that is this device
                    // stays wrong until the user edits it; and an advertisement that is this device
                    // is *our own*, which no amount of retrying will change either — the device
                    // advertises on the same LAN it browses, so it finds itself every time. Marking
                    // the target as self is what stops the discovery loop recreating this dialer.
                    Logger.i(e.getMessage());
                    Connection.rememberSelf(target);
                    // Not a fault either: a device that advertises on the LAN it browses finds itself
                    // every time, and there is nothing here for the user to fix.
                    note(getString(R.string.peer_is_self), Status.Why.NOTED);
                    Logger.i("not retrying " + target + " until the configuration changes");
                    refreshStatus();                     // ... and the sheet has to say so
                    return;
                } catch (Exception e) {
                    if (l != null && !l.isOpen()) {
                        // We closed it: the screen went off, the network changed, the configuration
                        // was reloaded, or the heartbeat found it dead. The read failing afterwards
                        // is the consequence, not the cause — and calling it a fault put a red
                        // "Socket closed" against every target every time the phone was locked.
                        // Only the catch can tell these apart, because by the time the finally runs
                        // the link has been closed either way.
                        note(getString(R.string.peer_disconnected), Status.Why.WAITING);
                    } else {
                        Logger.i(target + ": " + e);
                        // The one branch that IS a fault: a timeout, a refusal, a name that will not
                        // resolve. The message, not the class name — "Connection timed out" is what
                        // the user can act on, "java.net.SocketTimeoutException" is not.
                        note(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                Status.Why.FAULT);
                        // And it is not merely asleep: a device that will not answer at all has a
                        // better explanation than the last one it gave, and this is also what keeps a
                        // peer switched off while idle from reading as *Idle* for the life of the
                        // process.
                        if (lastPeerId != null) idlePeers.remove(lastPeerId);
                    }
                } finally {
                    if (l != null) {
                        l.close();
                        if (l.peerId() != null) byPeer.remove(l.peerId(), l);
                        teardown(l);
                    }
                    live = null;
                    // A link that ended without an exception ended cleanly — the peer closed, or a
                    // burst finished. Without a reason here the target shows in the sheet with a
                    // blank line and no account of the silence that follows. Not a fault: a clean
                    // close is the screen-off path working, and there is nothing to act on.
                    if (lastError == null) note(getString(R.string.peer_disconnected), Status.Why.WAITING);
                    refreshStatus();
                }
                if (!running || stop) return;
                // Why this target is not connected, in the order the answers override each other.
                //
                // Another route won, first: it is the only one with somewhere better to point, and
                // the only one that can lapse on its own when that route closes.
                Link winner = l != null && (l.saidBye() || l.superseded()) && l.peerId() != null
                        ? byPeer.get(l.peerId()) : null;
                if (winner != null && winner.isOpen() && winner != l) {
                    deferredTo = winner;
                    note(getString(R.string.peer_same_as, winner.target), Status.Why.NOTED);
                    backoff = backoffMax();
                } else if (lastPeerId != null && idlePeers.contains(lastPeerId)) {
                    // It said it was going to sleep. This device keeps listening and the peer dials
                    // out the moment its screen comes on, so the long wait costs nothing that a
                    // redial would buy. Asked of idlePeers rather than of this link, because the
                    // goodbye may have arrived on an inbound one that this dialler never sees.
                    //
                    // The wait is long, not infinite, and that is deliberate: suppressing the dial
                    // outright would be cheaper still, and would leave a peer that was switched off
                    // while idle reading as *Idle* forever, since the only things that clear the
                    // claim are a handshake and a failed dial. One connect per minute — and only
                    // while this device's own screen is on, or the gate above stops it — buys a
                    // state that corrects itself instead of one that needs a timeout to babysit it.
                    note(getString(R.string.peer_idle), Status.Why.ASLEEP);
                    backoff = backoffMax();
                } else if (l != null && (l.saidBye() || l.superseded())) {
                    // Closed on purpose, reason unknown or no longer relevant. Redialling straight
                    // away would rebuild exactly the link that was just discarded.
                    backoff = backoffMax();
                } else if (burst) {
                    // A burst ending is success, so no back-off ladder — but not *no delay*, and not
                    // the 1 s floor this had either. The gate normally closes right after, because
                    // the clip has been released; it does not when another live peer has yet to take
                    // the same clip, or when a send failed before it could be marked delivered. Then
                    // this spins: connect, burst, close, connect. At one second that is up to three
                    // hundred connects and handshakes per dialer across the five-minute window a
                    // pending clip stays worth waking for — a bound in name only.
                    backoff = BURST_RETRY_MS;
                }
                waitBackoff(true);
            }
        }

        private Link open() throws Exception {
            Network net = connectivity.getActiveNetwork();
            if (!isMdns(target)) return Link.toPeer(SyncService.this, linkOwner, target, net);
            Mdns.Instance inst = discovered.get(instanceOf(target));
            // Gone since the last browse. Not an error to log loudly — a laptop leaving the network
            // is the ordinary case — and the dialer dies with the target at the next syncDialers().
            if (inst == null) throw new java.io.IOException("no longer advertising on this network");
            return Link.viaMdns(SyncService.this, linkOwner, target, inst, net);
        }

        private void waitBackoff(boolean say) {
            if ((!screenOn && !worthWaking()) || !hasNetwork) return;
            long wait = Math.min(backoff, backoffMax());
            if (say) Logger.i(target + ": retry in " + wait / 1000 + "s (" + (onLan ? "lan" : "mobile") + ")");
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
     * The target prefix for a peer found by local discovery: {@code *mdns*NAME}.
     *
     * <p>It replaces the single {@code *discovery*} target, which named "whatever the LAN has" and
     * could therefore hold exactly one peer however many were advertising. A target per instance is
     * what gives each of them its own dialer, its own back-off and its own line in the sheet.
     *
     * <p>Asterisks because they cannot appear in a host name, so these collide with nothing a user
     * can type — and <b>not</b> a NUL (\\u0000) sentinel, which was the first choice and does not
     * compile: Java resolves backslash-u escapes before lexing, and it does so inside comments too,
     * so writing one puts a real NUL into the source file rather than into the string. Worth knowing
     * before reaching for one again — including while writing the comment that explains why not to,
     * which is how this paragraph came to contain one.
     */
    private static final String MDNS = "*mdns*";

    private static boolean isMdns(String target) {
        return target.startsWith(MDNS);
    }

    /** The advertised service name behind an mDNS target. */
    private static String instanceOf(String target) {
        return target.substring(MDNS.length());
    }

    /**
     * Claim this peer, or discover that we already hold it.
     *
     * @return null when this link is now the one for its peer, or the link that already holds it —
     *         which the caller keeps, so the next round can skip the dial rather than repeat it
     *
     * <p>Two targets can be two names for one machine — a listed address and its mDNS
     * advertisement, or two listed addresses — and it is only here, with the handshake done and an
     * id in hand, that this becomes knowable.
     *
     * <p>All three of §5's rules apply now that the device accepts as well as dials. While it only
     * dialled, both links of any pair were ours and rule 3 was the only one that could fire; the
     * other two need the peer to have opened one of them.
     */
    private Link register(Link l) {
        String id = l.peerId();                         // never null: the handshake refuses a peer with no id
        // A peer that has just completed a handshake is awake, whatever it said last time it left.
        idlePeers.remove(id);
        while (true) {
            Link other = byPeer.putIfAbsent(id, l);
            if (other == null || other == l) return null;
            // It died between the handshake and now. replace() and not put(): a third link may have
            // registered in the meantime, and overwriting it unconditionally would lose it from the
            // map while it went on running — invisible to the heartbeat, the broadcast and the status.
            if (!other.isOpen()) {
                if (byPeer.replace(id, other, l)) return null;
                continue;                               // someone got there first; re-read and re-decide
            }
            if (duplicateLoser(other, l) == l) {
                Logger.i(l.target + " is " + other.target + " by another name [" + Node.shortId(id)
                        + "] — closing the new link");
                l.bye("duplicate");
                return other;                           // the caller defers to this link, not forever
            }
            if (!byPeer.replace(id, other, l)) continue;
            Logger.i(l.target + " and " + other.target + " are one peer [" + Node.shortId(id)
                    + "] — closing the " + (other.connection().inbound ? "inbound" : "outbound") + " link");
            other.markSuperseded();         // so its owner defers instead of redialling into this one
            other.bye("duplicate");
            return null;
        }
    }

    /**
     * Which of two links to one peer has to go (§5).
     *
     * <p>Both ends compute this from the same three facts — whether each link is on-link, which node
     * opened it, and which is older — so they reach the same verdict independently, which is the
     * property the rules exist for. A tiebreak the two ends can disagree about closes <em>both</em>
     * links and disconnects the pair entirely.
     *
     * <p>That is also why the term is {@code lanPeer} and not {@code via}: only the dialler knows
     * whether it found the peer by mDNS or by name, so a rule phrased in terms of {@code via} is not
     * a shared fact. An accepted link takes its {@code lanPeer} from the dialler's HELLO for exactly
     * this reason.
     *
     * @param b the newer link — it is registering second, which is what makes rule 3 decidable
     */
    private static Link duplicateLoser(Link a, Link b) {
        Connection ca = a.connection(), cb = b.connection();
        if (ca.lanPeer != cb.lanPeer) return cb.lanPeer ? a : b;            // 1. keep the on-link one
        if (ca.inbound != cb.inbound) {
            // 2. opened by different nodes: the link opened by the larger id goes. The plan gives
            // that close to the larger node; doing it from whichever end notices first closes the
            // same socket — both ends name the same loser — and does not wait on the other end
            // having registered both links yet.
            boolean oursLoses = Node.id().compareTo(cb.peerId) > 0;
            return ca.inbound != oursLoses ? a : b;
        }
        return b;   // 3. same opener — keep the older, which is the one already carrying traffic
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
        Transfer u = upload.get(), d = download.get();
        // Only what this link was carrying. With one connection "no connection given" could mean
        // "mine"; with several it would mean "everyone's", and a target that merely failed to
        // connect would abort the transfer a different peer was happily running.
        //
        // compareAndSet and not a bare null: between reading the slot and clearing it, another link
        // may have installed its own transfer, and clearing unconditionally would drop that one out
        // of sight while it went on running — untracked by abortTransfers, transferBusy and the next
        // teardown alike.
        if (d != null && d.control == c) { d.abort(); d.partial.keep(); download.compareAndSet(d, null); }
        if (u != null && u.control == c) {
            if (!u.isAborted()) unanswered = u.ref;
            u.abort();
            upload.compareAndSet(u, null);
        }
        // `offered` is deliberately NOT cleared here. It is what answers a WANT, it is keyed by hash
        // rather than by peer, and the same file is offered to every link — so clearing it when one
        // link dies would make the others' offers unanswerable. It is bounded at 8 and emptied by
        // HAVE / SKIP / ABORT, or wholesale when a newer clip supersedes everything.
        // Back into the pending slot only if nothing newer has taken it: a clip the user copied
        // while the transfer was dying is the one that should win.
        if (unanswered != null) synchronized (lock) {
            if (pendingLocal == null) {
                pendingLocal = unanswered;
                // The stamp is preserved when this is the same content coming back, which is the
                // normal case here: a large file whose transfer keeps dying re-enters the slot every
                // few minutes, and a fresh stamp each time made PENDING_WAKE_MS unreachable.
                if (!unanswered.sha256.equals(pendingHash)) {
                    pendingHash = unanswered.sha256;
                    pendingSince = System.currentTimeMillis();
                }
            }
        }
    }


    /**
     * Start a dialer for every target the configuration names, and stop the ones it no longer does.
     *
     * <p>Called at start-up and on every reload. Dialers for targets that survive a reload are left
     * running: a configuration change that adds a peer should not disconnect the others.
     */
    private void syncDialers() {
        java.util.Set<String> want = new java.util.LinkedHashSet<>(cfg.peers);
        // One per advertisement, not one for "the LAN". Each gets its own back-off and its own row
        // in the sheet, and a peer that leaves takes its dialer with it at the next browse.
        if (cfg.discovery) for (String name : discovered.keySet()) want.add(MDNS + name);
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
                Thread th = new Thread(d, "clipsync-dial-" + (isMdns(t) ? instanceOf(t) : t));
                th.setDaemon(true);
                th.start();
            }
        }
        for (Dialer d : cancel) d.cancel();
    }

    // ------------------------------------------------------------------ local discovery
    /**
     * Peers seen advertising on the LAN, by advertised name.
     *
     * <p>This is what replaced the single cached mDNS address. A browse is a multicast round trip
     * and a radio wake-up, so its result is kept rather than repeated per dial — but it is kept with
     * a lifetime, because the old cache's 24 hours could not notice a laptop leaving the network.
     */
    private final java.util.Map<String, Mdns.Instance> discovered = new java.util.concurrent.ConcurrentHashMap<>();
    /** Why nothing has been found, for the sheet; null once discovery has a peer of its own. */
    private volatile String discoveryState;
    /** Its own monitor, for the same reason the back-off has one: a clipboard copy is not news here. */
    private final Object browseLock = new Object();
    /** How often to browse while the screen is on. Off, the device is not looking for anything. */
    private static final long REBROWSE_MS = 60_000;
    /**
     * How long an advertisement stays believed after it was last seen.
     *
     * <p>Longer than one browse interval on purpose: multicast is lossy, and a peer missed by a
     * single browse must not lose its dialer and its back-off only to have them rebuilt a minute
     * later. Four windows is long enough that a real absence is the only thing that reaches it.
     */
    private static final long MDNS_FORGET_MS = 4 * REBROWSE_MS;

    private void discovery() {
        while (running) {
            if (cfg.discovery && hasNetwork && (screenOn || worthWaking())) browse();
            else discoveryState = null;
            synchronized (browseLock) {
                try { browseLock.wait(REBROWSE_MS); } catch (InterruptedException e) { return; }
            }
        }
    }

    /** A network arrived, the screen came on, or the configuration changed: look again now. */
    private void rebrowse() {
        synchronized (browseLock) { browseLock.notifyAll(); }
    }

    private void browse() {
        Network net = connectivity.getActiveNetwork();
        List<Mdns.Instance> found = Mdns.discover(this, net, cfg.mdnsTimeoutMs);
        for (Mdns.Instance i : found) {
            // Our own advertisement is on the same LAN we are browsing, so we find ourselves every
            // time. The handshake is what proves it (§5); this remembers the verdict so that the
            // next browse does not rebuild a dialer we already know leads back here.
            if (Connection.isKnownSelf(MDNS + i.name)) continue;
            discovered.put(i.name, i);
        }
        long now = System.currentTimeMillis();
        discovered.values().removeIf(i -> now - i.foundAt > MDNS_FORGET_MS);
        discoveryState = discovered.isEmpty() ? getString(R.string.discovery_none) : null;
        syncDialers();
        refreshStatus();    // the sheet's list of targets just changed; do not wait for a heartbeat
    }

    /**
     * How long the process may fail to run <b>while the device is awake</b> before that is a fault.
     *
     * <p>Generous, because a heavily loaded phone can legitimately deschedule a background thread
     * for a while. What it measures is not lateness but prevention.
     */
    private static final long FROZEN_MS = 20_000;

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
            long wallBefore = System.currentTimeMillis();
            long awakeBefore = SystemClock.uptimeMillis();
            try { Thread.sleep(interval); } catch (InterruptedException e) { return; }
            // Two clocks, because "we did not run for ten minutes" and "we were *prevented* from
            // running for ten minutes" are different statements and only the second is a fault.
            //
            // uptimeMillis (CLOCK_MONOTONIC) stops while the device is suspended; currentTimeMillis
            // does not. Thread.sleep waits on the monotonic clock, so a sleep that spans a deep
            // sleep returns having consumed exactly `interval` of uptime and a great deal of wall
            // clock. That difference *is* the answer: uptime overshoot is time the device was
            // running and this process was not.
            //
            // The old check looked only at the wall clock, so every lock screen reported a fault —
            // and the fault it reported was the power saving the service asks for by dropping its
            // links and idling. Screen state cannot answer this either: the case worth catching is
            // precisely a vendor battery manager freezing us in the background with the screen off
            // and a clip still to deliver, which a screen-based test would silence along with the
            // rest.
            long frozen = SystemClock.uptimeMillis() - awakeBefore - interval;
            long late = System.currentTimeMillis() - wallBefore - interval;
            if (frozen > FROZEN_MS) {
                // Both numbers, always: the gap between them is how much of the delay was the device
                // asleep, and printing it is what makes the reasoning above checkable against a real
                // log rather than only against the documentation.
                Logger.w("process was frozen for ~" + frozen / 1000 + " s while the device was awake"
                        + " (" + late / 1000 + " s behind schedule in all) — exempt ClipSync from"
                        + " battery optimisation / background limits (see the app)");
                suspendedOnce = true;
            }
            // Here rather than where the window lapses, because nothing runs there: the window
            // lapsing is the *absence* of an event. The heartbeat is the device's periodic look at
            // itself, and it notices within one interval — in practice on the first tick after the
            // phone wakes, which is when someone is there to read the log.
            reportStalePending();
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
