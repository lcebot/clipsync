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
 * <p>Opens a window: an eight-digit code, an ephemeral port, and an advertisement on
 * {@code _clipsync-pair._tcp} carrying the salt. Anything that can complete the handshake knows the
 * code — and then <b>the user is asked, by name, whether that device may have the key</b>. The
 * handshake proves possession of the code; the prompt is what makes a guessed code still not enough,
 * because a device the user has never heard of shows up under a name they do not recognise.
 *
 * <p><b>The port is asked for, not chosen.</b> {@code ServerSocket(0)} cannot collide, cannot fail
 * and needs no search, and the joiner never has to guess it because it is in the SRV record it just
 * discovered. There is no firewall on the device to pre-authorise, so a predictable number would buy
 * nothing at all. (The PC would want one, if it ever acted as provider: there a firewall rule has to
 * name a port in advance, which is why that side searches upwards from {@code port + 1} instead.)
 *
 * <p><b>Nothing is parsed before the channel is authenticated.</b> A caller with the wrong code gets
 * as far as exchanging plaintext nonces and no further: its first real frame fails to decrypt, which
 * is the same wall a wrong PSK meets on the ordinary port. That is what keeps the surface here to
 * two short JSON frames.
 */
final class PairProvider implements Closeable {
    /** What the UI needs to show, and the only way out of this class. */
    interface Listener {
        /**
         * A device completed the handshake and was given the key.
         *
         * <p><b>May fire more than once.</b> The window does not end on a success: setting up three
         * devices is the ordinary case, and closing after the first would mean opening a new window
         * and reading out a new code for each of the others — two minutes of work to save nothing.
         * One code, one window, as many devices as walk up to it.
         */
        void onPaired(String device, String type);

        /**
         * A caller proved it knows the code. May it have the key?
         *
         * <p><b>Called on the provider's own thread and expected to block</b> until the user has
         * answered — the accept loop serves one caller at a time anyway, so there is nothing else
         * this thread could usefully be doing, and the alternative (hand the key over and tell the
         * UI afterwards) is what this exists to replace. The implementation puts the question on the
         * main thread and waits for it here.
         *
         * <p>The label and type are the peer's own claims, straight out of its HELLO. They are not
         * authenticated by anything except the code, and that is precisely what makes them useful:
         * an attacker who guessed the code still has to appear under a name the person standing
         * there recognises.
         *
         * @return true to hand the key over. False — including on a timeout, see
         *         {@link PairProvider#ASK_TIMEOUT_MS} — means no, and <b>a no is not a wrong
         *         code</b>: it does
         *         not count towards {@link Pairing#MAX_TRIES}. Burning the window on the user's own
         *         refusal would turn "no, not that one" into "now read out a new code".
         */
        boolean onAskUser(String device, String type);

        /**
         * The window is over, whether or not anyone joined.
         *
         * @param burned true when {@link Pairing#MAX_TRIES} wrong codes ended it early, rather than
         *               the clock. The distinction is the user's: a timer running out invites
         *               another go, five wrong guesses is worth saying out loud.
         */
        void onClosed(boolean burned);
    }

    private final Context ctx;
    private final String pskHex;
    /**
     * The port the <em>service</em> listens on — not the pairing port, which is ephemeral.
     *
     * <p>Handed over with the key because it is the other half of being able to connect at all: two
     * devices that agree on the key and disagree on the port never meet. Everything else a peer
     * needs it can discover or negotiate; this one is a setting, and a setting the joiner has no way
     * to guess if the provider is not on the default.
     */
    private final int port;
    private final Listener listener;
    private final ServerSocket socket;
    private final byte[] key;
    /** Shown to the user, typed on the other device. */
    final String code;
    /**
     * When the window will close, by the same clock a countdown would read.
     *
     * <p>Published rather than recomputed by the UI, because the two would not agree: the key
     * derivation is deliberately slow — under a second now that it is scrypt rather than the
     * several seconds of PBKDF2 it was (see {@link Pairing#channelKey}), plus binding a socket and
     * registering an advertisement. A countdown started from "now" would run a second or so behind
     * by the end of a two-minute window, and would still be showing time left after the socket had
     * timed out. Smaller than it was; still not zero, and the fix costs nothing.
     */
    final long closesAt;
    private Mdns.Advert advert;
    private volatile boolean closed;
    private int failures;

    /**
     * Opens the window. Blocking work — the key derivation is deliberately slow, and binding and
     * advertising are network calls — so construct this off the main thread.
     *
     * @param pskHex the key to give away, as the configuration stores it
     */
    PairProvider(Context ctx, String pskHex, int port, Listener listener) throws Exception {
        this.ctx = ctx;
        this.pskHex = pskHex;
        this.port = port;
        this.listener = listener;
        this.code = Pairing.newCode();
        byte[] salt = Pairing.newSalt();
        // Once per window, not once per connection: being slow is the point of the exercise (see
        // Pairing.SCRYPT_N), so paying it per caller would let anyone on the network cost this
        // device up to a second and 16 MiB just by opening a socket — and cost it on the one thread
        // that serves everybody, since callers are handled serially. Cheaper than the four seconds
        // of PBKDF2 this used to be, and still not something to hand out for free.
        this.key = Pairing.channelKey(code, salt);

        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(0), 4);

        advert = Mdns.advertise(ctx, Pairing.SERVICE_TYPE, Node.name(), socket.getLocalPort(),
                Map.of(Pairing.TXT_VERSION, String.valueOf(Connection.PROTOCOL_VERSION),
                        Pairing.TXT_SALT, Crypto.toHex(salt)));

        // Stamped here, immediately before the accept loop starts counting: everything above this
        // line has already spent some of the window.
        closesAt = System.currentTimeMillis() + Pairing.WINDOW_MS;
        Thread t = new Thread(this::run, "clipsync-pair-provider");
        t.setDaemon(true);
        t.start();
        Logger.i("pairing: window open on port " + socket.getLocalPort() + " for "
                + Pairing.WINDOW_MS / 1000 + "s");
    }

    private void run() {
        boolean burned = false;
        int paired = 0;
        try {
            while (!closed) {
                // The REMAINING window, recomputed every round. A timeout set once and left alone
                // silently restarts the clock on every connection: accept() returns, the loop comes
                // back, and the next accept() waits another full two minutes from *that* moment. So
                // one probe at 1:50 bought the window another two minutes, the countdown reached
                // zero with the code still live, and nothing arrived to say it had expired — because
                // it had not. The advertised lifetime has to be the real one.
                long left = closesAt - System.currentTimeMillis();
                if (left <= 0) break;
                socket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
                Socket s;
                try {
                    s = socket.accept();
                } catch (SocketTimeoutException e) {
                    break;                      // the window ran out
                }
                // Serially, one caller at a time. Pairing is a thing a person does with devices in
                // front of them, so there is no concurrency to serve — and refusing to spawn a
                // thread per connection is what stops a flood from costing anything but a queue.
                Outcome o = serve(s);
                if (o == Outcome.PAIRED) {
                    paired++;
                    // A success does NOT end the window: the next device in the pile wants the same
                    // key, and the same code is still on screen. It does clear the strikes, because
                    // five wrong codes means someone guessing, and a caller who has just proved it
                    // knows the code is evidence that nobody was.
                    failures = 0;
                    continue;
                }
                // A refusal is not a strike. The caller knew the code — that is why the user was
                // asked at all — so counting it would let a mis-tap on "No" burn a window the user
                // is still standing in front of, and would let a device the user keeps declining
                // close the window on the devices queued behind it.
                if (o == Outcome.DECLINED) continue;
                if (++failures >= Pairing.MAX_TRIES) {
                    burned = true;
                    break;
                }
            }
        } catch (Exception e) {
            if (!closed) Logger.w("pairing: " + e);
        } finally {
            Logger.i("pairing: window closed after " + paired + " device(s)"
                    + (burned ? " — too many wrong codes" : ""));
            shut();
            // Only for an ending nobody asked for: a window the caller closed itself needs no
            // telling. Each success was announced as it happened.
            if (!closed) listener.onClosed(burned);
            closed = true;
        }
    }

    /** How one caller ended, and the three endings are three different things to do next. */
    private enum Outcome {
        /** The key was handed over. */
        PAIRED,
        /** The user said no, or did not answer. Costs the caller nothing but its connection. */
        DECLINED,
        /** Wrong code, wrong protocol, or a dropped connection: one of {@link Pairing#MAX_TRIES}. */
        FAILED
    }

    private Outcome serve(Socket s) {
        Connection c = null;
        try {
            c = Connection.pairAccept(s, key);
            Hello hello = c.readHello();
            if (!Hello.ROLE_PAIR.equals(hello.role)) throw new IllegalStateException("not a pairing client");
            // readHello raises the timeout to the session one — ninety seconds, which is right for a
            // peer that will be quiet between heartbeats and wrong for this, where callers are served
            // one at a time and a silent one is standing in front of the person actually pairing.
            c.setSoTimeout(HANDSHAKE_MS);
            if (c.recv().type != Connection.T_PAIR_ASK) throw new IllegalStateException("expected PAIR_ASK");

            // Between the ask and the answer, which is the only place it can go: before PAIR_ASK
            // there is nothing to show the user but "something connected", and after PAIR_KEY the
            // key has already left the device. Blocking here is fine — callers are served serially,
            // so there is no second one being kept waiting that was not already waiting.
            //
            // The socket read timeout is untouched on purpose: it is a per-read timeout, and no read
            // is in flight while we are asking. The window's own deadline is what bounds this, and
            // the listener's own timeout bounds it sooner.
            if (!listener.onAskUser(c.peerLabel, c.peerType)) {
                Logger.i("pairing: the user declined " + c.peerLabel + " (" + c.peerType + ")");
                return Outcome.DECLINED;
            }

            c.sendJson(Connection.T_PAIR_KEY, new JSONObject()
                    .put("psk", pskHex)
                    .put("port", port)
                    .put("device", Node.name())
                    .put("type", Node.type(ctx)));
            Logger.i("pairing: key given to " + c.peerLabel + " (" + c.peerType + ")");
            listener.onPaired(c.peerLabel, c.peerType);
            return Outcome.PAIRED;
        } catch (Exception e) {
            // Every failure is counted, including the ones that are not guesses — a dropped
            // connection looks exactly like a wrong code from here, and erring towards closing the
            // window early is the safe direction when the thing being guarded is the key itself.
            // (A refusal is the one ending that is NOT counted, and it returns above rather than
            // throwing, precisely so it cannot be swept in here.)
            Logger.i("pairing: attempt " + (failures + 1) + " failed: " + e);
            return Outcome.FAILED;
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
        // The channel key dies with the window. It is the code's only stretched form, so a heap
        // dump taken minutes later would otherwise still contain the thing that authenticates
        // handing over the PSK. Safe to do here and only here: the socket is closed above, so no
        // further caller can be served, and a Connection derives its per-direction keys from this
        // array once, at construction, into arrays of its own. (A pairing Connection does keep the
        // array as its `matchedSecret`, but nothing reads that on a pairing channel — it exists for
        // the key-rotation logic on ordinary links — and every such connection is closed by now.)
        //
        // Idempotent on purpose — shut() is reached both from close() and from the accept loop's
        // finally, and zeroing an already-zeroed array costs nothing.
        java.util.Arrays.fill(key, (byte) 0);
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

    /**
     * How long {@link Listener#onAskUser} may take before the answer is read as "no".
     *
     * <p>Enforced by the implementation rather than here, because the call is a blocking one and
     * there is no safe way to abandon it from this side. Twenty seconds: long enough to look up from
     * the phone, read a device name and decide, short enough that a prompt nobody is in the room for
     * does not hold the window — and the failure direction is refusal, which costs the caller
     * nothing but another connection.
     *
     * <p><b>It does not have to cover the joiner's key derivation</b>, and that is worth writing down
     * because the two look as though they might overlap. They cannot, and the reason is structural
     * rather than arithmetic: the joiner derives its channel key <em>before</em> it opens a socket
     * ({@link PairJoiner#join} derives, then dials), so by the time this question is asked the slow
     * part of the other end is already paid for. That was the argument when the derivation was four
     * seconds and it is the same argument now that scrypt has made it half of one — the separation
     * is what makes this timeout safe, not the size of the number on the other side of it. What this
     * timeout is spent against is one
     * {@link #HANDSHAKE_MS}-bounded read plus a person reading a dialog. The joiner's own wait is
     * the mirror image: it must be at least this long, because after PAIR_ASK it is waiting on a
     * human at this end.
     */
    static final long ASK_TIMEOUT_MS = 20_000;
}
