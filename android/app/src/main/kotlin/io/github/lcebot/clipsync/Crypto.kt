package io.github.lcebot.clipsync

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 + ChaCha20-Poly1305 (Conscrypt, API 28+). Mirrors clipsync.py.
 *
 * ## Why the nonce counter is private to [Sealer] and [Opener]
 *
 * A ChaCha20-Poly1305 nonce may never be reused under one key, because reuse does not degrade
 * the cipher, it breaks it, leaking the keystream and the authentication key together. Handing the
 * counter to the caller as an ordinary parameter would make that rule a convention ("increment it
 * inside the send lock, and nowhere else") for callers to keep. A convention is something a reader
 * has to know; a private field is something they cannot get wrong. [Sealer] and [Opener] own the
 * counter, expose no way to set it, and advance it only on success, so the one invariant that
 * matters is enforced by the type rather than by a comment.
 *
 * They also cache their [Cipher]: a data connection seals one 512 KiB frame after another, and a
 * provider lookup per frame is a cost with nothing to show for it.
 *
 * ## What the platform actually provides (checked, do not re-check)
 *
 * `Cipher.getInstance("ChaCha20-Poly1305")` resolves to Conscrypt: its provider registers
 * `Cipher.ChaCha20/Poly1305/NoPadding` to `OpenSSLAeadCipherChaCha20` and the alias
 * `Alg.Alias.Cipher.ChaCha20-Poly1305` pointing at it
 * (conscrypt/common/src/main/java/org/conscrypt/OpenSSLProvider.java, the
 * `putSymmetricCipherImplClass("ChaCha20/Poly1305/NoPadding", ...)` line and the alias below
 * it). Available since API 28, so minSdk 35 is safe. Do not pass a provider name; Android's own
 * guidance is to let the platform choose
 * (https://developer.android.com/privacy-and-security/cryptography).
 *
 * The implementation is BoringSSL's `EVP_AEAD_chacha20_poly1305` behind
 * `OpenSSLAeadCipher`. Three consequences this file depends on:
 *
 * - **The nonce is exactly 12 bytes and is passed as an [IvParameterSpec].**
 *   `OpenSSLAeadCipher.engineInitInternal` accepts a `GCMParameterSpec` (via
 *   `Platform.fromGCMParameterSpec`) or an `IvParameterSpec`, and *anything
 *   else, including `javax.crypto.spec.ChaCha20ParameterSpec`, falls into the
 *   `else` branch that sets `iv = null`*, which for ENCRYPT_MODE silently
 *   generates a random nonce instead of the one you asked for, and for DECRYPT_MODE throws
 *   "IV must be specified". So: [IvParameterSpec], never ChaCha20ParameterSpec. An iv of
 *   any length other than `EVP_AEAD_nonce_length` (12) is rejected with
 *   InvalidAlgorithmParameterException.
 * - **The tag is 16 bytes and is not configurable.** With an IvParameterSpec the tag length
 *   defaults to `DEFAULT_TAG_SIZE_BITS` = 128, and
 *   `OpenSSLAeadCipherChaCha20.getOutputSizeForFinal` hard-codes `inputLen + 16`
 *   for encryption and `max(0, inputLen - 16)` for decryption. Ciphertext length is
 *   therefore always plaintext + 16, which is what the framing here and in clipsync.py assumes.
 * - **An encrypting instance must be re-initialised before every `doFinal`.**
 *   `doFinalInternal` sets `mustInitialize = true` after a successful seal, and the
 *   next call without an intervening `init` throws `IllegalStateException("Cannot
 *   re-use same key and IV for multiple encryptions")`. Conscrypt additionally remembers the
 *   last (key, iv) pair and throws InvalidAlgorithmParameterException if you init with the
 *   same one twice. Both are satisfied here because every seal inits with a fresh counter; the
 *   provider is, in effect, a second enforcement of the same invariant the counter enforces.
 *
 * None of this is thread-safe: `OpenSSLCipher` keeps the key, the iv, the mode and an
 * input buffer as plain instance fields. That is the mechanical reason for the thread-confinement
 * obligation documented on [Sealer] and [Opener].
 */
object Crypto {
    /**
     * The one RNG for the whole app.
     *
     * `new SecureRandom()` and nothing else, deliberately:
     *
     * - **No `setSeed`.** A default SecureRandom on Android is already seeded from the
     *   kernel pool; calling setSeed before first use is the classic way to make it
     *   *worse*, and seeding from a timestamp or an ANDROID_ID is the textbook weak-PRNG
     *   finding (https://developer.android.com/privacy-and-security/risks/weak-prng).
     * - **Not `getInstanceStrong()`.** On Android it is not the blocking /dev/random
     *   source it is on a server JRE; it resolves to the same AndroidOpenSSL/urandom-backed
     *   implementation, so it buys nothing and only adds a lookup. It does not block here, but
     *   relying on that is relying on an Android-specific detail for no gain.
     * - **Not `SecureRandom.getInstance("SHA1PRNG", "Crypto")`.** The Crypto provider
     *   does not exist from API 28 on, and that call throws NoSuchProviderException
     *   (https://developer.android.com/privacy-and-security/cryptography).
     *
     * Shared across threads on purpose: SecureRandom's `nextBytes` is thread-safe.
     */
    val RNG = SecureRandom()

    private val HEX = "0123456789abcdef".toCharArray()

    /** Lower-case hex, the form every key, salt and digest in this project travels and is stored in. */
    fun toHex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            out[2 * i] = HEX[(b[i].toInt() shr 4) and 0xf]
            out[2 * i + 1] = HEX[b[i].toInt() and 0xf]
        }
        return String(out)
    }

    /**
     * Hex back to bytes, **leniently**: anything that is not an even-length run of hex digits is
     * `null`, not an exception.
     *
     * Deliberately lenient rather than throwing. Every caller here is reading a value that arrived
     * from somewhere it does not control, such as a configuration file, a mDNS TXT record, or a key
     * ring an authenticated peer can add to, and none of them wants the whole operation abandoned because
     * one entry is bad: a single malformed key in an accepted-key ring must not fail every inbound
     * connection. Returning null lets each caller skip the one bad entry and carry on, which is what
     * all of them actually want.
     *
     * @return the bytes, or null if `s` is null, odd-length, empty or not all hex digits
     */
    fun fromHex(s: String?): ByteArray? {
        if (s == null || s.isEmpty() || (s.length and 1) != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** A fresh 256-bit PSK, in the 64 hex characters the configuration stores. */
    fun randomPskHex(): String {
        val b = ByteArray(32)
        RNG.nextBytes(b)
        return toHex(b)
    }

    /**
     * HKDF-SHA256, RFC 5869, written out by hand because the platform does not offer it.
     *
     * Checked, so the next reader does not have to: Android has no HKDF in its public crypto
     * API. Conscrypt's provider registers no `KDF` or `SecretKeyFactory` for it, because
     * the HKDF it contains is internal to its HPKE support, and `javax.crypto.KDF` is JDK 24/25
     * (JEP 478 / JEP 510, https://openjdk.org/jeps/510), which no Android API level ships. The
     * alternatives are all third-party (Tink's `com.google.crypto.tink.subtle.Hkdf` and
     * similar); pulling in a crypto dependency for twenty lines of HMAC is not worth it, so this
     * stays. What follows is the RFC's two steps verbatim:
     *
     * - **Extract** (RFC 5869 §2.2): `PRK = HMAC-Hash(salt, IKM)`, where the salt is the
     *   HMAC *key* and the IKM is the message, which is the way round that is easy to
     *   get backwards. Salt is always 64 bytes here (two 32-byte nonces), never empty, so the
     *   RFC's "substitute HashLen zeros when absent" case cannot arise, which matters because
     *   [SecretKeySpec] rejects a zero-length key with IllegalArgumentException.
     * - **Expand** (RFC 5869 §2.3): `T(0) = empty`, `T(i) = HMAC-Hash(PRK,
     *   T(i-1) ‖ info ‖ i)` with `i` a single byte counting from 1, and the output is
     *   the first `length` bytes of `T(1) ‖ T(2) ‖ …`. `Mac.doFinal` resets
     *   the instance for a new message under the same key, which is what lets one Mac serve
     *   every block.
     *
     * @param length at most 255 × 32 = 8160, the RFC's limit; beyond it the one-byte counter would
     *     wrap and silently repeat blocks. Every call here asks for 32.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (length < 0 || length > 255 * 32) throw IllegalArgumentException("bad HKDF length")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = Math.min(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    /** nonce = 4 zero bytes ‖ u64 big-endian counter. The same construction clipsync.py uses. */
    private fun nonce(counter: Long): ByteArray {
        return ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
    }

    /**
     * One direction of one connection, sealing frames in order.
     *
     * **Thread confinement is the caller's job and it is a real obligation.** The counter is a
     * plain `long` and the [Cipher] is shared between calls, so a Sealer must be used by
     * one thread at a time; [Connection] holds every call inside its send lock, which is what
     * makes that true there. It is not an `AtomicLong` because atomicity of the counter alone
     * would not be enough anyway: the increment and the encryption have to be one indivisible step
     * or two threads could still seal different frames under the same nonce.
     */
    class Sealer internal constructor(key: ByteArray) {
        private val cipher: Cipher
        private val key: SecretKeySpec

        /** Next nonce to use. Private, monotonic, and advanced only by a successful seal. */
        private var counter: Long = 0

        init {
            this.key = SecretKeySpec(key, "ChaCha20")
            this.cipher = Cipher.getInstance("ChaCha20-Poly1305")
        }

        fun seal(plaintext: ByteArray): ByteArray {
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce(counter)))
            val ct = cipher.doFinal(plaintext)
            counter++
            return ct
        }

        /**
         * Seal `len` bytes of `pt` straight into `out` at `outOff`.
         *
         * For the chunk path, which is the only one where this matters: 512 KiB a frame, eight
         * frames in flight, and the array-returning form above copies the payload once into a
         * plaintext buffer, once into the cipher's own output, and once more into the framed buffer.
         * Writing through the caller's array removes two of the three, which is megabytes of garbage
         * per file rather than a micro-optimisation. [Connection] keeps both arrays for the
         * life of the connection; this is what they are for.
         *
         * **The overload is supported here, checked against the implementation.**
         * `OpenSSLAeadCipher.engineDoFinal(byte[], int, int, byte[], int)` is a real override,
         * not the `UnsupportedOperationException` some providers use for the write-through
         * forms, and it ends in the same `EVP_AEAD_CTX_seal` call the array-returning form
         * does, minus the intermediate array.
         *
         * Two conditions it imposes, both met by the caller:
         *
         * - **`out.length - outOff` must be at least `len + 16`**, or it throws
         *   ShortBufferException before touching anything. The check is literally
         *   `getOutputSizeForFinal(inputLen) > output.length - outputOffset`, and for
         *   ChaCha20-Poly1305 encryption `getOutputSizeForFinal` is `inputLen + 16`
         *   exactly, with no padding and no block rounding, so `Cipher.getOutputSize()` is an
         *   exact figure here rather than the upper bound it is for block ciphers.
         *   [Connection.sendChunk] sizes its frame buffer to fit the largest chunk with
         *   nothing to spare, which is why that arithmetic must stay in step with this.
         * - **`pt` and `out` may be the same array**: BoringSSL forbids
         *   overlapping input and output, and Conscrypt handles it for us by copying the input
         *   range when `input == output` and the ranges overlap. Correct, but it
         *   reinstates the copy this method exists to avoid, so the caller passes two distinct
         *   arrays and should keep doing so.
         *
         * @return the number of ciphertext bytes written (plaintext plus the 16-byte tag)
         */
        fun sealInto(pt: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int): Int {
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce(counter)))
            val n = cipher.doFinal(pt, off, len, out, outOff)
            counter++
            return n
        }
    }

    /**
     * The receiving half: frames opened in the order they were sealed.
     *
     * Same thread rule as [Sealer]: [Connection] reads on one thread and that is the
     * whole of what keeps this safe.
     *
     * The counter advances **only on success**, which is what makes the multi-key path work
     * without any special entry point: an inbound connection builds one Opener per accepted key and
     * offers the first frame to each in turn. Every one that fails is still at counter 0, the one
     * that succeeds is at 1, and the connection simply keeps the winner. A failed decryption has
     * consumed no nonce because no frame was ever accepted under it.
     *
     * One Opener per candidate is also what keeps the provider happy. A failed AEAD open throws
     * out of `doFinalInternal` before its `reset()` runs, so the Conscrypt instance is
     * left mid-operation; it is only harmless because the very next use is an `init`, which
     * clears the input buffer and the counters itself. Sharing one Cipher across candidates and
     * relying on that would be betting on an implementation detail. Note also that the
     * `mustInitialize` latch Conscrypt sets after encrypting is *not* set after
     * decrypting, because decryption has no nonce-reuse hazard to guard, so nothing here depends on it.
     */
    class Opener internal constructor(key: ByteArray) {
        private val cipher: Cipher
        private val key: SecretKeySpec
        private var counter: Long = 0

        init {
            this.key = SecretKeySpec(key, "ChaCha20")
            this.cipher = Cipher.getInstance("ChaCha20-Poly1305")
        }

        fun open(ciphertext: ByteArray): ByteArray {
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce(counter)))
            val pt = cipher.doFinal(ciphertext)
            counter++
            return pt
        }
    }

    fun sha256Hex(text: String): String {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is mandatory on every Java platform; a build without it cannot run this app.
            throw IllegalStateException("SHA-256 unavailable", e)
        }
    }
}
