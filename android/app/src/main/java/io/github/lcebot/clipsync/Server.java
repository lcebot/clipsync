package io.github.lcebot.clipsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The listening half of the device: connections other peers open to us.
 *
 * <p>Until now Android only dialled, and that shaped what the project could do far more than it
 * looks: two devices could only meet through one that had a stable address, which meant the PC, and
 * a phone and a tablet on the same Wi-Fi could not find each other at all with the PC switched off.
 * Neither of them has a name the other can be configured with. Only one of the two has to find the
 * other for both to be connected — so a listening socket plus an mDNS advertisement is the whole of
 * it. (docs/p2p-plan.md §13, phase 4)
 *
 * <p>What arrives here is either a <b>control</b> connection, which becomes an ordinary peer link
 * indistinguishable from one we dialled, or a <b>data</b> connection, which is one stream of one
 * file transfer. Which it is, is in the HELLO, so this reads it before dispatching — and that is why
 * the handshake was split in two ({@link Connection#readHello()} / {@link Connection#sendHello}):
 * the accepter has to know who is calling before it can answer.
 *
 * <p>The cost is recorded in §14: the attack surface grows from one open port on the PC to one on
 * every device. What limits it is that the PSK handshake completes before a single frame is parsed —
 * an unauthenticated peer gets 32 bytes of nonce read and nothing else — and that this pool is
 * bounded, so a flood of sockets cannot become a flood of threads.
 */
final class Server implements Closeable {
    /** What the listener needs from the service, and what it hands back. */
    interface Handler {
        /**
         * A control connection whose HELLO has been read and answered for. Runs the whole session;
         * returning ends it.
         */
        void onControl(Connection c) throws Exception;

        /** One data connection of one transfer: the peer will push chunks or pull them. */
        void onData(Connection c, JSONObject hello) throws Exception;

        Config config();
    }

    /**
     * Concurrent inbound connections.
     *
     * <p>Bounded, and rejecting rather than queueing: a queue would let an unauthenticated peer put
     * unbounded work in front of a real one, and an unbounded pool would let it put unbounded
     * threads on the phone. A handful of peers hold one control link each plus at most
     * {@code threads} data connections during a transfer, so this is generous for the real case and
     * still a ceiling for the other one.
     */
    private static final int MAX_INBOUND = 24;

    private final Context ctx;
    private final Handler handler;
    private final ServerSocket socket;
    private final ThreadPoolExecutor workers;
    private volatile boolean closed;

    /**
     * Bind and start accepting.
     *
     * @throws IOException if the port cannot be bound — which is not fatal to the service: a device
     *                     that cannot listen can still dial out, it just cannot be dialled.
     */
    static Server start(Context ctx, Handler h, int port) throws IOException {
        return new Server(ctx, h, port);
    }

    private Server(Context ctx, Handler h, int port) throws IOException {
        this.ctx = ctx;
        this.handler = h;
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            // The wildcard address, which is the IPv6 one on Android and accepts IPv4 through it.
            // Deliberately not pinned to a Network: the peers that reach us do so over whichever
            // interface they can, and a socket bound to one of them would refuse the rest.
            s.bind(new InetSocketAddress(port), 16);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        this.socket = s;
        this.workers = new ThreadPoolExecutor(0, MAX_INBOUND, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "clipsync-inbound");
            t.setDaemon(true);
            return t;
        });
        Thread t = new Thread(this::acceptLoop, "clipsync-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket s;
            try {
                s = socket.accept();
            } catch (IOException e) {
                if (closed || socket.isClosed()) return;      // the ordinary way this thread ends
                // Anything else is transient — out of descriptors, a connection reset between the
                // SYN and the accept. Returning here would silently end every future inbound
                // connection while the service went on looking healthy, which is the expensive
                // failure; a second of quiet and another try is the cheap one.
                Logger.w("accept failed: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                continue;
            }
            try {
                workers.execute(() -> serve(s));
            } catch (RejectedExecutionException e) {
                Logger.w("refusing " + s.getRemoteSocketAddress() + ": " + MAX_INBOUND + " inbound connections already");
                shut(s);
            }
        }
    }

    private void serve(Socket s) {
        String from = String.valueOf(s.getRemoteSocketAddress());
        Connection c = null;
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            c = Connection.accept(ctx, handler.config(), s, net);
            JSONObject hello = c.readHello();
            if ("data".equals(hello.optString("role"))) handler.onData(c, hello);
            else handler.onControl(c);
        } catch (Connection.SelfConnection e) {
            // Reached ourselves — a loopback address, or our own advertisement. Nothing to report:
            // the dialling side logs it and stops, and this end could see it once per attempt.
            Logger.i("inbound " + from + ": that is this device");
        } catch (Exception e) {
            Logger.i("inbound " + from + ": " + e);
        } finally {
            if (c != null) c.close();
            else shut(s);
        }
    }

    private static void shut(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
