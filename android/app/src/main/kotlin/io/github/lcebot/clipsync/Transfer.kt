package io.github.lcebot.clipsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One file moving between phone and PC over N parallel data connections.
 *
 * Upload, from the phone to the PC: the PC's WANT names the missing chunk ranges; they are
 * striped across N workers, each opening its own data connection (HELLO role=data) and pushing
 * CHUNK frames. Download, from the PC to the phone: each worker sends PULL for its stripe and writes the CHUNKs it gets
 * into the shared [Files.Partial].  [abort] stops every worker at the next chunk;
 * whatever was written stays on disk for a later resume.
 */
class Transfer private constructor(
    ctx: Context,
    cfg: Config,
    route: PeerRoute,
    sha: String,
    upload: Boolean,
    ref: Files.Ref?,
    partial: Files.Partial?,
    ranges: List<IntArray>,
    done: Done?,
) {
    fun interface Done { fun onDone(t: Transfer, complete: Boolean) }

    val sha256: String = sha
    val upload: Boolean = upload
    val ref: Files.Ref? = ref              // upload
    val partial: Files.Partial? = partial  // download
    private val ctx: Context = ctx
    private val cfg: Config = cfg
    /**
     * Where this transfer's bytes are going, and how to reach that peer again.
     *
     * [PeerRoute] exposes only the three things a transfer needs: open a data connection,
     * say one thing out of band, and answer "is this the transfer that link was carrying?", and
     * nothing else, so a transfer cannot reach past its own job into a peer session.
     */
    private val route: PeerRoute = route
    private val ranges: List<IntArray> = ranges
    private val done: Done? = done
    private val aborted = AtomicBoolean(false)
    private val alive = AtomicInteger()
    private val conns = ArrayList<Connection>()
    private val t0 = System.currentTimeMillis()
    @Volatile
    private var failure: String? = null

    /** Which peer this transfer is with: the caller's handle for matching it and for aborting it. */
    internal fun route(): PeerRoute {
        return route
    }

    /**
     * Called once, on whichever worker writes the first chunk of a download.
     *
     * It exists for the relay: a node that is forwarding a file offers it to its waiters as soon
     * as the first chunk lands, so they pull while it is still receiving rather than after. When a
     * peer *pushes* at us, the service performs that write itself and can see it directly; when
     * this end drives the download instead, the write happens inside `Transfer`, out of the
     * service's sight, so this callback is what surfaces the event to the relay.
     *
     * A bare Runnable rather than a handle on the service's state, because the only thing worth
     * reporting up is that the event happened.
     */
    @Volatile
    private var firstChunk: Runnable? = null

    fun onFirstChunk(r: Runnable): Transfer {
        this.firstChunk = r
        return this
    }

    /** Chunk indexes named by `ranges`, dealt round-robin into at most `cfg.threads` stripes. */
    private fun stripes(): List<List<Int>> {
        val all = ArrayList<Int>()
        for (r in ranges) for (i in r[0] until r[1]) all.add(i)
        val n = Math.max(1, Math.min(cfg.threads, all.size))
        val out = ArrayList<MutableList<Int>>()
        for (k in 0 until n) out.add(ArrayList())
        for (i in 0 until all.size) out[i % n].add(all[i])
        return out
    }

    fun start() {
        val parts = stripes()
        alive.set(parts.size)
        Logger.i((if (upload) "uploading " else "downloading ") + name() + ": " + count() +
                " chunk(s) over " + parts.size + " connection(s)")
        for (mine in parts) {
            val t = Thread({ worker(mine) }, "clipsync-xfer")
            t.isDaemon = true
            t.start()
        }
    }

    private fun name() = if (upload) checkNotNull(ref).name else checkNotNull(partial).name

    private fun count(): Int {
        var c = 0
        for (r in ranges) c += r[1] - r[0]
        return c
    }

    fun isAborted(): Boolean { return aborted.get() }

    /** Stop all workers; partial data stays on disk. */
    fun abort() {
        if (!aborted.compareAndSet(false, true)) return
        synchronized(conns) {
            for (c in conns) c.close()
        }
    }

    private fun worker(mine: List<Int>) {
        var c: Connection? = null
        try {
            // Node.name(), not Build.MODEL: the same value today, but the name this device shows a
            // peer is Node's answer to give, and a second reader of the system property is a second
            // place to change when it stops being the property.
            val opened = route.data(cfg, sha256, Node.name())
            c = opened
            synchronized(conns) { conns.add(opened) }
            if (aborted.get()) return
            if (upload) push(opened, mine) else pull(opened, mine)
        } catch (e: Exception) {
            if (!aborted.get()) {
                failure = e.toString()
                Logger.i("transfer stream failed: $e")
            }
        } finally {
            c?.close()
            if (alive.decrementAndGet() == 0) finish()
        }
    }

    private fun push(c: Connection, mine: List<Int>) {
        val buf = ByteArray(Connection.CHUNK)
        Files.ChunkSource(ctx, checkNotNull(ref)).use { src ->
            for (idx in mine) {
                if (aborted.get()) return
                val len = src.read(idx, buf)
                c.sendChunk(idx, buf, len)
            }
        }
        c.sendJson(Connection.T_END, JSONObject().put("sha256", sha256))
        // the PC may answer ABORT while we were pushing; a short wait drains it, EOF/timeout is fine
        c.setSoTimeout(500)
        try { c.recv() } catch (ignored: Exception) {}
    }

    private fun pull(c: Connection, mine: List<Int>) {
        val rs = JSONArray()
        for (idx in mine) rs.put(JSONArray().put(idx).put(idx + 1))
        c.sendJson(Connection.T_PULL, JSONObject().put("sha256", sha256).put("ranges", rs))
        // read until the peer's END (not just until our chunks are in): closing earlier makes its
        // final send fail with a reset and log a spurious "connection forcibly closed"
        Frames.dataLoop(c, object : Frames.Data {
            /** [Transfer.abort] closes the sockets, but a worker between frames stops here. */
            override fun stopped(): Boolean {
                return this@Transfer.aborted.get()
            }

            override fun chunk(idx: Int, payload: ByteArray, off: Int, len: Int) {
                // Asked before the write, so "was there anything here" is asked of the state the
                // write is about to change. Several workers can be here at once and all of them can
                // see an empty file, so the question and the flag are one operation on the Partial's
                // own monitor (claimFirstChunk) rather than a read here and a compareAndSet on a
                // field of this Transfer: the push side has the same rule to enforce and no Transfer
                // to hang it on, and two implementations of one rule is how one side ends up with
                // none.
                val p = checkNotNull(partial)
                val wasFirst = p.claimFirstChunk()
                p.write(idx, payload, off, len)
                val first = firstChunk
                if (wasFirst && first != null) first.run()
            }

            /**
             * The peer gave up. Recorded as well as logged: this worker is one of several, and the
             * flag is what stops the others asking for the rest of their stripes.
             */
            override fun aborted(f: Connection.Frame) {
                this@Transfer.aborted.set(true)
                val why: String = try {
                    Frames.json(f).optString("reason")
                } catch (e: Exception) {
                    "no reason given"
                }
                Logger.i("peer aborted the transfer: $why")
            }

            // PING is not answered here, and cannot arrive: this end opened the stream and is
            // pulling on it, so the peer is serving chunks and never has a reason to poll a socket
            // it is actively writing to. A stream the PEER opened can sit idle and does answer;
            // see FileExchange.serveData.
        })
    }

    private fun finish() {
        val complete = if (upload) !aborted.get() && failure == null else checkNotNull(partial).complete()
        val ms = Math.max(1L, System.currentTimeMillis() - t0)
        if (complete) {
            val bytes = if (upload) checkNotNull(ref).size else checkNotNull(partial).size
            Logger.i((if (upload) "uploaded " else "downloaded ") + name() + " in " + ms / 1000.0 + " s (" +
                    (bytes * 1000 / ms / 1024) + " KB/s)")
        } else if (aborted.get()) {
            Logger.i((if (upload) "upload" else "download") + " of " + name() + " stopped" +
                    (if (upload) "" else " (" + partial + ", kept for resume)"))
        } else {
            Logger.i((if (upload) "upload" else "download") + " of " + name() + " incomplete: " +
                    (if (failure != null) failure else partial.toString()))
        }
        done?.onDone(this, complete)
    }

    companion object {
        internal fun upload(ctx: Context, cfg: Config, route: PeerRoute, ref: Files.Ref, ranges: List<IntArray>,
                            done: Done?): Transfer {
            return Transfer(ctx, cfg, route, ref.sha256, true, ref, null, ranges, done)
        }

        internal fun download(ctx: Context, cfg: Config, route: PeerRoute, p: Files.Partial, done: Done?): Transfer {
            return Transfer(ctx, cfg, route, p.sha256, false, null, p, p.missing(), done)
        }
    }
}
