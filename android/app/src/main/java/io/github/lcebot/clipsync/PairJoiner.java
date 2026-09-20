package io.github.lcebot.clipsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Properties;

/**
 * The device that wants the key.
 *
 * <p>Browses {@code _clipsync-pair._tcp}, connects to what it finds with a key derived from the
 * code the user typed, asks, and writes the answer into the configuration. From the next reload it
 * is an ordinary node.
 *
 * <p>Static, and deliberately so: unlike {@link PairProvider} there is no window to hold open and
 * nothing to close. A join is one attempt with one code; if it fails, the user tries again, and
 * <b>that attempt is one of the provider's five</b>, which is where the brute-force bound lives.
 * Nothing on this side needs to remember anything between tries.
 *
 * <p>Every method here blocks, and blocks for seconds rather than for a moment: a browse runs for a
 * fixed window, the key derivation is slow on purpose (under a second, see
 * {@link Pairing#SCRYPT_P}), and the wait for the provider's user to say yes is a person rather than
 * a network. Nothing in this class may be called from the main thread.
 */
final class PairJoiner {
    private PairJoiner() {}

    /** What was found on the LAN, and what the user picks between. */
    static List<Mdns.Instance> find(Context ctx, long timeoutMs) {
        return Mdns.discover(ctx, network(ctx), Pairing.SERVICE_TYPE, timeoutMs);
    }

    private static Network network(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm != null ? cm.getActiveNetwork() : null;
    }

    /** The key, the port it is used on, and who gave them. */
    static final class Result {
        final String pskHex, device, type;
        /** The provider's service port, or 0 if it did not say, in which case ours is left alone. */
        final int port;

        Result(String pskHex, int port, String device, String type) {
            this.pskHex = pskHex; this.port = port; this.device = device; this.type = type;
        }
    }

    /**
     * Ask one provider for the key.
     *
     * <p>A wrong code does not produce a "wrong code" message, and cannot: the code <em>is</em> the
     * channel key, so getting it wrong fails at the first frame as a decrypt error. That is the
     * design working, because there is no cheaper way to test a guess than by spending one of the
     * provider's attempts, which is why the exception this throws says what the user can act on
     * rather than what the cipher reported.
     *
     * <p>The work is in two parts: stretching the code (under a second of native scrypt, see
     * {@link Pairing#SCRYPT_P}), and only then does a socket open. {@code derived} is called on this
     * thread between the two, which is the only point at which a caller can tell the user which of
     * the two it is waiting on. It exists because "Connecting" shown over a derivation is a lie
     * about what the phone is doing, and the user's next move, assuming it hung and pressing
     * something, follows from believing it. <b>It is still worth having now that the first half is
     * short</b>: "short" is a mid-range phone's ~0.5 to 1 second, the callback costs one post, and the two labels
     * are also what makes a slow device's long first half explicable rather than alarming.
     *
     * @param derived run when the key is ready and the first connect is about to be attempted;
     *                on the calling thread, so an implementation posts and returns
     * @throws IOException if the advertisement is unusable, nothing answers, or the code is wrong
     */
    static Result join(Context ctx, Mdns.Instance provider, String code, Runnable derived)
            throws Exception {
        // Version first, because the cost of getting this wrong is paid by a person. On an ordinary
        // link a mismatch wastes one connection nobody is watching; here it wastes copying nine
        // digits across the room, typing them, and waiting a second for the key to derive, only to be
        // told "wrong code, or the window has closed" when the code was right. A missing `v` is not
        // a mismatch (an older provider may not send one) and is allowed through; the handshake
        // stays the authority either way. Mirrors clipsync_pair.find().
        String theirV = provider.attrs.get(Pairing.TXT_VERSION);
        if (theirV != null && !theirV.isEmpty()
                && !theirV.equals(String.valueOf(Connection.PROTOCOL_VERSION))) {
            throw new IOException("that device is running a different version of ClipSync"
                    + " (it speaks " + theirV + ", this one speaks " + Connection.PROTOCOL_VERSION + ")");
        }
        byte[] salt = Crypto.fromHex(provider.attrs.get(Pairing.TXT_SALT));
        if (salt == null) {
            // Either not one of ours or a truncated record. Refusing beats guessing a salt: a wrong
            // one produces the same decrypt failure as a wrong code and would be blamed on the user.
            throw new IOException("that device is not offering to pair (no salt in its advertisement)");
        }
        byte[] key = Pairing.channelKey(code, salt);
        if (derived != null) derived.run();
        try {
            return dial(ctx, provider, key);
        } finally {
            // The stretched code, gone as soon as the attempt is over, whether it succeeded, was
            // refused, or never reached anyone. It is worth a line because it is the expensive half
            // of the secret: reaching it from the digits again costs another full scrypt, and this
            // array is that payment sitting in the heap. Every Connection it was
            // handed to derived its own per-direction keys from it and is closed by now.
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** One pass over the provider's addresses with an already-derived channel key. */
    private static Result dial(Context ctx, Mdns.Instance provider, byte[] key) throws Exception {
        Network net = network(ctx);
        IOException last = null;
        // The addresses of ONE provider, in the order OnLink put them: several routes to the same
        // device, not several devices. Tried in turn rather than raced, because a pairing attempt is
        // not free, since each failure that reaches the far end spends one of its five.
        for (InetSocketAddress addr : provider.addrs) {
            // The connect is separated from everything after it, and the split is exactly where the
            // meaning changes. Only reaching the address can fail in a way another address might
            // fix; once the socket is open we are talking to the device, and the nonce exchange
            // inside pairTo is plaintext, so a failure past this point is the code being wrong,
            // which every address would report identically while spending one of the provider's
            // five attempts each time.
            Connection c;
            try {
                c = Connection.pairTo(addr, key, net);
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
                continue;
            }
            try {
                c.sendPairHello(ctx);
                c.send(Connection.T_PAIR_ASK);
                Connection.Frame f = c.recv();
                if (f.type != Connection.T_PAIR_KEY) throw new IOException("expected PAIR_KEY, got " + f.type);
                JSONObject m = new JSONObject(new String(f.payload, java.nio.charset.StandardCharsets.UTF_8));
                String psk = m.optString("psk", "");
                String bad = Config.checkPsk(psk);
                if (bad != null) throw new IOException("the key it sent is not usable: " + bad);
                // Taken only if it is a port at all: a provider that sends nonsense should not be
                // able to point this device at a port nothing is listening on, and leaving ours
                // alone is the failure that is easy to see and easy to fix.
                int port = m.optInt("port", 0);
                if (Config.checkPort(String.valueOf(port)) != null) port = 0;
                Logger.i("pairing: got the key from " + m.optString("device", "?")
                        + (port > 0 ? " (port " + port + ")" : ""));
                return new Result(psk, port, m.optString("device", "?"), m.optString("type", "?"));
            } catch (Exception e) {
                // Not split by exception type: a provider that cannot decrypt closes the socket, so
                // a wrong code surfaces here as an EOFException, indistinguishable from any other
                // IOException a dropped connection could produce. Reporting them the same way is
                // correct, because either way the attempt is spent and the user's only useful next move is
                // the same.
                throw new IOException("wrong code, or the pairing window has already closed", e);
            } finally {
                c.close();
            }
        }
        throw last != null ? last : new IOException("could not reach it");
    }

    /**
     * Write the key in, and turn on the switch that made this possible.
     *
     * <p>Discovery, because pairing <em>is</em> mDNS: a device that just found its peer by browsing
     * and is then left unable to browse would be reporting a setting the user never chose. It also
     * means a freshly paired device has a way to reach the other one without anybody typing an
     * address, which is the point of the exercise.
     *
     * <p>Goes through {@link Config#save}, so the whole configuration is validated before a byte is
     * written, so a key that cannot be parsed never reaches the file.
     */
    static void apply(Context ctx, Result r) throws IOException {
        Properties v = new Properties();
        // freshKey resets the whole schedule (activation time, successor, old-key ring), so the
        // paired key starts clean rather than inheriting the previous key's rotation state.
        Config.freshKey(v, r.pskHex);
        v.setProperty("discovery", "true");
        // Only when the provider named one. Writing a 0 would be worse than writing nothing: the
        // port is the one setting a joiner cannot discover, and overwriting a working value with a
        // guess turns a pairing into a device that is configured and unreachable.
        if (r.port > 0) v.setProperty("port", String.valueOf(r.port));
        Config.save(ctx, v);
    }
}
