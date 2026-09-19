package io.github.lcebot.clipsync;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Getting the PSK onto a second device without typing 64 hex characters into it.
 *
 * <p>One device generates the key and advertises {@code _clipsync-pair._tcp} while a window is open;
 * another finds it, connects, and is given the key. The transfer cannot be protected by the PSK —
 * the PSK is what is being transferred — so <b>a six-digit code is the whole of the
 * authentication</b>: shown on the device that holds the key, typed on the device that wants it.
 * (docs/p2p-plan.md §12)
 *
 * <p>This class is the part with no I/O in it: the code, the key the code turns into, and the limits
 * on how long and how often it may be tried.
 *
 * <h2>Why the code is stretched, where §12 said only "HKDF"</h2>
 *
 * A six-digit code is a million possibilities. §12 bounds <em>online</em> guessing properly — five
 * failed handshakes end the window — but a listener on the same Wi-Fi can record the exchange and
 * try all million <em>offline</em>, at whatever rate its hardware allows. With a plain HKDF that is
 * a few seconds of CPU for the key to the user's clipboard, permanently. The window being short does
 * not help: the recording outlives it.
 *
 * <p>So the code goes through PBKDF2 first. It does not remove the attack — nothing short of a PAKE
 * does, because a low-entropy secret is a low-entropy secret — but it moves the cost from seconds to
 * something a casual listener will not pay, for about a tenth of a second on each device, once. The
 * honest summary is: <b>online guessing is bounded by design, offline guessing is only made
 * expensive.</b> A PAKE (SPAKE2 and friends) is the real answer and is a different project.
 *
 * <p>The salt is per-pairing and published in the advertisement's TXT record, which is what stops
 * one precomputed table from covering every pairing anyone ever performs. Public is fine — a salt is
 * not a secret, it only has to be unique.
 */
final class Pairing {
    /** Its own service type, so ordinary discovery never has to filter it out and vice versa. */
    static final String SERVICE_TYPE = "_clipsync-pair._tcp.";
    /** TXT keys on the advertisement: the protocol version, and the salt below. */
    static final String TXT_VERSION = "v", TXT_SALT = "s";

    /**
     * How long a pairing window stays open.
     *
     * <p>Long enough to walk to the other device and type six digits, short enough that an
     * advertisement nobody is watching does not stay on the network. It is a bound on opportunity,
     * not on brute force — {@link #MAX_TRIES} is that.
     */
    static final long WINDOW_MS = 2 * 60_000;

    /**
     * Failed handshakes before the code is burned and the window closes.
     *
     * <p>Five, and the count is of <em>handshake</em> failures, which is the only thing a wrong code
     * can produce: the channel's authentication is the check, so there is no separate "wrong code"
     * message to count and no way for a caller to fail more cheaply than by using up one of these.
     */
    static final int MAX_TRIES = 5;

    /** PBKDF2 rounds. See the class comment: about 100 ms on a phone, paid once per pairing. */
    private static final int ROUNDS = 200_000;
    private static final int SALT_BYTES = 16;

    private Pairing() {}

    /**
     * A fresh six-digit code.
     *
     * <p>Rejection-sampled rather than reduced modulo a million: {@code nextInt()} mod 1_000_000
     * favours the low values, and while the bias is tiny it is free to avoid and impossible to
     * explain away afterwards. Formatted with leading zeros, because "007421" is six digits and
     * 7421 is not — a code the user reads as four digits is a code they will type as four.
     */
    static String newCode() {
        SecureRandom rng = Crypto.RNG;
        int n;
        do {
            n = rng.nextInt();
        } while (n == Integer.MIN_VALUE);
        n = Math.abs(n);
        // 2^31-1 is not a multiple of 1e6, so the top partial block is discarded rather than folded.
        int limit = Integer.MAX_VALUE - (Integer.MAX_VALUE % 1_000_000);
        while (n >= limit) {
            n = Math.abs(rng.nextInt());
        }
        return String.format(java.util.Locale.US, "%06d", n % 1_000_000);
    }

    /** A fresh salt for one pairing window, published in the advertisement. */
    static byte[] newSalt() {
        byte[] s = new byte[SALT_BYTES];
        Crypto.RNG.nextBytes(s);
        return s;
    }

    /**
     * The channel key both ends derive from the code.
     *
     * <p>Handed to {@link Connection} exactly where the PSK would go, which is what makes the rest
     * of the channel — nonces, per-direction keys, AEAD, replay resistance — come along unchanged.
     * Slow on purpose; call it off the main thread.
     *
     * @param code six digits as the user sees them, leading zeros included
     * @param salt from the advertisement, so no table covers two pairings
     */
    static byte[] channelKey(String code, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ROUNDS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // Both the algorithm and the parameters are fixed and supported since API 26; a failure
            // here is not a condition to recover from, it is a build that cannot pair at all.
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    /** Hex for the TXT record, and back. A salt travels as text because a TXT value is text. */
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** @return null if it is not even-length hex — a malformed advertisement, not an exception path */
    static byte[] unhex(String s) {
        if (s == null || s.isEmpty() || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
