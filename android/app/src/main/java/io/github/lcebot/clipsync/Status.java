package io.github.lcebot.clipsync;

import android.content.Context;
import android.os.FileObserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service state shared with the UI across processes (the service lives in ":sync"):
 * files/status.json, rewritten by the service on every change and refreshed by the heartbeat. The UI
 * is told about a rewrite rather than looking for one — see {@link #watch} — and keeps a slow poll
 * only for the things no write can announce.
 *
 * <p><b>It carries a list now, not a connection.</b> The flat {@code via/lan/host/addr} tuple this
 * replaced could only describe one peer, which stopped being a fact about the device the moment it
 * could hold several. Two lists, because the interesting question is no longer "am I connected" but
 * "which of the things I was told to reach am I reaching": {@link #peers} is what is up, and
 * {@link #targets} is what is configured and is not, each with the reason.
 */
public final class Status {
    private Status() {}

    /** A live link, as the UI needs to show it. */
    public static final class Peer {
        public final String id, name, type, via, addr;
        public final boolean lan;

        Peer(String id, String name, String type, String via, String addr, boolean lan) {
            this.id = id; this.name = name; this.type = type; this.via = via; this.addr = addr; this.lan = lan;
        }
    }

    /**
     * What kind of thing a not-connected reason is — the four answers to "so what do I do?".
     *
     * <p>*Not connected* began as a place to put errors, and was coloured as one: every reason in it
     * got {@code colorError}. But most of what lands there is not an error at all, and the section's
     * real job is to account for every configured target, whatever the account says. Grading it needs
     * a vocabulary rather than a boolean, and the grades are chosen so that each one answers that
     * question differently — a colour that does not change what the reader does next is decoration.
     *
     * <p>The order below is the order of visual weight, loudest first.
     */
    public enum Why {
        /**
         * Something is wrong and you can act on it: a timeout, a refusal, a name that will not
         * resolve. {@code colorError}, and the only thing that gets it.
         */
        FAULT,
        /**
         * The peer <b>told us</b> it was going idle. The one row in the section carrying positive
         * knowledge rather than the absence of it, so it takes an accent — {@code colorTertiary},
         * which this app already uses for the chip's *Connecting…*: a state the system is passing
         * through on purpose. Nothing to do; it will dial back when its screen comes on.
         */
        ASLEEP,
        /**
         * Not connected, no explanation offered, and expected to be connected again — *Disconnected*,
         * or a discovery browse that has found nothing yet. The default, at plain
         * {@code colorOnSurface}. Wait.
         */
        WAITING,
        /**
         * A fact about the setup rather than about a connection: *That is this device*, *Same device
         * as …*. Nothing will change it but the configuration, and there is nothing to be done about
         * it now — so it is the quietest, {@code colorOnSurfaceVariant}. These are footnotes
         * explaining why a row exists at all.
         */
        NOTED;

        static Why of(String name) {
            for (Why w : values()) if (w.name().equals(name)) return w;
            return WAITING;
        }
    }

    /**
     * A peer this device knows about only because a direct peer listed it in its roster (T_PEERS),
     * so it is two hops away rather than connected.
     */
    public static final class IndirectPeer {
        public final String id, name, type, via;

        IndirectPeer(String id, String name, String type, String via) {
            this.id = id; this.name = name; this.type = type; this.via = via;
        }
    }

    /** A configured target that is not connected, and why. */
    public static final class Target {
        public final String target, reason;
        public final Why why;

        Target(String target, String reason, Why why) {
            this.target = target; this.reason = reason; this.why = why == null ? Why.WAITING : why;
        }
    }

    public static final class Snapshot {
        /**
         * stopped | no network | idle | connecting | connected | relay. Five answers to "what do I
         * do now", which is the only reason they are distinct: *Stopped* needs a button pressed,
         * *No network* needs the network fixed, *Idle* needs nothing at all.
         */
        public final String state;
        /** Free text for the states that have somewhere to go but nowhere to be. */
        public final String detail;
        public final long ts;           // wall-clock ms of the last write
        // the heartbeat caught the process being frozen at least once WHILE THE DEVICE WAS AWAKE.
        // Being suspended along with the device is the intended outcome, not a fault — the service
        // drops its links and idles with the screen off on purpose — so counting that would advise
        // the user against a power saving that is working.
        public final boolean suspended;
        public final List<Peer> peers;
        public final List<IndirectPeer> indirectPeers;
        public final List<Target> targets;
        /** Number of files this device is currently fetching on a LAN peer's behalf. */
        public final int relayCount;

        Snapshot(String state, String detail, long ts, boolean suspended, List<Peer> peers,
                 List<IndirectPeer> indirectPeers, List<Target> targets, int relayCount) {
            this.state = state; this.detail = detail; this.ts = ts; this.suspended = suspended;
            this.peers = peers; this.indirectPeers = indirectPeers; this.targets = targets; this.relayCount = relayCount;
        }

        /** The service process wrote recently and is not stopped. */
        public boolean alive() {
            return !"stopped".equals(state) && System.currentTimeMillis() - ts < 120_000;
        }

        /**
         * Directly connected peers — what the status chip counts.
         *
         * <p>Indirect peers are deliberately left out. They are devices this one has only heard
         * about from a neighbour, so counting them would make the chip claim connections that do
         * not exist; they get their own group in the details sheet instead, where the distinction
         * can be stated rather than implied by a number.
         */
        public int count() {
            return peers.size();
        }
    }

    /** The snapshot the UI substitutes when the service process has died without saying so. */
    public static Snapshot stopped(String detail, boolean suspended) {
        return new Snapshot("stopped", detail, 0, suspended, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
    }

    // Factories rather than public constructors: the service builds these, the UI only reads them.
    public static Peer peer(String id, String name, String type, String via, String addr, boolean lan) {
        return new Peer(id, name, type, via, addr, lan);
    }

    public static IndirectPeer indirectPeer(String id, String name, String type, String via) {
        return new IndirectPeer(id, name, type, via);
    }

    public static Target target(String target, String reason, Why why) {
        return new Target(target, reason, why);
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "status.json");
    }

    /**
     * Fire {@code onChange} whenever the service replaces the status file.
     *
     * <p>Push instead of poll, across a process boundary that has no other channel: the service
     * writes this file, the UI reads it, and inotify is the one thing both ends already share. What
     * it replaces is a read, a parse and a render once a second for as long as the app is in front,
     * nearly all of which found nothing new — while still being up to a second late when there
     * was.
     *
     * <p><b>The directory is watched, not the file</b>, and that is forced by {@link #write}: it
     * writes a temporary file and renames it over the old one, so the inode a file watch attaches to
     * is precisely the one being discarded. Such a watch fires once and is then bound to nothing.
     * Watching the parent for {@code MOVED_TO} and filtering by name is the shape an atomic writer
     * demands, and it is also why the two are documented together.
     *
     * <p>{@code onEvent} arrives on the observer's own thread, so the caller has to marshal. The
     * returned observer must be held in a field and started: an unreferenced FileObserver is
     * collected and stops delivering, silently.
     */
    public static FileObserver watch(Context ctx, Runnable onChange) {
        File f = file(ctx);
        String name = f.getName();
        return new FileObserver(f.getParentFile(), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (name.equals(path)) onChange.run();
            }
        };
    }

    /**
     * Synchronized, because the number of writers grew with the number of connections.
     *
     * <p>One temporary file, one rename. With two writers that was survivable; there are now one per
     * dialer plus the heartbeat plus the main thread, and two of them truncating the same tmp file
     * interleave their bytes. The reader's catch-all then yields the "stopped" snapshot — so the
     * chip blinks *Stopped*, the button blinks *Start*, and pressing it in that window starts the
     * service instead of reloading it. A per-thread tmp name would also work; serialising is
     * cheaper to be sure of.
     */
    public static synchronized void write(Context ctx, String state, String detail, boolean suspended,
                                          List<Peer> peers, List<IndirectPeer> indirectPeers,
                                          List<Target> targets, int relayCount) {
        try {
            JSONArray ps = new JSONArray();
            for (Peer p : peers) {
                ps.put(new JSONObject().put("id", str(p.id)).put("name", str(p.name)).put("type", str(p.type))
                        .put("via", str(p.via)).put("addr", str(p.addr)).put("lan", p.lan));
            }
            JSONArray ips = new JSONArray();
            for (IndirectPeer ip : indirectPeers) {
                ips.put(new JSONObject().put("id", str(ip.id)).put("name", str(ip.name))
                        .put("type", str(ip.type)).put("via", str(ip.via)));
            }
            JSONArray ts = new JSONArray();
            for (Target t : targets) {
                ts.put(new JSONObject().put("target", str(t.target)).put("reason", str(t.reason))
                        .put("why", t.why.name()));
            }
            String s = new JSONObject().put("state", state).put("detail", str(detail))
                    .put("ts", System.currentTimeMillis()).put("suspended", suspended)
                    .put("peers", ps).put("indirect_peers", ips)
                    .put("targets", ts).put("relay_count", relayCount).toString();
            Files.atomicWrite(file(ctx), out -> out.write(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }

    private static Object str(String s) {
        return s == null ? JSONObject.NULL : s;
    }

    private static String get(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k);
    }

    public static Snapshot read(Context ctx) {
        try (InputStream in = new FileInputStream(file(ctx))) {
            JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<Peer> peers = new ArrayList<>();
            JSONArray ps = o.optJSONArray("peers");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                peers.add(new Peer(get(p, "id"), get(p, "name"), get(p, "type"),
                        get(p, "via"), get(p, "addr"), p.optBoolean("lan")));
            }
            List<IndirectPeer> indirectPeers = new ArrayList<>();
            JSONArray ips = o.optJSONArray("indirect_peers");
            for (int i = 0; ips != null && i < ips.length(); i++) {
                JSONObject ip = ips.optJSONObject(i);
                if (ip == null) continue;
                indirectPeers.add(new IndirectPeer(get(ip, "id"), get(ip, "name"),
                        get(ip, "type"), get(ip, "via")));
            }
            List<Target> targets = new ArrayList<>();
            JSONArray ts = o.optJSONArray("targets");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                targets.add(new Target(get(t, "target"), get(t, "reason"), Why.of(t.optString("why"))));
            }
            return new Snapshot(o.optString("state", "stopped"), get(o, "detail"),
                    o.optLong("ts", 0), o.optBoolean("suspended"), peers, indirectPeers, targets,
                    o.optInt("relay_count", 0));
        } catch (Exception e) {
            return new Snapshot("stopped", null, 0, false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
        }
    }
}
