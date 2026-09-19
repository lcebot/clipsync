package io.github.lcebot.clipsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service state shared with the UI across processes (the service lives in ":sync"):
 * files/status.json, rewritten by the service on every change and refreshed by the heartbeat, read
 * by MainActivity every second.
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

    /** A configured target that is not connected, and why. */
    public static final class Target {
        public final String target, reason;
        /**
         * Is {@link #reason} a fault, or an expected outcome?
         *
         * <p>Not every target that is down is a problem, and the UI was colouring all of them as one.
         * Two of them are the system working: a target deferring because the same peer is already
         * held by another route is <b>choosing the better of two links</b>, and a target recognised as
         * this device has nowhere to connect to by definition. Painting those red says something is
         * broken when nothing is; red belongs to the reasons the user can act on — a timeout, a
         * refusal, a name that does not resolve.
         */
        public final boolean fault;

        Target(String target, String reason, boolean fault) {
            this.target = target; this.reason = reason; this.fault = fault;
        }
    }

    public static final class Snapshot {
        /** stopped | no network | idle | connecting | connected. See docs/p2p-plan.md §11. */
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
        public final List<Target> targets;

        Snapshot(String state, String detail, long ts, boolean suspended, List<Peer> peers, List<Target> targets) {
            this.state = state; this.detail = detail; this.ts = ts; this.suspended = suspended;
            this.peers = peers; this.targets = targets;
        }

        /** The service process wrote recently and is not stopped. */
        public boolean alive() {
            return !"stopped".equals(state) && System.currentTimeMillis() - ts < 120_000;
        }

        public int count() {
            return peers.size();
        }
    }

    /** The snapshot the UI substitutes when the service process has died without saying so. */
    public static Snapshot stopped(String detail, boolean suspended) {
        return new Snapshot("stopped", detail, 0, suspended, new ArrayList<>(), new ArrayList<>());
    }

    // Factories rather than public constructors: the service builds these, the UI only reads them.
    public static Peer peer(String id, String name, String type, String via, String addr, boolean lan) {
        return new Peer(id, name, type, via, addr, lan);
    }

    public static Target target(String target, String reason, boolean fault) {
        return new Target(target, reason, fault);
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "status.json");
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
                                          List<Peer> peers, List<Target> targets) {
        try {
            JSONArray ps = new JSONArray();
            for (Peer p : peers) {
                ps.put(new JSONObject().put("id", str(p.id)).put("name", str(p.name)).put("type", str(p.type))
                        .put("via", str(p.via)).put("addr", str(p.addr)).put("lan", p.lan));
            }
            JSONArray ts = new JSONArray();
            for (Target t : targets) {
                ts.put(new JSONObject().put("target", str(t.target)).put("reason", str(t.reason))
                        .put("fault", t.fault));
            }
            String s = new JSONObject().put("state", state).put("detail", str(detail))
                    .put("ts", System.currentTimeMillis()).put("suspended", suspended)
                    .put("peers", ps).put("targets", ts).toString();
            File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
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
            List<Target> targets = new ArrayList<>();
            JSONArray ts = o.optJSONArray("targets");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                targets.add(new Target(get(t, "target"), get(t, "reason"), t.optBoolean("fault")));
            }
            return new Snapshot(o.optString("state", "stopped"), get(o, "detail"),
                    o.optLong("ts", 0), o.optBoolean("suspended"), peers, targets);
        } catch (Exception e) {
            return new Snapshot("stopped", null, 0, false, new ArrayList<>(), new ArrayList<>());
        }
    }
}
