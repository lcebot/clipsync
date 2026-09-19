package io.github.lcebot.clipsync;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 + ChaCha20-Poly1305 (Conscrypt, API 28+). Mirrors clipsync.py.
 *
 * <h2>Why there is no {@code seal(key, counter, plaintext)} any more</h2>
 *
 * <p>A ChaCha20-Poly1305 nonce may never be reused under one key — reuse does not degrade the
 * cipher, it breaks it, leaking the keystream and the authentication key together. The old shape
 * handed that rule to the caller as an ordinary {@code long} parameter and relied on a convention
 * ("increment it inside the send lock, and nowhere else") to keep it. A convention is something a
 * reader has to know; a private field is something they cannot get wrong. {@link Sealer} and
 * {@link Opener} own the counter, expose no way to set it, and advance it only on success — so the
 * one invariant that matters is enforced by the type rather than by a comment.
 *
 * <p>They also cache their {@link Cipher}, which the static form could not: a data connection seals
 * one 512 KiB frame after another and was paying for a provider lookup on each.
 *
 * <h2>What the platform actually provides (checked, do not re-check)</h2>
 *
 * <p>{@code Cipher.getInstance("ChaCha20-Poly1305")} resolves to Conscrypt: its provider registers
 * {@code Cipher.ChaCha20/Poly1305/NoPadding -> OpenSSLAeadCipherChaCha20} and the alias
 * {@code Alg.Alias.Cipher.ChaCha20-Poly1305} pointing at it
 * (conscrypt/common/src/main/java/org/conscrypt/OpenSSLProvider.java, the
 * {@code putSymmetricCipherImplClass("ChaCha20/Poly1305/NoPadding", ...)} line and the alias below
 * it). Available since API 28, so minSdk 35 is safe. Do not pass a provider name — Android's own
 * guidance is to let the platform choose
 * (https://developer.android.com/privacy-and-security/cryptography).
 *
 * <p>The implementation is BoringSSL's {@code EVP_AEAD_chacha20_poly1305} behind
 * {@code OpenSSLAeadCipher}. Three consequences this file depends on:
 *
 * <ul>
 *   <li><b>The nonce is exactly 12 bytes and is passed as an {@link IvParameterSpec}.</b>
 *       {@code OpenSSLAeadCipher.engineInitInternal} accepts a {@code GCMParameterSpec} (via
 *       {@code Platform.fromGCMParameterSpec}) or an {@code IvParameterSpec}, and <em>anything
 *       else, including {@code javax.crypto.spec.ChaCha20ParameterSpec}, falls into the
 *       {@code else} branch that sets {@code iv = null}</em> — which for ENCRYPT_MODE silently
 *       generates a random nonce instead of the one you asked for, and for DECRYPT_MODE throws
 *       "IV must be specified". So: {@link IvParameterSpec}, never ChaCha20ParameterSpec. An iv of
 *       any length other than {@code EVP_AEAD_nonce_length} (12) is rejected with
 *       InvalidAlgorithmParameterException.
 *   <li><b>The tag is 16 bytes and is not configurable.</b> With an IvParameterSpec the tag length
 *       defaults to {@code DEFAULT_TAG_SIZE_BITS} = 128, and
 *       {@code OpenSSLAeadCipherChaCha20.getOutputSizeForFinal} hard-codes {@code inputLen + 16}
 *       for encryption and {@code max(0, inputLen - 16)} for decryption. Ciphertext length is
 *       therefore always plaintext + 16, which is what the framing here and in clipsync.py assumes.
 *   <li><b>An encrypting instance must be re-initialised before every {@code doFinal}.</b>
 *       {@code doFinalInternal} sets {@code mustInitialize = true} after a successful seal, and the
 *       next call without an intervening {@code init} throws {@code IllegalStateException("Cannot
 *       re-use same key and IV for multiple encryptions")}. Conscrypt additionally remembers the
 *       previous (key, iv) pair and throws InvalidAlgorithmParameterException if you init with the
 *       same one twice. Both are satisfied here because every seal inits with a fresh counter — the
 *       provider is, in effect, a second enforcement of the same invariant the counter enforces.
 * </ul>
 *
 * <p>None of this is thread-safe: {@code OpenSSLCipher} keeps the key, the iv, the mode and an
 * input buffer as plain instance fields. That is the mechanical reason for the thread-confinement
 * obligation documented on {@link Sealer} and {@link Opener}.
 */
public final class Crypto {
    private Crypto() {}

    /**
     * The one RNG for the whole app.
     *
     * <p>{@code new SecureRandom()} and nothing else, deliberately:
     *
     * <ul>
     *   <li><b>No {@code setSeed}.</b> A default SecureRandom on Android is already seeded from the
     *       kernel pool; calling setSeed before first use is the classic way to make it
     *       <em>worse</em>, and seeding from a timestamp or an ANDROID_ID is the textbook weak-PRNG
     *       finding (https://developer.android.com/privacy-and-security/risks/weak-prng).
     *   <li><b>Not {@code getInstanceStrong()}.</b> On Android it is not the blocking /dev/random
     *       source it is on a server JRE — it resolves to the same AndroidOpenSSL/urandom-backed
     *       implementation — so it buys nothing and only adds a lookup. It does not block here, but
     *       relying on that is relying on an Android-specific detail for no gain.
     *   <li><b>Not {@code SecureRandom.getInstance("SHA1PRNG", "Crypto")}.</b> The Crypto provider
     *       was removed in API 28 and that call now throws NoSuchProviderException
     *       (https://developer.android.com/privacy-and-security/cryptography).
     * </ul>
     *
     * <p>Shared across threads on purpose: SecureRandom's {@code nextBytes} is thread-safe.
     */
    public static final SecureRandom RNG = new SecureRandom();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Lower-case hex, the form every key, salt and digest in this project travels and is stored in. */
    public static String toHex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX[(b[i] >> 4) & 0xf];
            out[2 * i + 1] = HEX[b[i] & 0xf];
        }
        return new String(out);
    }

    /**
     * Hex back to bytes, <b>leniently</b>: anything that is not an even-length run of hex digits is
     * {@code null}, not an exception.
     *
     * <p>Deliberately not the strict parse it replaced. Every caller here is reading a value that
     * arrived from somewhere it does not control — a configuration file, a mDNS TXT record, a key
     * ring an authenticated peer can add to — and exactly one of them wants the whole operation
     * abandoned when one entry is bad. The strict version threw from inside the accepted-key loop,
     * so a single malformed key anywhere in the ring made <em>every</em> inbound connection fail
     * before a byte was read, permanently and across restarts. Returning null lets each caller skip
     * the one bad entry and carry on, which is what all of them actually want.
     *
     * @return the bytes, or null if {@code s} is null, odd-length, empty or not all hex digits
     */
    public static byte[] fromHex(String s) {
        if (s == null || s.isEmpty() || (s.length() & 1) != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** A fresh 256-bit PSK, in the 64 hex characters the configuration stores. */
    public static String randomPskHex() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return toHex(b);
    }

    /**
     * HKDF-SHA256, RFC 5869, written out by hand because the platform does not offer it.
     *
     * <p>Checked, so the next reader does not have to: Android has no HKDF in its public crypto
     * API. Conscrypt's provider registers no {@code KDF} or {@code SecretKeyFactory} for it — the
     * HKDF it contains is internal to its HPKE support — and {@code javax.crypto.KDF} is JDK 24/25
     * (JEP 478 / JEP 510, https://openjdk.org/jeps/510), which no Android API level ships. The
     * alternatives are all third-party (Tink's {@code com.google.crypto.tink.subtle.Hkdf} and
     * similar); pulling in a crypto dependency for twenty lines of HMAC is not worth it, so this
     * stays. What follows is the RFC's two steps verbatim:
     *
     * <ul>
     *   <li><b>Extract</b> (RFC 5869 §2.2): {@code PRK = HMAC-Hash(salt, IKM)} — the salt is the
     *       HMAC <em>key</em> and the IKM is the message, which is the way round that is easy to
     *       get backwards. Salt is always 64 bytes here (two 32-byte nonces), never empty, so the
     *       RFC's "substitute HashLen zeros when absent" case cannot arise — which matters because
     *       {@link SecretKeySpec} rejects a zero-length key with IllegalArgumentException.
     *   <li><b>Expand</b> (RFC 5869 §2.3): {@code T(0) = empty}, {@code T(i) = HMAC-Hash(PRK,
     *       T(i-1) ‖ info ‖ i)} with {@code i} a single byte counting from 1, and the output is
     *       the first {@code length} bytes of {@code T(1) ‖ T(2) ‖ …}. {@code Mac.doFinal} resets
     *       the instance for a new message under the same key, which is what lets one Mac serve
     *       every block.
     * </ul>
     *
     * @param length at most 255 × 32 = 8160, the RFC's limit — beyond it the one-byte counter would
     *     wrap and silently repeat blocks. Every call here asks for 32.
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        if (length < 0 || length > 255 * 32) throw new IllegalArgumentException("bad HKDF length");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    /** nonce = 4 zero bytes ‖ u64 big-endian counter. The same construction clipsync.py uses. */
    private static byte[] nonce(long counter) {
        return ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
    }

    /**
     * One direction of one connection, sealing frames in order.
     *
     * <p><b>Thread confinement is the caller's job and it is a real obligation.</b> The counter is a
     * plain {@code long} and the {@link Cipher} is shared between calls, so a Sealer must be used by
     * one thread at a time — {@link Connection} holds every call inside its send lock, which is what
     * makes that true there. It is not an {@code AtomicLong} because atomicity of the counter alone
     * would not be enough anyway: the increment and the encryption have to be one indivisible step
     * or two threads could still seal different frames under the same nonce.
     */
    public static final class Sealer {
        private final Cipher cipher;
        private final SecretKeySpec key;
        /** Next nonce to use. Private, monotonic, and advanced only by a successful seal. */
        private long counter;

        Sealer(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "ChaCha20");
            this.cipher = Cipher.getInstance("ChaCha20-Poly1305");
        }

        public byte[] seal(byte[] plaintext) throws GeneralSecurityException {
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce(counter)));
            byte[] ct = cipher.doFinal(plaintext);
            counter++;
            return ct;
        }

        /**
         * Seal {@code len} bytes of {@code pt} straight into {@code out} at {@code outOff}.
         *
         * <p>For the chunk path, which is the only one where this matters: 512 KiB a frame, eight
         * frames in flight, and the array-returning form above copies the payload once into a
         * plaintext buffer, once into the cipher's own output, and once more into the framed buffer.
         * Writing through the caller's array removes two of the three, which is megabytes of garbage
         * per file rather than a micro-optimisation. {@link Connection} keeps both arrays for the
         * life of the connection; this is what they are for.
         *
         * <p><b>The overload is supported here, checked against the implementation.</b>
         * {@code OpenSSLAeadCipher.engineDoFinal(byte[], int, int, byte[], int)} is a real override
         * — not the {@code UnsupportedOperationException} some providers use for the write-through
         * forms — and it ends in the same {@code EVP_AEAD_CTX_seal} call the array-returning form
         * does, minus the intermediate array.
         *
         * <p>Two conditions it imposes, both met by the caller:
         *
         * <ol>
         *   <li><b>{@code out.length - outOff} must be at least {@code len + 16}</b>, or it throws
         *       ShortBufferException before touching anything. The check is literally
         *       {@code getOutputSizeForFinal(inputLen) > output.length - outputOffset}, and for
         *       ChaCha20-Poly1305 encryption {@code getOutputSizeForFinal} is {@code inputLen + 16}
         *       exactly — no padding, no block rounding, so {@code Cipher.getOutputSize()} is an
         *       exact figure here rather than the upper bound it is for block ciphers.
         *       {@link Connection#sendChunk} sizes its frame buffer to fit the largest chunk with
         *       nothing to spare, which is why that arithmetic must stay in step with this.
         *   <li><b>{@code pt} and {@code out} may be the same array</b> — BoringSSL forbids
         *       overlapping input and output, and Conscrypt handles it for us by copying the input
         *       range when {@code input == output} and the ranges overlap. Correct, but it
         *       reinstates the copy this method exists to avoid, so the caller passes two distinct
         *       arrays and should keep doing so.
         * </ol>
         *
         * @return the number of ciphertext bytes written (plaintext plus the 16-byte tag)
         */
        public int sealInto(byte[] pt, int off, int len, byte[] out, int outOff)
                throws GeneralSecurityException {
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce(counter)));
            int n = cipher.doFinal(pt, off, len, out, outOff);
            counter++;
            return n;
        }
    }

    /**
     * The receiving half: frames opened in the order they were sealed.
     *
     * <p>Same thread rule as {@link Sealer} — {@link Connection} reads on one thread and that is the
     * whole of what keeps this safe.
     *
     * <p>The counter advances <b>only on success</b>, which is what makes the multi-key path work
     * without any special entry point: an inbound connection builds one Opener per accepted key and
     * offers the first frame to each in turn. Every one that fails is still at counter 0, the one
     * that succeeds is at 1, and the connection simply keeps the winner. A failed decryption has
     * consumed no nonce because no frame was ever accepted under it.
     *
     * <p>One Opener per candidate is also what keeps the provider happy. A failed AEAD open throws
     * out of {@code doFinalInternal} before its {@code reset()} runs, so the Conscrypt instance is
     * left mid-operation; it is only harmless because the very next use is an {@code init}, which
     * clears the input buffer and the counters itself. Sharing one Cipher across candidates and
     * relying on that would be betting on an implementation detail. Note also that the
     * {@code mustInitialize} latch Conscrypt sets after encrypting is <em>not</em> set after
     * decrypting — decryption has no nonce-reuse hazard to guard — so nothing here depends on it.
     */
    public static final class Opener {
        private final Cipher cipher;
        private final SecretKeySpec key;
        private long counter;

        Opener(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "ChaCha20");
            this.cipher = Cipher.getInstance("ChaCha20-Poly1305");
        }

        public byte[] open(byte[] ciphertext) throws GeneralSecurityException {
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce(counter)));
            byte[] pt = cipher.doFinal(ciphertext);
            counter++;
            return pt;
        }
    }

    public static String sha256Hex(String text) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform; a build without it cannot run this app.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
