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
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.material.color.MaterialColors;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service (specialUse) that keeps one connection to each peer while the screen is on.
 * Clipboard access in background works because Entry hooks ClipboardService in system_server.
 *
 * <p><b>What is left in here, and what is not.</b> Three subjects each live behind an explicit
 * interface back to this class rather than a reach into its fields:
 *
 * <ul>
 *   <li>{@link ClipboardBridge}: what this device holds, what it has sent, and what it is still
 *       trying to hand to its peers;
 *   <li>{@link FileExchange}: OFFER/WANT/CHUNK and everything that serves or receives a file,
 *       including the {@link RelayCoordinator} it owns;
 *   <li>{@link Link} and {@link Dialer}: one session and one target's persistence.
 * </ul>
 *
 * <p>What remains is the service itself: the Android lifecycle, the assembly of those parts, the
 * things that are genuinely per-device because the device has exactly one of them: one radio (so
 * one {@link #heartbeat()}), one network, one screen, one status file, one key schedule, one peer
 * map; and the control-frame switch, which is the only place that knows every frame type.
 *
 * <p><b>The lock.</b> There is exactly one, {@link #lock}, held here and handed to
 * {@link ClipboardBridge} at construction, because the two halves of what it guards are one
 * invariant: a dialler sleeps on this monitor until there is work, and "is there work" is a
 * question about the clipboard's pending slot. {@link FileExchange} and {@link RelayCoordinator} do
 * not take it at all, because every clipboard question they have goes through a ClipboardBridge
 * method that takes it for them, so there is no lock ordering to get wrong.
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
    /**
     * Reconnect back-off (doubling), capped per transport.
     *
     * <p>The two caps differ, and the split only earns its keep because they do; while both were
     * 60 s this was an abstraction that described nothing. What makes them different is what a retry
     * costs and what it is likely to buy. On Wi-Fi a connect is cheap and the peer is usually a
     * machine on the same LAN that will come back within the minute, so a minute is the right
     * ceiling. On cellular every attempt pulls the modem out of its idle state, and the thing on the
     * other end is typically a PC at home that is off for the evening, so retrying it every minute
     * all evening buys nothing and costs battery all night. Five minutes is still prompt enough that
     * nobody watches for it, because a network change or the screen coming on resets the ladder to
     * {@link #BACKOFF_MIN_MS} anyway, and those are what actually end most waits.
     */
    private static final long BACKOFF_MIN_MS = 1_000, BACKOFF_MAX_WIFI_MS = 60_000, BACKOFF_MAX_MOBILE_MS = 300_000;

    // State for the UI lives in files/status.json (the UI runs in another process): see Status.
    /**
     * The heartbeat has caught this process being frozen <em>while the device was awake</em>.
     *
     * <p>The qualifier is the whole meaning of the flag: only a freeze that happens while the device
     * is awake is evidence of a problem. It drives the app's battery card, which tells the user the
     * system is still freezing ClipSync despite the exemption, advice that is right for a freeze the
     * device was awake through and wrong for the one that happens every time the phone is locked,
     * where being suspended is what the service asks for by idling. See {@link #heartbeat()}.
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
     * *Idle* needs nothing at all. There is no separate *Disconnected* state: with several targets,
     * "never connected" versus "connected, then lost" is not a fact about the device as a whole;
     * which target failed and why is per target, and lives in the sheet.
     */
    private void refreshStatus() {
        if (!running) { setStatus("stopped"); return; }
        if (!hasNetwork) { setStatus("no network"); return; }
        if (!byPeer.isEmpty()) {
            // "Relay (n)" when this device is actively relaying for others; asked of the waiters
            // and not of the map, because a key with no waiters left is a relay that has finished.
            setStatus(relayingCount() == 0 ? "connected" : "relay");
            return;
        }
        if (!screenOn) { setStatus("idle", "screen off"); return; }
        setStatus("connecting");
    }

    /**
     * Re-derive and re-write the status: liveness for the UI, and a fresh answer.
     *
     * <p>It re-derives rather than re-writing what was last said, and that is not an optimisation to
     * skip. The state is computed from volatiles that change without anyone calling in here, namely
     * `screenOn`, `hasNetwork`, `byPeer`, so re-writing the last string risks publishing a claim
     * that has already stopped being true, such as a chip stuck on *Connecting…* while the screen is
     * off with no link left to die, or `Connected (0)` beside an emptied peer map. Deriving here
     * means every caller of this is also a state transition, which is exactly what it should be.
     */
    private void touchStatus() {
        refreshStatus();
    }

    /** What the last status write said, so an identical one can be skipped. See {@link #writeStatus}. */
    private volatile String lastStatusSig;
    private volatile long lastStatusAt;
    /**
     * How long a status file may go unwritten while nothing changes.
     *
     * <p>Not "forever", although nothing new is being said: the UI decides the service is alive from
     * the file's timestamp, with two minutes of slack. Staying under that is what makes skipping
     * writes safe, because the alternative is a chip that reads *Stopped* beside a service that is running
     * perfectly and a Start button that cold-starts a second copy.
     */
    private static final long STATUS_MAX_QUIET_MS = 60_000;

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
        // Indirect peers: ones this device knows about only because a direct peer listed them in its
        // roster (T_PEERS), so they are reachable in two hops but not connected to us.
        // Exclude any id that is already a direct peer or is this device itself.
        java.util.Set<String> directIds = new java.util.HashSet<>();
        for (Link l : byPeer.values()) {
            if (l.isOpen() && l.peerId() != null) directIds.add(l.peerId());
        }
        List<Status.IndirectPeer> indirectPeers = new ArrayList<>();
        java.util.Set<String> seenIndirect = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, org.json.JSONArray> e : indirect.entrySet()) {
            String reporterId = e.getKey();
            // Find reporter's name for the "via" field.
            Link reporter = byPeer.get(reporterId);
            String reporterName = reporter != null && reporter.isOpen()
                    ? reporter.connection().peerLabel : Node.shortId(reporterId);
            org.json.JSONArray entries = e.getValue();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String pid = entry.optString("id", "");
                if (pid.isEmpty() || directIds.contains(pid) || pid.equals(Node.id())) continue;
                if (!seenIndirect.add(pid)) continue;   // dedup across reporters
                indirectPeers.add(Status.indirectPeer(pid, entry.optString("name", "?"),
                        entry.optString("type", "?"), reporterName));
            }
        }
        // Everything configured that is not up, with its last reason. Three addresses of which one
        // is failing is invisible in "Connected (2)", and that is exactly the thing someone opens
        // the sheet to find out.
        List<Status.Target> targets = new ArrayList<>();
        for (Dialer d : snapshotDialers()) {
            Link l = d.live;
            if (l != null && l.isOpen()) continue;
            String name = isMdns(d.target) ? instanceOf(d.target) : d.target;
            // Not listed at all when its peer is connected by another route, which is what happens
            // every time a phone wakes up and dials us, leaving the dialler that would otherwise
            // reach it with nothing to do.
            //
            // This section means "configured targets that are not connected", and a target whose
            // device is connected is, in the only sense anyone opens this for, working. That the
            // address is not the one in use is a fact, but it is one the log already records; a row
            // for it here would sit directly under the same device's card in the group above, which
            // reads as the sheet contradicting itself rather than as a footnote.
            //
            // Asked of the live map either way, never of what the dialler last wrote down: the peer
            // map is the truth about who is connected, and a dialler knows only about its own last
            // attempt. Deriving it is the same rule the state string follows.
            Link byOther = d.lastPeerId == null ? null : byPeer.get(d.lastPeerId);
            if (byOther != null && byOther.isOpen()) continue;
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
        // Nothing new to say, and something said recently enough: don't say it again. The heartbeat
        // calls in here every thirty seconds whether or not anything moved, and each call was a
        // synchronous file write plus an inotify event that woke the UI process to re-parse and
        // re-render an identical snapshot, about 2 900 of each a day on an idle phone.
        int relaying = relayingCount();
        String sig = signature(peers, indirectPeers, targets, relaying);
        long now = System.currentTimeMillis();
        if (sig.equals(lastStatusSig) && now - lastStatusAt < STATUS_MAX_QUIET_MS) return;
        lastStatusSig = sig;
        lastStatusAt = now;
        Status.write(this, lastState, lastDetail, suspendedOnce, peers, indirectPeers, targets, relaying);
    }

    /** Everything a status write would say except the timestamp, flattened for comparison. */
    private String signature(List<Status.Peer> peers, List<Status.IndirectPeer> indirect,
                             List<Status.Target> targets, int relaying) {
        StringBuilder sb = new StringBuilder();
        sb.append(lastState).append('').append(lastDetail).append('')
                .append(suspendedOnce).append('').append(relaying);
        for (Status.Peer p : peers)
            sb.append('').append(p.id).append('|').append(p.name).append('|').append(p.type)
                    .append('|').append(p.via).append('|').append(p.addr).append('|').append(p.lan);
        for (Status.IndirectPeer p : indirect)
            sb.append('').append(p.id).append('|').append(p.name).append('|').append(p.type)
                    .append('|').append(p.via);
        for (Status.Target t : targets)
            sb.append('').append(t.target).append('|').append(t.reason).append('|').append(t.why);
        return sb.toString();
    }

    /**
     * How many files this device is actually relaying, for the UI's "Relay (n)".
     *
     * <p>Guarded, because the status is written once before the parts are assembled: an invalid
     * configuration is reported and the service stops before {@link #files} exists.
     */
    private int relayingCount() {
        FileExchange f = files;
        return f == null ? 0 : f.relay().relayingCount();
    }

    private volatile Config cfg;
    private FileCache cache;
    private ClipboardManager clipboard;
    private PowerManager power;
    private ConnectivityManager connectivity;

    /** What this device holds on its clipboard, and what it still owes its peers. */
    private ClipboardBridge clip;
    /** OFFER/WANT/CHUNK, and the relay coordination that rides on them. */
    private FileExchange files;

    /**
     * The device's one monitor.
     *
     * <p>It guards two things that are really one: the clipboard state in {@link ClipboardBridge},
     * and the gate every {@link Dialer} sleeps on. The gate's condition reads the pending clip, so
     * publishing a clip and waking the diallers has to happen under the same monitor or a dialler
     * can go to sleep a microsecond before the work it was waiting for appears. That is why it is
     * created here and passed in rather than living inside the clipboard: the sleeper is on this
     * side of the boundary and the condition is on the other.
     */
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

    /** Peer roster: indirect maps each sender_id to its reported peer entries (full snapshot, replaced on each T_PEERS). */
    private final java.util.Map<String, org.json.JSONArray> indirect = new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        // First line of every run. The id is per-process (see Node), so this line is what ties every
        // later "connected" line to the session it belongs to.
        Logger.i("ClipSync " + Node.name() + ", node " + Node.shortId(Node.id())
                + " (protocol " + Connection.PROTOCOL_VERSION + ")");
        startForegroundQuiet();             // must happen promptly after startForegroundService()
        try {
            cfg = Config.load(this);
            Node.setRelayOptOut(cfg.relayOptOut);
        } catch (RuntimeException e) {
            Logger.w("invalid config: " + e.getMessage() + "; open the app and fix it");
            setStatus("stopped", "invalid config");
            disableAutoStart();
            stopSelf();
            return;
        }
        started = true;
        // "I do not know when this key started" is read as NOW (see Config.schedule), which is the
        // safe reading but is not a durable one: it is re-decided at every launch, so a key whose
        // psk_since was never written, whether typed in by hand or edited into the file, is zero days
        // old at every launch and never reaches the rotation deadline at all. Recording the answer
        // once turns the guess into a fact, and the check costs one property read.
        if (!cfg.keys.psk.isEmpty() && storedKeyAge(this) == 0) {
            persistSchedule(cfg.keys);          // its `since` is already the anchor Config chose
            // Read back, and the read-back is not belt-and-braces. If the write silently does not
            // land, this branch runs again at every launch, and the log line below has to say
            // that plainly, rather than reporting the same "recorded it" every time, so a stuck
            // write shows up in the log instead of quietly failing to reach a rotation that never
            // happens.
            long stored = storedKeyAge(this);
            if (stored == 0) {
                Logger.w("key age: psk_since is still unset after writing it, so rotation cannot"
                        + " measure this key's age from the file. Falling back to " + FILE_AGE_NOTE);
            } else {
                Logger.i("key age was unknown; recorded it as " + new java.util.Date(stored));
            }
        }
        cache = new FileCache(this);
        startupReport();
        clipboard = getSystemService(ClipboardManager.class);
        power = getSystemService(PowerManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        // Assembled before the clipboard listener is registered, because the listener's first fire
        // goes straight into ClipboardBridge.
        clip = new ClipboardBridge(this, clipboard, lock, clipHost);
        files = new FileExchange(this, cache, clip, fileHost);

        clipboard.addPrimaryClipChangedListener(clipListener);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);   // system broadcasts only
        screenOn = power.isInteractive();

        // The handle is seeded here, before the callback exists, and that ordering is the whole
        // point. registerDefaultNetworkCallback delivers the current network immediately, so an
        // unseeded netHandle (0) would make that first delivery look like a network change and
        // trigger a re-advertisement on top of the one startListening() just made. NsdManager's
        // unregister is asynchronous, so the two registrations would race, mDNS would resolve the
        // name collision by suffixing, and the device would end up advertising as both "NAME" and
        // "NAME (2)", finding both, building a dialer for each, and recognising itself twice.
        Network active = connectivity.getActiveNetwork();
        netHandle = active == null ? 0 : active.getNetworkHandle();
        readNetwork(connectivity.getNetworkCapabilities(active));
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

    /**
     * Break the restart loop an unusable configuration would otherwise put the device in.
     *
     * <p>Stopping ourselves is not enough. The xposed watchdog (see {@code xposed.Common}) polls
     * "is auto-start wanted and is the service not running?" every minute, and "wanted" means only
     * that the {@link BootReceiver} component is enabled; it has no way to know that the last start
     * died on its own configuration. So an invalid config would give a process launch and a flash of
     * the foreground notification once a poll, forever, with nothing but the log to say why.
     *
     * <p>Disabling the component is the one signal the watchdog does read, and it is the same switch
     * the Stop button uses, so the state is not a new one: the app shows auto-start off, and Apply
     * turns it back on once the configuration parses. Never called for a runtime failure, only for
     * a configuration that cannot start, which is exactly the case a retry cannot fix.
     */
    private void disableAutoStart() {
        try {
            getPackageManager().setComponentEnabledSetting(
                    new android.content.ComponentName(this, BootReceiver.class),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            Logger.i("auto-start disabled so the watchdog stops relaunching us; Apply re-enables it");
        } catch (RuntimeException e) {
            Logger.w("could not disable auto-start: " + e);
        }
    }

    /**
     * What the fallback is, for the one log line that has to explain it.
     *
     * <p>Named rather than inlined because it is the answer to "then how does the key ever age?":
     * {@link Config} anchors an unwritten activation time to the configuration file's modification
     * time, so even with this write failing forever the key still ages, just from the file rather
     * than from the field. What it cannot do is age from a clock that restarts with the process.
     */
    private static final String FILE_AGE_NOTE = "the configuration file's modification time";

    /** What the file itself says about when the key started; 0 means "it does not say". */
    private static long storedKeyAge(Context ctx) {
        try {
            return Long.parseLong(Config.raw(ctx).getProperty("psk_since", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
     * reload in place (no restart, no process churn, no reconnect storm), and one that was never
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
                if (cd != null) clipWorker.execute(() -> clip.handleClip(cd, "push"));
                else if (intent.getBooleanExtra("fetch", false)) clipWorker.execute(clip::readLocalClip);   // clip too big for binder
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
        Node.setRelayOptOut(cfg.relayOptOut);
        Logger.i("config reloaded: " + targets(cfg)
                + ", " + cfg.threads + " streams, files in " + cfg.filesDir);
        files.abortAll("configuration changed");
        // The size limits may be exactly what just changed, so a clip already reported as stuck
        // deserves to be judged again rather than stay silently written off.
        clip.forgetStaleReport();
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
        // Stops the relay's background timer, which is also where FileExchange's debounced re-ask
        // runs, so this is the one call that has to be here for either of them not to outlive us.
        if (files != null) files.relay().shutdown();
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
                // while they are looking at the screen. wake() alone cannot do it, because it notifies the
                // gate, deliberately not the back-off.
                resetBackoff();
                rebrowse();             // the LAN may be a different one since the screen went off
                wake();
                refreshStatus();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOn = false;
                // On a worker, because saying goodbye is a socket write and this is the main thread.
                // Worth saying: a link that simply closes leaves the peer unable to tell a device
                // that went to sleep from one that crashed, for as long as its read timeout, and
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
            // The handle, not the capabilities: this callback fires on signal strength and on
            // metered-ness, several times a minute on a moving phone, and tearing down the mDNS
            // registration that often would make the device invisible more than it made it visible.
            // A different handle is a different network, which is the only thing that matters here.
            //
            // This fires once with the current network as soon as the callback is registered, so
            // netHandle is seeded in onCreate before that happens: an unseeded 0 would make the very
            // first delivery look like a move and trigger a duplicate advertisement.
            long handle = n == null ? 0 : n.getNetworkHandle();
            boolean moved = handle != netHandle;
            netHandle = handle;
            if (moved) {
                // Our advertisement belongs to the network it was registered on. Without this, a
                // phone that changed Wi-Fi went on browsing successfully and was never found by
                // anybody again: no exception, no log line, nothing to notice.
                republishAdvert("after a network change");
            }
            if (readNetwork(nc) || moved) {
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

    /** The network the links and the advertisement currently belong to; 0 before there is one. */
    private volatile long netHandle;

    private long backoffMax() {
        return onLan ? BACKOFF_MAX_WIFI_MS : BACKOFF_MAX_MOBILE_MS;
    }

    /** Give every target its first retry back. A network appearing is good news for all of them. */
    private void resetBackoff() {
        for (Dialer d : snapshotDialers()) d.resetBackoff();
    }

    // ------------------------------------------------------------------ the clipboard's thread
    // the clipboard listener runs on the main thread; reading a 10 MB image there would freeze
    // MainActivity, so the work is handed to a single worker (serialised: order is preserved)
    private final ExecutorService clipWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-clip");
        t.setDaemon(true);
        return t;
    });

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        try {
            clipWorker.execute(clip::readLocalClip);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // service is shutting down
        }
    };

    /** What {@link ClipboardBridge} may ask of the device. */
    private final ClipboardBridge.Host clipHost = new ClipboardBridge.Host() {
        @Override public Config config() { return cfg; }

        @Override public Iterable<Link> links() { return byPeer.values(); }

        @Override public java.util.Set<String> knownPeerIds() { return SyncService.this.knownPeerIds(); }

        @Override public void sendClip(Link l, Object c) throws Exception { SyncService.this.sendClip(l, c); }

        @Override public void supersede(String except) { files.supersede(except); }

        @Override public void wake() { SyncService.this.wake(); }

        @Override public long limitOn(Connection c) { return FileExchange.limitFor(cfg, c); }
    };

    /** What {@link FileExchange} may ask of the device. */
    private final FileExchange.Host fileHost = new FileExchange.Host() {
        @Override public Config config() { return cfg; }

        @Override public Iterable<Link> links() { return byPeer.values(); }

        @Override public Link linkTo(String peerId) { return peerId == null ? null : byPeer.get(peerId); }

        @Override public java.util.Set<String> knownPeerIds() { return SyncService.this.knownPeerIds(); }
    };

    /**
     * Put one clip on one link.
     *
     * <p>Two kinds, two paths, and they diverge here rather than sharing a method that would have to
     * branch on the type twice over: text goes out whole in a CLIP frame and is compared by version,
     * a file is announced by hash in an OFFER and its bytes only move if the peer answers WANT.
     * Nothing else in the app has to know which kind a clip is.
     */
    private void sendClip(Link link, Object o) throws Exception {
        if (o instanceof Files.Ref) files.offer(link, (Files.Ref) o);
        else clip.sendText(link, (String) o);
    }

    // ------------------------------------------------------------------ control frames

    /**
     * One place for every frame type on a control connection, used by both the normal loop and the
     * screen-off burst.
     *
     * <p>It is the only one of the app's three read loops that understands the session: CLIP, KEYS,
     * BYE and the roster exist nowhere else, and CHUNK/PULL/END, the entire vocabulary of the two
     * data loops in {@link Frames#dataLoop}, cannot appear here at all, because a control
     * connection never carries a byte of a file. The overlap is PING/PONG and ABORT, and only PING
     * is answered the same way twice (it is not: see {@link Frames#pong}).
     */
    private void handleFrame(Link l, Connection.Frame f) throws Exception {
        Connection c = l.connection();
        switch (f.type) {
            case Connection.T_CLIP -> clip.onRemoteClip(l, Frames.json(f));
            case Connection.T_OFFER -> files.onOffer(l, Frames.json(f));
            case Connection.T_WANT -> files.onWant(c, Frames.json(f));
            case Connection.T_HAVE -> files.onHave(Frames.json(f));
            case Connection.T_SKIP -> files.onSkip(Frames.json(f));
            case Connection.T_ABORT -> files.onAbort(Frames.json(f));
            case Connection.T_KEYS -> onKeys(l, Frames.json(f));
            case Connection.T_PING -> Frames.pong(c, Frames.json(f));
            case Connection.T_PONG -> {
                long t4 = System.currentTimeMillis();
                JSONObject pong = Frames.json(f);
                long t1 = pong.optLong("t1", 0);
                long t2 = pong.optLong("t2", 0);
                long t3 = pong.optLong("t3", 0);
                if (t1 > 0 && t2 > 0 && t3 > 0) {
                    l.clockOffset = ((t2 - t1) + (t3 - t4)) / 2;
                }
            }
            case Connection.T_BYE -> {
                String why = Frames.json(f).optString("reason", "");
                Logger.i(c.peer + " said goodbye: " + (why.isEmpty() ? "no reason given" : why));
                // Marked, not just closed. Closing alone ends the session and the dialer redials on
                // its usual back-off, handing the peer back exactly the link it discarded, which is
                // the loop BYE exists to prevent. The reason itself is logged above and, for the one
                // reason anything acts on, carried to idlePeers below.
                l.ended(Link.End.PEER_BYE);
                // Recorded against the peer rather than the link, because the dialler that needs to
                // know may not be the one this arrived on. See idlePeers.
                if (Connection.BYE_IDLE.equals(why) && c.peerId != null) idlePeers.add(c.peerId);
                l.close();
            }
            case Connection.T_RELAY_ASK -> files.relay().onRelayAsk(l, Frames.json(f));
            case Connection.T_RELAY_OK -> files.relay().onRelayOk(l, Frames.json(f));
            case Connection.T_RELAY_NO -> files.relay().onRelayNo(l, Frames.json(f));
            case Connection.T_PEERS -> onPeers(l, Frames.json(f));
            default -> { }
        }
    }

    // ------------------------------------------------------------------ key rotation

    /**
     * Send this device's key schedule to one peer.
     *
     * <p>Sent right after HELLO on every control connection (both sides), and whenever the schedule
     * changes (successor generated, promoted, or reconciled). The frame is cheap and harmless if the
     * peer ignores it, so it is sent whether or not rotation is on: a device with rotation off
     * still has a {@link Keys.Schedule} with a possibly non-empty ring, and a peer needs to hear
     * that to align its own.
     */
    private void sendKeys(Connection c) {
        Keys.Schedule s = cfg.keys;
        if (s.psk.isEmpty()) return;       // no key yet, nothing to tell
        try {
            JSONObject msg = new JSONObject();
            msg.put("psk", s.psk);
            msg.put("since", s.since);
            msg.put("next", s.next);
            c.sendJson(Connection.T_KEYS, msg);
        } catch (Exception e) {
            Logger.i("could not send key schedule: " + e);
        }
    }

    /** When the last announcement went out, for {@link #announceKeys}. */
    private volatile long lastAnnounceMs;
    /**
     * Shortest gap between two key announcements.
     *
     * <p>A backstop, not a scheduler. Announcing is the response to a schedule that changed, and
     * with an idempotent {@code agreed()} a settled pair changes nothing and says nothing, so in
     * ordinary running this limit is never reached. It is here because the failure it bounds is
     * expensive and self-feeding: an announcement provokes a reply, a reply that looks like a change
     * provokes another announcement, and every turn of that loop rewrites the file holding the PSK.
     * Five seconds keeps a real change prompt and makes the loop, if one is ever reintroduced,
     * merely noisy instead of destructive.
     */
    private static final long ANNOUNCE_MIN_GAP_MS = 5_000;

    /** The schedule the last announcement carried, so a repeat of it can be recognised. */
    private volatile Keys.Schedule lastAnnounced;

    /**
     * Send T_KEYS to every connected peer, unless this is the same thing said moments ago.
     *
     * <p>Both halves of that condition matter. Rate-limiting alone would swallow a real change that
     * happened to follow a reconciliation closely: a generated successor announced nowhere waits
     * until the next phase transition, which is a day away. Comparing alone would not bound a loop
     * that alternates between two schedules. Together they silence the repetition and nothing else.
     */
    private void announceKeys() {
        Keys.Schedule s = cfg.keys;
        long now = System.currentTimeMillis();
        if (s.equals(lastAnnounced) && now - lastAnnounceMs < ANNOUNCE_MIN_GAP_MS) return;
        lastAnnounced = s;
        lastAnnounceMs = now;
        for (Link l : byPeer.values()) {
            if (l.isOpen()) sendKeys(l.connection());
        }
    }

    /**
     * A peer reported its key schedule. Reconcile, persist if anything changed, and reply with ours
     * so the peer can do the same.
     */
    private void onKeys(Link l, JSONObject msg) {
        String theirPsk = msg.optString("psk", "").trim().toLowerCase();
        String theirNext = msg.optString("next", "").trim().toLowerCase();
        if (theirPsk.isEmpty()) return;
        // Refused at the door, not at the file. A peer holding the PSK could otherwise name anything
        // at all as the network's next key, and the value goes straight into the configuration,
        // where a `psk_next` that is not hex is read back on every inbound connection and makes all
        // of them fail. One malformed frame, and the device can never be reached again.
        if (Config.checkPsk(theirPsk) != null) {
            Logger.w("keys: " + l.connection().peerLabel + " sent a malformed psk, ignored");
            return;
        }
        if (Config.checkPskOrEmpty(theirNext) != null) {
            Logger.w("keys: " + l.connection().peerLabel + " sent a malformed successor, ignored");
            return;
        }
        Keys.Schedule before = cfg.keys;
        // Which key let this connection in decides whether it may lead. A peer on the current key or
        // on the successor is where the network is going; one that authenticated with a superseded
        // ring key is behind, and rotation exists precisely because a superseded key may have
        // leaked, so letting it nominate the next key would turn a past compromise into a present
        // takeover. Its right to *connect* is untouched: the ring still accepts it, and the "peer is
        // behind" branch of reconcile teaches it our schedule instead.
        boolean trusted = matches(l.connection().matchedSecret, before.psk)
                || (!before.next.isEmpty() && matches(l.connection().matchedSecret, before.next));
        if (!trusted && before.wouldAdopt(theirPsk)) {
            Logger.w("keys: " + l.connection().peerLabel + " authenticated with a retired key"
                    + " and asked us onto " + Node.shortKey(theirPsk) + "; not adopting it");
        }
        Keys.Schedule after = before.reconcile(theirPsk, theirNext, System.currentTimeMillis(), trusted);
        // Spelled out rather than left to == or to equals(). Reference equality called every frame a
        // change, because `agreed()` returned a new object each time; equals() ignores `agreedAt`,
        // which is right for the storm and wrong for the FIRST agreement, because that one moves the phase
        // from STRANDED to DUE and absolutely must be written down, or the promotion it unlocks is
        // forgotten at the next restart. Both facts are stated here so neither can be lost to a
        // later edit of either operator.
        // `old` and `since` are in the list although a change to either today implies a changed psk:
        // adopt() and promoted() rewrite all three together. They are here so that a future schedule
        // transition which touches only the ring cannot be silently dropped, and so that this reads
        // the same as the Windows side's check rather than being a shorter list someone has to
        // diff by eye.
        boolean changed = !after.psk.equals(before.psk)
                || !after.next.equals(before.next)
                || after.retireAt != before.retireAt
                || after.since != before.since
                || !after.old.equals(before.old)
                || (before.agreedAt == 0 && after.agreedAt != 0);
        if (changed) {
            Logger.i("key schedule reconciled with " + l.connection().peerLabel
                    + ": psk=" + Node.shortKey(after.psk)
                    + (after.next.isEmpty() ? "" : " next=" + Node.shortKey(after.next)));
            persistSchedule(after);
            // Tell every peer the result, including the one that triggered this, so it hears the
            // outcome of the tie-break if there was one.
            announceKeys();
        }
    }

    /**
     * One line at startup saying what this run is actually configured to do.
     *
     * <p>Every setting named here is one that fails quietly when it is wrong: a key whose age is
     * being re-guessed at each launch never rotates, a ring that silently lost a member starts
     * refusing peers, discovery being off looks exactly like a network problem, and a files
     * directory that has grown past its budget looks exactly like a transfer bug. None of them
     * raise anything. Printing them costs one line and turns "it does not work" into a question
     * with an answer. The Windows service prints the same line for the same reason.
     */
    private void startupReport() {
        Keys.Schedule s = cfg.keys;
        long age = s.psk.isEmpty() ? 0 : Math.max(0, System.currentTimeMillis() - s.since);
        StringBuilder b = new StringBuilder("config: ");
        b.append(s.psk.isEmpty() ? "no key (not paired)" : "key " + Node.shortKey(s.psk)
                + " age " + (age / 3_600_000) + "h" + (age / 60_000 % 60) + "m");
        b.append(", rotate ").append(cfg.rotate ? "on" : "off");
        if (!s.next.isEmpty()) b.append(", next ").append(Node.shortKey(s.next));
        b.append(", ring ").append(s.accepted().size()).append(" key(s)");
        b.append("; listening :").append(cfg.port);
        b.append(", data_out true");           // this end always opens its own data connections
        b.append(", discovery ").append(cfg.discovery ? "browse+advertise" : "off");
        b.append(", direct ").append(cfg.direct ? "on" : "off");
        long[] used = cache.usage();
        b.append("; files ").append(used[1]).append(" / ").append(used[0] / (1024 * 1024)).append(" MB");
        b.append(", keep ").append(cfg.keepHours).append("h");
        b.append(cfg.keepMaxBytes > 0 ? " / " + cfg.keepMaxBytes / (1024 * 1024) + " MB" : " / unlimited");
        Logger.i(b.toString());
    }

    /** Is {@code secret} the key this hex names? Null (an outbound link) counts as the current PSK. */
    private static boolean matches(byte[] secret, String hex) {
        if (secret == null) return true;    // outbound connections always use the current PSK
        if (hex == null || hex.length() != secret.length * 2) return false;
        for (int i = 0; i < secret.length; i++) {
            int b = Character.digit(hex.charAt(2 * i), 16) << 4 | Character.digit(hex.charAt(2 * i + 1), 16);
            if ((secret[i] & 0xff) != b) return false;
        }
        return true;
    }

    /**
     * Persist a new key schedule to the configuration file and reload.
     *
     * <p>Uses {@link Config#save}, which merges and validates, so all other settings survive and
     * the PSK field changes when a promotion swaps the key.
     */
    private void persistSchedule(Keys.Schedule sched) {
        try {
            Properties update = Config.store(sched, cfg.rotate);
            cfg = Config.save(this, update);
        } catch (Exception e) {
            Logger.w("rotation: could not save schedule: " + e);
        }
    }

    /**
     * The rotation clock, called from the heartbeat every ~30 s.
     *
     * <p>Pure phase-based: at each tick it asks "what phase is the key in?" and does the one thing
     * that phase calls for, or nothing if no action is due. The design is intentionally incremental
     * generating now, announcing on the next heartbeat (or sooner via onConnected), and promoting
     * later, so a process that dies between ticks loses nothing.
     */
    private void checkRotation() {
        if (!cfg.rotate) return;
        Keys.Schedule s = cfg.keys;
        if (s.psk.isEmpty()) return;
        long now = System.currentTimeMillis();
        Keys.Phase phase = s.phase(now);
        Keys.Schedule next = null;
        switch (phase) {
            case PRE_RETIRED:
                if (s.next.isEmpty()) {
                    String successor = Crypto.randomPskHex();
                    next = s.withNext(successor);
                    Logger.i("rotation: generated successor " + Node.shortKey(successor)
                            + ", announcing to peers");
                }
                break;
            case DUE:
                next = s.promoted(now);
                Logger.i("rotation: promoted successor to current key "
                        + Node.shortKey(next.psk));
                break;
            case STRANDED:
                next = s.extended();
                Logger.i("rotation: no peer agreed, extending deadline by "
                        + Keys.EXTEND_MS / 3600_000L + " h");
                break;
            default:
                break;
        }
        if (next != null) {
            persistSchedule(next);
            announceKeys();
        }
    }

    // ------------------------------------------------------------------ peer roster

    /**
     * Direct peers ∪ indirect peers: the full {@code to} set for OFFER and CLIP.
     *
     * <p>Indirect ones matter because the {@code to} list is what stops a file being fetched twice
     * and what a relay election is computed over: a node this device cannot reach but its peer can
     * is still a node that is about to get this clip.
     */
    private java.util.Set<String> knownPeerIds() {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (Link peer : byPeer.values()) {
            if (peer.isOpen() && peer.peerId() != null) result.add(peer.peerId());
        }
        for (org.json.JSONArray entries : indirect.values()) {
            for (int i = 0, n = entries.length(); i < n; i++) {
                String pid = entries.optJSONObject(i) != null ? entries.optJSONObject(i).optString("id", "") : "";
                if (!pid.isEmpty()) result.add(pid);
            }
        }
        return result;
    }

    /**
     * Send this node's direct-peer roster to one peer, excluding that peer.
     *
     * <p>Every node telling each of its direct peers about its other direct peers is what gives the
     * whole network a view two hops wide, which is exactly what an OFFER's {@code to} list and a
     * relay election need and neither can work without.
     */
    private void sendPeers(Connection c) {
        if (c.peerId == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Link peer : byPeer.values()) {
                Connection pc = peer.connection();
                if (peer.isOpen() && pc.peerId != null && !pc.peerId.equals(c.peerId)) {
                    JSONObject entry = new JSONObject();
                    entry.put("id", pc.peerId);
                    entry.put("name", pc.peerLabel);
                    entry.put("type", pc.peerType);
                    entry.put("persistent", pc.peerPersistent);
                    entry.put("battery", pc.peerBattery);
                    arr.put(entry);
                }
            }
            JSONObject msg = new JSONObject();
            msg.put("peers", arr);
            c.sendJson(Connection.T_PEERS, msg);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) names.append(", ");
                names.append(arr.optJSONObject(i) != null ? arr.optJSONObject(i).optString("name", "?") : "?");
            }
            Logger.i("sent roster to " + c.peerLabel + ": " + arr.length() + " peer(s) [" + (arr.length() > 0 ? names : "empty") + "]");
        } catch (Exception e) {
            Logger.i("could not send peers to " + c.peerLabel + ": " + e);
        }
    }

    /** Send an updated T_PEERS to every connected peer. */
    private void broadcastPeers() {
        for (Link l : byPeer.values()) {
            if (l.isOpen()) sendPeers(l.connection());
        }
    }

    /** A peer reported its direct-peer roster.  Replace our record for that sender. */
    private void onPeers(Link l, JSONObject msg) {
        Connection c = l.connection();
        if (c.peerId == null) return;
        org.json.JSONArray entries = msg.optJSONArray("peers");
        if (entries == null) entries = new org.json.JSONArray();
        indirect.put(c.peerId, entries);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < entries.length(); i++) {
            if (i > 0) names.append(", ");
            JSONObject e = entries.optJSONObject(i);
            names.append(e != null ? e.optString("name", "?") : "?");
        }
        Logger.i("roster from " + c.peerLabel + ": " + entries.length() + " peer(s) [" + (entries.length() > 0 ? names : "none") + "]");
        refreshStatus();
    }

    // ------------------------------------------------------------------ network loop
    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
        // Every live link, not the one: a clip has to reach every peer. Each decides for itself
        // whether it has already sent this one, so calling them all is safe however often it happens.
        //
        // On a bounded pool, not a thread each: this is called on the main thread from the screen
        // receiver, and delivery blocks on a socket that may never drain. A thread per link per
        // clipboard change would be unbounded and could outlive the work it was doing.
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
     * links is dropped as a duplicate, and the survivor may be the <em>inbound</em> one, so when
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
     * one at that moment, near enough, and the alternative is to leave a link the peer cannot reach
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
     * with the rest, because the failure that costs something here is keeping a dead link, which looks
     * connected until a read times out ninety seconds later, and not closing a live one, which
     * costs a reconnect.
     */
    private static boolean spared(Link l, Network gone) {
        Network on = l.connection().network;
        return gone != null && on != null && !gone.equals(on);
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
            // Tell the peer our key schedule, so it can align its successor with ours. Both ends
            // send one if rotation is on; the frame is harmless either way (a peer without rotation
            // simply ignores it), so it goes out unconditionally when the schedule has anything to
            // say, which is whenever a successor exists, because that is news the peer needs
            // whether or not it is rotating itself.
            sendKeys(c);
            broadcastPeers();
            // Both halves of the handshake carry clip_ts / clip_sha, so this runs on a link we
            // dialled and on one we accepted alike, which is the point: the peer that is behind is
            // as often the one that called us as the one we called.
            Hello hello = l.peerHello();
            clip.catchUp(l, hello == null ? 0 : hello.clipTs,
                    hello == null ? "" : hello.clipSha);
            refreshStatus();
        }

        @Override public long clipTs() { return clip.clipTs(); }

        @Override public String clipSha() { return clip.clipSha(); }

        @Override public Object pendingClip() {
            return clip.pendingClip();
        }

        @Override public void send(Link l, Object c) throws Exception {
            sendClip(l, c);
        }

        @Override public void delivered(Link l, String hash) {
            clip.releasePending(hash);
        }

        @Override public boolean transferBusy() {
            return files.transferBusy();
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
     * the only thing that is certainly correct: a port that did not change is rebound to itself.
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
                    + "; this device can still reach peers, but no peer can reach it");
        }
        republishAdvert(null);
    }

    /**
     * Drop the mDNS registration and make a new one, if this device should have one at all.
     *
     * <p>Two callers, which is why it is a method: start-up (and every reload, where the port or the
     * discovery switch may have changed) and a network change. Both matter because a registration is
     * made on one network and means nothing on the next; without re-registering here, a phone that
     * moved to another Wi-Fi would go on browsing perfectly while being unfindable itself.
     *
     * <p>Tied to the same switch as browsing: "Local network discovery" is one idea to the user, and
     * a device that looks for peers on the LAN but hides from them is not one of the ways anybody
     * wants it to work.
     *
     * @param why a phrase for the log when this is a re-advertisement, or null at start-up, where
     *            "listening on …" has already said everything
     */
    private void republishAdvert(String why) {
        Mdns.Advert a = advert;
        advert = null;
        if (a != null) a.close();
        if (!cfg.discovery || server == null) return;
        advert = Mdns.advertise(this, Node.name(), cfg.port);
        if (why != null) Logger.i("mDNS re-advertising " + why);
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
        @Override public void onControl(Connection c, Hello hello) throws Exception { serveInbound(c, hello); }

        @Override public void onData(Connection c, Hello hello) throws Exception { files.serveData(c, hello); }

        @Override public Config config() { return cfg; }
    };

    /**
     * One session with a peer that dialled us.
     *
     * <p>The mirror of {@link Dialer}'s body with everything about retrying removed, because there
     * is nothing to retry: we did not choose this link and cannot rebuild it. Past the handshake the
     * two are the same thing, using the same {@link Link}, the same registry, and the same
     * broadcast, which is the
     * property that makes a peer a peer regardless of who reached whom.
     */
    private void serveInbound(Connection c, Hello hello) throws Exception {
        Link l = Link.accepted(this, linkOwner, c, hello);
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
         * <p>Dial-time suppression: once a handshake has proved that two targets are one machine,
         * there is no reason to keep proving it. Held as the winning Link rather than as a flag so
         * it heals itself: the moment that link closes, this target starts dialling again, which is
         * what makes it a suppression rather than a permanent surrender.
         */
        private volatile Link deferredTo;
        /** Why this target is not connected, for the sheet. Null while it is. */
        volatile String lastError;
        /**
         * What kind of reason {@link #lastError} is.
         *
         * <p>Set only through {@link #note}, so the two cannot drift apart, which they would, being
         * assigned at seven different points in one loop. See {@link Status.Why}.
         */
        volatile Status.Why lastWhy = Status.Why.WAITING;
        /**
         * The node the last handshake on this target reached.
         *
         * <p>The link is gone by the time the dialler asks what its silence means, so the id has to
         * outlive it, because it is the only handle on {@link #idlePeers}, which is keyed by peer and not
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
         * <p>Separate from {@code lock}, which is what {@code wake()} notifies on every clipboard
         * copy: the two waits ask different questions, and sharing a monitor would answer one with
         * the other. The gate asks "is there work", which a new clip answers; the back-off asks "has
         * the world changed", which only a network change or a shutdown answers; a clipboard copy
         * must not cancel every dialer's back-off and force an immediate reconnect.
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
                    // Hold a link while the screen is on, or while something is waiting to go out,
                    // and never try without a network (the callback wakes us when one appears).
                    //
                    // No timeout: every waker of this gate, namely wake(), resetBackoff(), the network
                    // callback, and onDestroy, already notifies, so a poll would only wake every dialer
                    // a minute to discover nothing had changed and rewrite status.json for no reason.
                    // On a sleeping phone that is exactly the kind of CPU and file-write churn Doze
                    // batching exists to prevent. The heartbeat keeps status.json fresh; this does
                    // not need to.
                    while (running && !stop && ((!screenOn && !clip.worthWaking()) || !hasNetwork)) {
                        try { lock.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                if (!running || stop) return;

                // Dial-time suppression. Once a handshake has proved this target is a machine
                // another target already holds, stop proving it: a successful connect, handshake and
                // BYE once per back-off is the most expensive way possible to learn something we
                // already know. It lapses the moment the winning link closes, so this is a deferral
                // and not a surrender: if the other route dies, this one takes over.
                // Already connected by another route, most often because the peer dialled us while
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
                    // is *our own*, which no amount of retrying will change either, because the device
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
                        // is the consequence, not the cause, and calling it a fault put a red
                        // "Socket closed" against every target every time the phone was locked.
                        // Only the catch can tell these apart, because by the time the finally runs
                        // the link has been closed either way.
                        note(getString(R.string.peer_disconnected), Status.Why.WAITING);
                    } else {
                        Logger.i(target + ": " + e);
                        // The one branch that IS a fault: a timeout, a refusal, a name that will not
                        // resolve. The message, not the class name: "Connection timed out" is what
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
                    // A link that ended without an exception ended cleanly: the peer closed, or a
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
                Link winner = l != null && l.endedOnPurpose() && l.peerId() != null
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
                    // claim are a handshake and a failed dial. One connect per minute, and only
                    // while this device's own screen is on, or the gate above stops it, buys a
                    // state that corrects itself instead of one that needs a timeout to babysit it.
                    note(getString(R.string.peer_idle), Status.Why.ASLEEP);
                    backoff = backoffMax();
                } else if (l != null && l.endedOnPurpose()) {
                    // Closed on purpose, reason unknown or no longer relevant. Redialling straight
                    // away would rebuild exactly the link that was just discarded.
                    backoff = backoffMax();
                } else if (burst) {
                    // A burst ending is success, so no back-off ladder, but not *no delay*, and not
                    // the 1 s floor this had either. The gate normally closes right after, because
                    // the clip has been released; it does not when another live peer has yet to take
                    // the same clip, or when a send failed before it could be marked delivered. Then
                    // this spins: connect, burst, close, connect. At one second that is up to three
                    // hundred connects and handshakes per dialer across the five-minute window a
                    // pending clip stays worth waking for, a bound in name only.
                    backoff = BURST_RETRY_MS;
                }
                waitBackoff(true);
            }
        }

        private Link open() throws Exception {
            Network net = connectivity.getActiveNetwork();
            if (!isMdns(target)) return Link.toPeer(SyncService.this, linkOwner, target, net);
            Mdns.Instance inst = discovered.get(instanceOf(target));
            // Gone since the last browse. Not an error to log loudly, because a laptop leaving the network
            // is the ordinary case, and the dialer dies with the target at the next syncDialers().
            if (inst == null) throw new java.io.IOException("no longer advertising on this network");
            return Link.viaMdns(SyncService.this, linkOwner, target, inst, net);
        }

        private void waitBackoff(boolean say) {
            if ((!screenOn && !clip.worthWaking()) || !hasNetwork) return;
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
     * can type, and <b>not</b> a NUL (\\u0000) sentinel, which was the first choice and does not
     * compile: Java resolves backslash-u escapes before lexing, and it does so inside comments too,
     * so writing one puts a real NUL into the source file rather than into the string. Worth knowing
     * before reaching for one again, including while writing the comment that explains why not to,
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
     * @return null when this link is now the one for its peer, or the link that already holds it,
     *         which the caller keeps, so the next round can skip the dial rather than repeat it
     *
     * <p>Two targets can be two names for one machine: a listed address and its mDNS
     * advertisement, or two listed addresses; and it is only here, with the handshake done and an
     * id in hand, that this becomes knowable.
     *
     * <p>All three dedup rules in {@link #duplicateLoser} apply now that the device accepts as well
     * as dials. While it only dialled, both links of any pair were ours and rule 3 was the only one
     * that could fire; the other two need the peer to have opened one of them.
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
            // map while it went on running, invisible to the heartbeat, the broadcast and the status.
            if (!other.isOpen()) {
                if (byPeer.replace(id, other, l)) return null;
                continue;                               // someone got there first; re-read and re-decide
            }
            if (duplicateLoser(other, l) == l) {
                Logger.i(l.target + " is " + other.target + " by another name [" + Node.shortId(id)
                        + "]: closing the new link");
                l.bye("duplicate");
                return other;                           // the caller defers to this link, not forever
            }
            if (!byPeer.replace(id, other, l)) continue;
            Logger.i(l.target + " and " + other.target + " are one peer [" + Node.shortId(id)
                    + "]: closing the " + (other.connection().inbound ? "inbound" : "outbound") + " link");
            other.ended(Link.End.SUPERSEDED);   // so its owner defers instead of redialling into this one
            other.bye("duplicate");
            return null;
        }
    }

    /**
     * Which of two links to one peer has to go.
     *
     * <p>Three rules, in order: keep the one on the LAN; if the two were opened by different nodes,
     * close the one the larger node id opened; otherwise keep the older.
     *
     * <p>Both ends compute this from the same three facts: whether each link is on-link, which node
     * opened it, and which is older; so they reach the same verdict independently, which is the
     * property the rules exist for. A tiebreak the two ends can disagree about closes <em>both</em>
     * links and disconnects the pair entirely.
     *
     * <p>That is also why the term is {@code lanPeer} and not {@code via}: only the dialler knows
     * whether it found the peer by mDNS or by name, so a rule phrased in terms of {@code via} is not
     * a shared fact. An accepted link takes its {@code lanPeer} from the dialler's HELLO for exactly
     * this reason.
     *
     * @param b the newer link; it is registering second, which is what makes rule 3 decidable
     */
    private static Link duplicateLoser(Link a, Link b) {
        Connection ca = a.connection(), cb = b.connection();
        if (ca.lanPeer != cb.lanPeer) return cb.lanPeer ? a : b;            // 1. keep the on-link one
        if (ca.inbound != cb.inbound) {
            // 2. opened by different nodes: the link opened by the larger id goes. The rule gives
            // that close to the larger node; doing it from whichever end notices first closes the
            // same socket, because both ends name the same loser, and does not wait on the other end
            // having registered both links yet.
            boolean oursLoses = Node.id().compareTo(cb.peerId) > 0;
            return ca.inbound != oursLoses ? a : b;
        }
        return b;   // 3. same opener: keep the older, which is the one already carrying traffic
    }

    /**
     * After a link goes: stop whatever it was carrying, tell the remaining peers, and keep what can
     * be resumed.
     *
     * <p>Three pieces of clean-up in a fixed order, and the order is the readable part: stop the
     * transfers that were riding this link before anything else can observe a half-dead one; then
     * correct the roster, because a peer that has gone must stop appearing in everyone else's
     * two-hop view; then let the relay walk past it and put the undelivered file back.
     */
    private void teardown(Link l) {
        Connection c = l.connection();
        Files.Ref unanswered = files.stopTransfersOn(l);
        // Drop what this peer told us about its peers, and tell remaining peers it is gone.
        if (c.peerId != null) {
            indirect.remove(c.peerId);
            broadcastPeers();
        }
        // If a relay we were waiting on disconnected, walk to the next candidate; and stop counting
        // this link as a waiter for anything we were relaying to it.
        files.relay().onLinkGone(l, c.peerId);
        // Back into the pending slot only if nothing newer has taken it: a clip the user copied
        // while the transfer was dying is the one that should win.
        if (unanswered != null) clip.requeue(unanswered);
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
     * <p>A browse is a multicast round trip and a radio wake-up, so its result is kept rather than
     * repeated per dial, but with a short lifetime ({@link #MDNS_FORGET_MS}), so a laptop leaving
     * the network is noticed rather than believed for a whole day.
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
            if (cfg.discovery && hasNetwork && (screenOn || clip.worthWaking())) browse();
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
            // time. The handshake is what proves it: the peer's HELLO comes back with our own node
            // id; and this remembers the verdict so that the next browse does not rebuild a dialer
            // we already know leads back here.
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
            // Checking the wall clock alone would report a fault on every lock screen, since a
            // locked screen means uptime and wall time drift apart by design, and that drift is the
            // power saving the service asks for by dropping its links and idling, not a problem.
            // Screen state cannot substitute for the uptime check either: the case worth catching is
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
                        + " (" + late / 1000 + " s behind schedule in all); exempt ClipSync from"
                        + " battery optimisation / background limits (see the app)");
                suspendedOnce = true;
            }
            // Here rather than where the window lapses, because nothing runs there: the window
            // lapsing is the *absence* of an event. The heartbeat is the device's periodic look at
            // itself, and it notices within one interval, in practice on the first tick after the
            // phone wakes, which is when someone is there to read the log.
            clip.reportStalePending();
            checkRotation();
            touchStatus();
            for (java.util.Map.Entry<String, Link> e : byPeer.entrySet()) {
                Link l = e.getValue();
                if (!l.isOpen()) { byPeer.remove(e.getKey(), l); continue; }
                // A failed ping is a dead link. Closing it is what makes its own dialer's blocking
                // recv() return, which is what gets it retried, so this is the path that notices a
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
