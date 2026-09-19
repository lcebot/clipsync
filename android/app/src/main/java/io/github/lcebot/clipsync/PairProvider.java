package io.github.lcebot.clipsync;

import android.content.Context;

import org.json.JSONObject;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * The device that holds the key, offering it for a while.
 *
 * <p>Opens a window: a six-digit code, an ephemeral port, and an advertisement on
 * {@code _clipsync-pair._tcp} carrying the salt. Anything that can complete the handshake knows the
 * code, and the code is the whole of the authentication — so a peer that gets that far is handed the
 * PSK and the window closes. (docs/p2p-plan.md §12)
 *
 * <p><b>The port is asked for, not chosen.</b> {@code ServerSocket(0)} cannot collide, cannot fail
 * and needs no search, and the joiner never has to guess it because it is in the SRV record it just
 * discovered. There is no firewall on the device to pre-authorise, so a predictable number would buy
 * nothing at all. (The PC would want one, if it ever acted as provider — that is what §12's bounded
 * search from {@code port + 1} is for.)
 *
 * <p><b>Nothing is parsed before the channel is authenticated.</b> A caller with the wrong code gets
 * as far as exchanging plaintext nonces and no further: its first real frame fails to decrypt, which
 * is the same wall a wrong PSK meets on the ordinary port. That is what keeps the surface here to
 * two short JSON frames.
 */
final class PairProvider implements Closeable {
    /** What the UI needs to show, and the only way out of this class. */
    interface Listener {
        /** A device completed the handshake and was given the key. The window is over. */
        void onPaired(String device, String type);

        /**
         * The window ended without pairing anyone.
         *
         * @param burned true when {@link Pairing#MAX_TRIES} wrong codes ended it early, rather than
         *               the clock. The distinction is the user's: a timer running out invites
         *               another go, five wrong guesses is worth saying out loud.
         */
        void onClosed(boolean burned);
    }

    private final Context ctx;
    private final String pskHex;
    private final Listener listener;
    private final ServerSocket socket;
    private final byte[] key;
    /** Shown to the user, typed on the other device. */
    final String code;
    private Mdns.Advert advert;
    private volatile boolean closed;
    private int failures;

    /**
     * Opens the window. Blocking work — the key derivation is deliberately slow — so construct this
     * off the main thread.
     *
     * @param pskHex the key to give away, as the configuration stores it
     */
    PairProvider(Context ctx, String pskHex, Listener listener) throws Exception {
        this.ctx = ctx;
        this.pskHex = pskHex;
        this.listener = listener;
        this.code = Pairing.newCode();
        byte[] salt = Pairing.newSalt();
        // Once per window, not once per connection: 200 000 PBKDF2 rounds is the point of the
        // exercise, and paying it per caller would let anyone on the network cost this device a
        // tenth of a second by opening a socket.
        this.key = Pairing.channelKey(code, salt);

        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(0), 4);
        // The whole window on one timeout: accept() then returns by itself when the time is up, and
        // the alternative is a timer thread whose only job is to close a socket.
        socket.setSoTimeout((int) Pairing.WINDOW_MS);

        advert = Mdns.advertise(ctx, Pairing.SERVICE_TYPE, Node.name(), socket.getLocalPort(),
                Map.of(Pairing.TXT_VERSION, String.valueOf(Connection.PROTOCOL_VERSION),
                        Pairing.TXT_SALT, Pairing.hex(salt)));

        Thread t = new Thread(this::run, "clipsync-pair-provider");
        t.setDaemon(true);
        t.start();
        Logger.i("pairing: window open on port " + socket.getLocalPort() + " for "
                + Pairing.WINDOW_MS / 1000 + "s");
    }

    private void run() {
        boolean burned = false, paired = false;
        try {
            while (!closed) {
                Socket s;
                try {
                    s = socket.accept();
                } catch (SocketTimeoutException e) {
                    break;                      // the window simply ran out
                }
                // Serially, one caller at a time. Pairing is a thing a person does with two devices
                // in front of them, so there is no concurrency to serve — and refusing to spawn a
                // thread per connection is what stops a flood from costing anything but a queue.
                if (serve(s)) {
                    paired = true;
                    break;
                }
                if (++failures >= Pairing.MAX_TRIES) {
                    burned = true;
                    break;
                }
            }
        } catch (Exception e) {
            if (!closed) Logger.w("pairing: " + e);
        } finally {
            Logger.i("pairing: window closed" + (paired ? " — paired" : burned ? " — too many wrong codes" : ""));
            shut();
            // Exactly one ending is reported. The success was announced from serve(), and a window
            // the caller closed itself needs no telling — so this is only the two ways it can end
            // without anyone having asked: the clock, and five wrong codes.
            if (!paired && !closed) listener.onClosed(burned);
            closed = true;
        }
    }

    /** @return true when the key was handed over */
    private boolean serve(Socket s) {
        Connection c = null;
        try {
            c = Connection.pairAccept(s, key);
            JSONObject hello = c.readHello();
            if (!"pair".equals(hello.optString("role"))) throw new IllegalStateException("not a pairing client");
            // readHello raises the timeout to the session one — ninety seconds, which is right for a
            // peer that will be quiet between heartbeats and wrong for this, where callers are served
            // one at a time and a silent one is standing in front of the person actually pairing.
            c.setSoTimeout(HANDSHAKE_MS);
            if (c.recv().type != Connection.T_PAIR_ASK) throw new IllegalStateException("expected PAIR_ASK");

            c.sendJson(Connection.T_PAIR_KEY, new JSONObject()
                    .put("psk", pskHex)
                    .put("device", Node.name())
                    .put("type", Node.type(ctx)));
            Logger.i("pairing: key given to " + c.peerLabel + " (" + c.peerType + ")");
            listener.onPaired(c.peerLabel, c.peerType);
            return true;
        } catch (Exception e) {
            // Every failure is counted, including the ones that are not guesses — a dropped
            // connection looks exactly like a wrong code from here, and erring towards closing the
            // window early is the safe direction when the thing being guarded is the key itself.
            Logger.i("pairing: attempt " + (failures + 1) + " failed: " + e);
            return false;
        } finally {
            if (c != null) c.close();
            else try { s.close(); } catch (Exception ignored) { }
        }
    }

    private void shut() {
        Mdns.Advert a = advert;
        advert = null;
        if (a != null) a.close();
        try { socket.close(); } catch (Exception ignored) { }
    }

    /** Give up on the window early — the user closed the sheet, or turned discovery off. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        shut();                                  // accept() fails, and the thread unwinds
    }

    /**
     * How long one caller may hold the window while saying nothing.
     *
     * <p>Short, because callers are served one at a time: a silent connection here does not merely
     * waste a worker, it is in front of the person actually trying to pair.
     */
    private static final int HANDSHAKE_MS = 10_000;
}
