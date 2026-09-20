package io.github.lcebot.clipsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One file moving between phone and PC over N parallel data connections.
 *
 * <p>Upload, from the phone to the PC: the PC's WANT names the missing chunk ranges; they are
 * striped across N workers, each opening its own data connection (HELLO role=data) and pushing
 * CHUNK frames. Download, from the PC to the phone: each worker sends PULL for its stripe and writes the CHUNKs it gets
 * into the shared {@link Files.Partial}.  {@link #abort()} stops every worker at the next chunk;
 * whatever was written stays on disk for a later resume.
 */
public final class Transfer {
    public interface Done { void onDone(Transfer t, boolean complete); }

    public final String sha256;
    public final boolean upload;
    public final Files.Ref ref;            // upload
    public final Files.Partial partial;    // download
    private final Context ctx;
    private final Config cfg;
    /**
     * Where this transfer's bytes are going, and how to reach that peer again.
     *
     * <p>{@link PeerRoute} exposes only the three things a transfer needs: open a data connection,
     * say one thing out of band, and answer "is this the transfer that link was carrying?", and
     * nothing else, so a transfer cannot reach past its own job into a peer session.
     */
    private final PeerRoute route;
    private final List<int[]> ranges;
    private final Done done;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicInteger alive = new AtomicInteger();
    private final List<Connection> conns = new ArrayList<>();
    private final long t0 = System.currentTimeMillis();
    private volatile String failure;

    private Transfer(Context ctx, Config cfg, PeerRoute route, String sha, boolean upload, Files.Ref ref,
                     Files.Partial partial, List<int[]> ranges, Done done) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.route = route;
        this.sha256 = sha;
        this.upload = upload;
        this.ref = ref;
        this.partial = partial;
        this.ranges = ranges;
        this.done = done;
    }

    /** Which peer this transfer is with: the caller's handle for matching it and for aborting it. */
    PeerRoute route() {
        return route;
    }

    public static Transfer upload(Context ctx, Config cfg, PeerRoute route, Files.Ref ref, List<int[]> ranges, Done done) {
        return new Transfer(ctx, cfg, route, ref.sha256, true, ref, null, ranges, done);
    }

    public static Transfer download(Context ctx, Config cfg, PeerRoute route, Files.Partial p, Done done) {
        return new Transfer(ctx, cfg, route, p.sha256, false, null, p, p.missing(), done);
    }

    /**
     * Called once, on whichever worker writes the first chunk of a download.
     *
     * <p>It exists for the relay: a node that is forwarding a file offers it to its waiters as soon
     * as the first chunk lands, so they pull while it is still receiving rather than after. When a
     * peer <em>pushes</em> at us, the service performs that write itself and can see it directly; when
     * this end drives the download instead, the write happens inside {@code Transfer}, out of the
     * service's sight, so this callback is what surfaces the event to the relay.
     *
     * <p>A bare Runnable rather than a handle on the service's state, because the only thing worth
     * reporting up is that the event happened.
     */
    private volatile Runnable firstChunk;

    public Transfer onFirstChunk(Runnable r) {
        this.firstChunk = r;
        return this;
    }

    /** Chunk indexes named by {@code ranges}, dealt round-robin into at most {@code cfg.threads} stripes. */
    private List<List<Integer>> stripes() {
        List<Integer> all = new ArrayList<>();
        for (int[] r : ranges) for (int i = r[0]; i < r[1]; i++) all.add(i);
        int n = Math.max(1, Math.min(cfg.threads, all.size()));
        List<List<Integer>> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add(new ArrayList<>());
        for (int i = 0; i < all.size(); i++) out.get(i % n).add(all.get(i));
        return out;
    }

    public void start() {
        List<List<Integer>> parts = stripes();
        alive.set(parts.size());
        Logger.i((upload ? "uploading " : "downloading ") + name() + ": " + count() + " chunk(s) over " + parts.size() + " connection(s)");
        for (List<Integer> mine : parts) {
            Thread t = new Thread(() -> worker(mine), "clipsync-xfer");
            t.setDaemon(true);
            t.start();
        }
    }

    private String name() { return upload ? ref.name : partial.name; }

    private int count() {
        int c = 0;
        for (int[] r : ranges) c += r[1] - r[0];
        return c;
    }

    public boolean isAborted() { return aborted.get(); }

    /** Stop all workers; partial data stays on disk. */
    public void abort() {
        if (!aborted.compareAndSet(false, true)) return;
        synchronized (conns) {
            for (Connection c : conns) c.close();
        }
    }

    private void worker(List<Integer> mine) {
        Connection c = null;
        try {
            // Node.name(), not Build.MODEL: the same value today, but the name this device shows a
            // peer is Node's answer to give, and a second reader of the system property is a second
            // place to change when it stops being the property.
            c = route.data(cfg, sha256, Node.name());
            synchronized (conns) { conns.add(c); }
            if (aborted.get()) return;
            if (upload) push(c, mine); else pull(c, mine);
        } catch (Exception e) {
            if (!aborted.get()) {
                failure = String.valueOf(e);
                Logger.i("transfer stream failed: " + e);
            }
        } finally {
            if (c != null) c.close();
            if (alive.decrementAndGet() == 0) finish();
        }
    }

    private void push(Connection c, List<Integer> mine) throws Exception {
        byte[] buf = new byte[Connection.CHUNK];
        try (Files.ChunkSource src = new Files.ChunkSource(ctx, ref)) {
            for (int idx : mine) {
                if (aborted.get()) return;
                int len = src.read(idx, buf);
                c.sendChunk(idx, buf, len);
            }
        }
        c.sendJson(Connection.T_END, new JSONObject().put("sha256", sha256));
        // the PC may answer ABORT while we were pushing; a short wait drains it, EOF/timeout is fine
        c.setSoTimeout(500);
        try { c.recv(); } catch (Exception ignored) {}
    }

    private void pull(Connection c, List<Integer> mine) throws Exception {
        JSONArray rs = new JSONArray();
        for (int idx : mine) rs.put(new JSONArray().put(idx).put(idx + 1));
        c.sendJson(Connection.T_PULL, new JSONObject().put("sha256", sha256).put("ranges", rs));
        // read until the peer's END (not just until our chunks are in): closing earlier makes its
        // final send fail with a reset and log a spurious "connection forcibly closed"
        Frames.dataLoop(c, new Frames.Data() {
            /** {@link Transfer#abort()} closes the sockets, but a worker between frames stops here. */
            @Override public boolean stopped() {
                return aborted.get();
            }

            @Override public void chunk(int idx, byte[] payload, int off, int len) throws Exception {
                // Asked before the write, so "was there anything here" is asked of the state the
                // write is about to change. Several workers can be here at once and all of them can
                // see an empty file, so the question and the flag are one operation on the Partial's
                // own monitor (claimFirstChunk) rather than a read here and a compareAndSet on a
                // field of this Transfer: the push side has the same rule to enforce and no Transfer
                // to hang it on, and two implementations of one rule is how the push side ended up
                // with none.
                boolean wasFirst = partial.claimFirstChunk();
                partial.write(idx, payload, off, len);
                Runnable first = firstChunk;
                if (wasFirst && first != null) first.run();
            }

            /**
             * The peer gave up. Recorded as well as logged: this worker is one of several, and the
             * flag is what stops the others asking for the rest of their stripes.
             */
            @Override public void aborted(Connection.Frame f) {
                aborted.set(true);
                String why;
                try {
                    why = Frames.json(f).optString("reason");
                } catch (Exception e) {
                    why = "no reason given";
                }
                Logger.i("peer aborted the transfer: " + why);
            }

            // PING is not answered here, and cannot arrive: this end opened the stream and is
            // pulling on it, so the peer is serving chunks and never has a reason to poll a socket
            // it is actively writing to. A stream the PEER opened can sit idle and does answer;
            // see FileExchange.serveData.
        });
    }

    private void finish() {
        boolean complete = upload ? !aborted.get() && failure == null : partial.complete();
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        if (complete) {
            long bytes = upload ? ref.size : partial.size;
            Logger.i((upload ? "uploaded " : "downloaded ") + name() + " in " + ms / 1000.0 + " s (" + (bytes * 1000 / ms / 1024) + " KB/s)");
        } else if (aborted.get()) {
            Logger.i((upload ? "upload" : "download") + " of " + name() + " stopped" + (upload ? "" : " (" + partial + ", kept for resume)"));
        } else {
            Logger.i((upload ? "upload" : "download") + " of " + name() + " incomplete: " + (failure != null ? failure : partial.toString()));
        }
        if (done != null) done.onDone(this, complete);
    }
}
