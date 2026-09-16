package io.github.lcebot.clipsync;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One file moving between phone and PC over N parallel data connections.
 *
 * <p>Upload (phone -> PC): the PC's WANT names the missing chunk ranges; they are striped across
 * N workers, each opening its own data connection (HELLO role=data) and pushing CHUNK frames.
 * Download (PC -> phone): each worker sends PULL for its stripe and writes the CHUNKs it gets
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
    private final Connection control;
    private final List<int[]> ranges;
    private final Done done;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicInteger alive = new AtomicInteger();
    private final List<Connection> conns = new ArrayList<>();
    private final long t0 = System.currentTimeMillis();
    private volatile String failure;

    private Transfer(Context ctx, Config cfg, Connection control, String sha, boolean upload, Files.Ref ref,
                     Files.Partial partial, List<int[]> ranges, Done done) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.control = control;
        this.sha256 = sha;
        this.upload = upload;
        this.ref = ref;
        this.partial = partial;
        this.ranges = ranges;
        this.done = done;
    }

    public static Transfer upload(Context ctx, Config cfg, Connection control, Files.Ref ref, List<int[]> ranges, Done done) {
        return new Transfer(ctx, cfg, control, ref.sha256, true, ref, null, ranges, done);
    }

    public static Transfer download(Context ctx, Config cfg, Connection control, Files.Partial p, Done done) {
        return new Transfer(ctx, cfg, control, p.sha256, false, null, p, p.missing(), done);
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
            c = Connection.data(cfg, control, sha256, Build.MODEL);
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
        int expect = mine.size();
        while (expect > 0) {
            if (aborted.get()) return;
            Connection.Frame f = c.recv();
            if (f.type == Connection.T_CHUNK) {
                int idx = ((f.payload[0] & 0xff) << 24) | ((f.payload[1] & 0xff) << 16) | ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
                partial.write(idx, f.payload, 4, f.payload.length - 4);
                expect--;
            } else if (f.type == Connection.T_END) {
                return;
            } else if (f.type == Connection.T_ABORT) {
                aborted.set(true);
                Logger.i("PC aborted the transfer: " + new JSONObject(new String(f.payload)).optString("reason"));
                return;
            }
        }
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
