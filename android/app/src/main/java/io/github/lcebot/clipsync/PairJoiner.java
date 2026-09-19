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
 * is an ordinary node. (docs/p2p-plan.md §12)
 *
 * <p>Static, and deliberately so: unlike {@link PairProvider} there is no window to hold open and
 * nothing to close. A join is one attempt with one code — if it fails, the user tries again, and
 * <b>that attempt is one of the provider's five</b>, which is where the brute-force bound lives.
 * Nothing on this side needs to remember anything between tries.
 *
 * <p>Every method here blocks: a browse takes seconds and the key derivation is slow on purpose.
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

    /** The key, and who gave it, for the screen that says pairing worked. */
    static final class Result {
        final String pskHex, device, type;

        Result(String pskHex, String device, String type) {
            this.pskHex = pskHex; this.device = device; this.type = type;
        }
    }

    /**
     * Ask one provider for the key.
     *
     * <p>A wrong code does not produce a "wrong code" message, and cannot: the code <em>is</em> the
     * channel key, so getting it wrong fails at the first frame as a decrypt error. That is the
     * design working — there is no cheaper way to test a guess than by spending one of the
     * provider's attempts — and it is why the exception this throws says what the user can act on
     * rather than what the cipher reported.
     *
     * @throws IOException if the advertisement is unusable, nothing answers, or the code is wrong
     */
    static Result join(Context ctx, Mdns.Instance provider, String code) throws Exception {
        byte[] salt = Pairing.unhex(provider.attrs.get(Pairing.TXT_SALT));
        if (salt == null) {
            // Either not one of ours or a truncated record. Refusing beats guessing a salt: a wrong
            // one produces the same decrypt failure as a wrong code and would be blamed on the user.
            throw new IOException("that device is not offering to pair (no salt in its advertisement)");
        }
        byte[] key = Pairing.channelKey(code, salt);

        Network net = network(ctx);
        IOException last = null;
        // The addresses of ONE provider, in the order OnLink put them: several routes to the same
        // device, not several devices. Tried in turn rather than raced, because a pairing attempt is
        // not free — each failure that reaches the far end spends one of its five.
        for (InetSocketAddress addr : provider.addrs) {
            // The connect is separated from everything after it, and the split is exactly where the
            // meaning changes. Only reaching the address can fail in a way another address might
            // fix; once the socket is open we are talking to the device, and the nonce exchange
            // inside pairTo is plaintext — so a failure past this point is the code being wrong,
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
                Logger.i("pairing: got the key from " + m.optString("device", "?"));
                return new Result(psk, m.optString("device", "?"), m.optString("type", "?"));
            } catch (Exception e) {
                // Not split by exception type any more, and that was the bug: a provider that cannot
                // decrypt closes the socket, so a wrong code surfaces here as an EOFException —
                // an IOException, which the old catch read as "unreachable" and retried at the next
                // address before reporting that nothing answered. A right code and a wrong one were
                // indistinguishable in the message, and both spent every attempt.
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
     * written — a key that cannot be parsed never reaches the file.
     */
    static void apply(Context ctx, String pskHex) throws IOException {
        Properties v = new Properties();
        v.setProperty("psk", pskHex);
        v.setProperty("discovery", "true");
        Config.save(ctx, v);
    }
}
