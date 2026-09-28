package io.github.lcebot.clipsync

import java.security.SecureRandom
import java.security.spec.KeySpec

import javax.crypto.SecretKeyFactory

import androidx.annotation.Keep

/**
 * Getting the PSK onto a second device without typing 64 hex characters into it.
 *
 * One device generates the key and advertises `_clipsync-pair._tcp` while a window is open;
 * another finds it, connects, and is given the key. The transfer cannot be protected by the PSK,
 * because the PSK is what is being transferred, so **a nine-digit code is the whole of the
 * authentication**: shown on the device that holds the key, typed on the device that wants it.
 *
 * **The code is nine digits, and nothing else.** It is *shown* in groups of three, as
 * "123 456 789", because that is how a person reads a long number off a screen and says it out
 * loud. The spaces are for the eye only: [grouped] puts them in at the last moment, for
 * display, and nothing else in this project ever sees them. What [channelKey] stretches, and
 * what the two ends have to agree on bit for bit, is the nine digits themselves. Feeding a grouped
 * string to the derivation derives a different key and reports it to the user as a wrong code.
 *
 * This class is the part with no I/O in it: the code, the key the code turns into, and the limits
 * on how long and how often it may be tried.
 *
 * ## Why the code is stretched, not just hashed
 *
 * *Online* guessing is bounded properly, since five failed handshakes end the window, but a
 * listener on the same Wi-Fi can record the exchange and try every code *offline*, at
 * whatever rate its hardware allows. With a plain HKDF that is seconds of CPU for the key to the
 * user's clipboard, permanently. The window being short does not help: the recording outlives it.
 *
 * Two knobs answer that, and they multiply. The code is **nine** digits rather than six,
 * which is the thousandfold part: a million codes is half a minute of offline work on a laptop, a
 * billion is not. And the derivation is deliberately slow, which is the constant factor on top.
 * Nine is where it stops: the three characters over six are the ones [grouped] pays for by
 * printing them as three groups of three, which is a shape a person reads in one glance and says
 * aloud in three breaths; twelve would be a fourth group and a telephone number.
 *
 * So the code goes through **scrypt** first. It does not remove the attack; nothing short of a
 * PAKE does, because a low-entropy secret is a low-entropy secret, but it moves the cost from
 * seconds to something a casual listener will not pay. The price on this side is **under a
 * second** (see [SCRYPT_N] for where the parameters come from), paid once per pairing. The
 * honest summary is: **online guessing is bounded by design, offline guessing is only made
 * expensive.** A PAKE (SPAKE2 and friends) is the real answer and is a different project.
 *
 * ## What "expensive" is worth, in numbers
 *
 * The unit to measure in is: *how long does one consumer GPU need to walk the whole 10⁹ code
 * space?* Both figures below come from the same published RTX 4090 run of hashcat v6.2.6
 * (Chick3nman's benchmark gist), so they are comparable to each other rather than to a vendor claim.
 *
 * scrypt, N=2¹⁴, r=8, p=10. Hashcat mode 8900 measures scrypt at exactly N=16384, r=8, p=1 and
 * gets 7 126 H/s (modes 22700 and 27700 are the same parameters and report 7 156 and 7 107, three
 * independent confirmations of the same number). The p loop is p sequential ROMix calls over one
 * buffer, so p=10 divides that by 10: ≈713 H/s. 10⁹ ÷ 713 ≈ **16 days** to exhaust one RTX 4090
 * walking the whole code space, ~8 days to expect a hit.
 *
 * For scale: a plain PBKDF2-HMAC-SHA256 at 600 000 rounds, priced on the same hardware and the
 * same code space, would fall in **~21 hours**; hashcat mode 27500 measures PBKDF2-HMAC-SHA256
 * at 259 999 rounds at 30 676 H/s, and PBKDF2 is exactly linear in the round count, so 600 000
 * rounds is ≈13 300 H/s (mode 10900 at 999 rounds, 8 865.7 kH/s, scales to ≈14 800, and the two agree
 * within 10%, which is the cross-check that the scaling is honest). scrypt at these parameters is
 * roughly **50 000×** harder to exhaust than that baseline. The multiplier is not the interesting
 * part; *where* it comes from is. PBKDF2's state is a few dozen bytes, so a GPU runs as many
 * instances as it has cores. scrypt at these parameters needs 16 MiB of fast random-access memory
 * per instance, which caps a 24 GB card at ~1 500 concurrent hashes no matter how many cores it has,
 * and makes memory bandwidth the binding constraint. The same benchmark shows that wall directly:
 * mode 9300 is scrypt at the same N=16384 with r=1 (2 MiB) and runs at 83 890 H/s, so **8× the memory
 * costs the GPU 11.8×**, superlinearly, which is the memory-hardness doing something PBKDF2
 * cannot do at any round count.
 *
 * None of which makes it safe against somebody who wants it. A fortnight on one card is a day on
 * sixteen, and a day of rented cloud time is not a deterrent to anyone who has decided to spend it.
 * What this does achieve is making recording a pairing *on the off-chance* not worth the
 * electricity, which is the threat that is actually there. Against an attacker who is specifically
 * after this clipboard, the answer is not the code length; it is a PAKE, and that is a different
 * project.
 *
 * The salt is per-pairing and published in the advertisement's TXT record, which is what stops
 * one precomputed table from covering every pairing anyone ever performs. Public is fine; a salt is
 * not a secret, it only has to be unique.
 */
object Pairing {
    /** Its own service type, so ordinary discovery never has to filter it out and vice versa. */
    internal const val SERVICE_TYPE = "_clipsync-pair._tcp."

    /**
     * TXT keys on the advertisement: the protocol version, and the salt below.
     *
     * The salt travels as [Crypto.toHex] text, because a TXT value is text. It is read back
     * with [Crypto.fromHex], which answers null rather than throwing, because a truncated or foreign
     * advertisement is a thing to skip, not an exception to handle.
     */
    internal const val TXT_VERSION = "v"
    internal const val TXT_SALT = "s"

    /**
     * How long a pairing window stays open.
     *
     * Long enough to walk to the other device and type the code, short enough that an
     * advertisement nobody is watching does not stay on the network. It is a bound on opportunity,
     * not on brute force; [MAX_TRIES] is that.
     */
    internal const val WINDOW_MS: Long = 2 * 60_000L

    /**
     * Failed handshakes before the code is burned and the window closes.
     *
     * Five, and the count is of *handshake* failures, which is the only thing a wrong code
     * can produce: the channel's authentication is the check, so there is no separate "wrong code"
     * message to count and no way for a caller to fail more cheaply than by using up one of these.
     */
    internal const val MAX_TRIES = 5

    /**
     * How many digits [newCode] produces, and how many the field accepts once the spaces
     * are taken back out.
     *
     * A ninth glyph is a real cost to a user reading a code across a room, but the answer to that
     * is not a shorter code, it is a better-shaped one: nine digits are *shown* as three
     * groups of three ([grouped]), which keeps the code as easy to read aloud as a shorter
     * one while keeping the full factor of ten in the brute-force cost; see the class comment.
     *
     * **This counts digits, never the displayed spaces.** Nine is the length of what is
     * generated, what is compared, and what [channelKey] stretches; the field may hold
     * eleven characters, and what it holds is stripped before it is measured against this.
     */
    const val CODE_DIGITS = 9

    /**
     * scrypt's cost parameter N, and the anchor of the whole choice below.
     *
     * ### Why scrypt
     *
     * **Conscrypt does not implement PBKDF2 at all**: `SecretKeyFactory.getInstance(
     * "PBKDF2WithHmacSHA256")` resolves to the BouncyCastle copy Android bundles
     * (`com.android.org.bouncycastle`), pure Java with no native or hardware acceleration.
     * Measured, 310 000 rounds of `PBKDF2WithHmacSha256` take roughly 2.2 s on a Pixel 3
     * (Daniel Hugenroth, "Password hashing on Android"), so 600 000 rounds of PBKDF2 would cost
     * **about 4 s on a Pixel 3 and 1.5 to 2.5 s on a newer mid-range phone**, several seconds of a
     * frozen sheet for less brute-force resistance than a second of native scrypt buys, see the
     * class comment for the measured comparison.
     *
     * Conscrypt's provider registers `SecretKeyFactory.SCRYPT` (with the OID alias
     * `1.3.6.1.4.1.11591.4.11`), backed by BoringSSL's `EVP_PBE_scrypt`. In AOSP
     * `external/conscrypt` those three registration lines **first appear on
     * `android15-release`**; on `android14-release` and `android14-qpr3-release`
     * the `SecretKeyFactory` block is `DESEDE` plus its `TDEA` alias and nothing
     * else. That is the entire reason `minSdk` is 35; see the note on it in
     * `build.gradle.kts`, which also records what that costs in devices.
     *
     * The name is undocumented: `SCRYPT` appears nowhere in the supported-algorithm table
     * on `developer.android.com/reference/javax/crypto/SecretKeyFactory`, whose list stops at
     * the `PBKDF2withHmac*` family. That is a real risk and it is accepted with open eyes,
     * because the failure is loud rather than silent: `getInstance` throws
     * [java.security.NoSuchAlgorithmException] and [channelKey] turns it into an
     * [IllegalStateException]. A build that cannot pair is obvious on the first attempt.
     *
     * ### Where N, r and p come from
     *
     * Four constraints, and they pin the answer almost exactly:
     *
     * - **Under a second on a mid-range phone.** One ROMix at N=2¹⁴, r=8 is a few tens of
     *   milliseconds of native code; p=10 makes it ten of those, so roughly **0.5 to 1.0 s**.
     *   See [SCRYPT_P] for why that upper bound is where p stopped, and for the one
     *   measurement that would let it move.
     * - **16 MiB, and not one byte more.** The footprint is `128*r*N` = 16 MiB
     *   (BoringSSL also allocates `128*r*(p+1)` = 11 KiB for B and T, so ~16.01 MiB in
     *   total). It is a native allocation, outside the Java heap, so it costs no GC at all,
     *   but it is still 16 MiB of RSS on a phone that may have little to spare, and it is
     *   transient.
     * - **No implementation's memory cap is anywhere near it, and there is no way to raise one
     *   from here.** This matters more than it looks: the reflective KeySpec below has six
     *   fields and **`max_mem` is not one of them**, so whatever cap Conscrypt hands
     *   BoringSSL is fixed and unreachable from app code. BoringSSL's default
     *   (`SCRYPT_MAX_MEM` in `crypto/evp/scrypt.cc`) is `1024*1024*65` = 65
     *   MiB; OpenSSL's, which is what the PC end gets by default, is 32 MiB. 16 MiB clears both
     *   with room to spare. **N=2¹⁵ is the obvious alternative and is exactly why it is not
     *   used**: `128*r*N` is then precisely 32 MiB, which is precisely OpenSSL's
     *   default limit, i.e. a build that works on one end and throws "memory limit exceeded" on
     *   the other. Sitting on a documented cliff edge to buy ~10% is not a trade.
     * - **p, not a bigger N, spends the remaining time budget.** p is ten sequential ROMix
     *   calls over the same buffer: it multiplies the work by 10 without touching the memory
     *   footprint, and it slows a GPU attacker by exactly 10 too, so the trade is one-for-one.
     *   A bigger N would be marginally better per unit of defender time (it raises the
     *   attacker's memory *capacity* requirement as well as its bandwidth bill), but the
     *   point above forbids it, and p has the compensating virtue that N=2¹⁴, r=8, p=1 is
     *   exactly what hashcat mode 8900 benchmarks, so the attacker-side number in the class
     *   comment is a measurement divided by p rather than an extrapolation.
     *
     * [DK_BITS] is 256 because that is what [Connection] wants where the PSK would
     * go; nothing about scrypt suggests it.
     *
     * **Both ends must agree bit for bit**, and so must [CODE_DIGITS]: all of it feeds
     * the derivation, so two ends that disagree about any of it do not fail with "wrong parameters";
     * they derive different keys and the user is told the code was wrong. There is no negotiation
     * and deliberately none: the two ends ship together, and a parameter announced in a plaintext
     * advertisement is a parameter an attacker on the same Wi-Fi can ask to have lowered.
     * `clipsync_pair.py` carries the same four numbers and the same reasoning.
     *
     * Under a second is short, but not free: [channelKey] runs on a worker thread and the
     * screen says it is working. A mid-range phone is not the slowest phone, and the derivation is
     * not the only thing between the button and the answer.
     */
    private const val SCRYPT_N = 1 shl 14

    /** scrypt's block size r. 8 is the standard value and the one every published figure uses. */
    private const val SCRYPT_R = 8

    /**
     * scrypt's parallelisation parameter p, which is the whole of the time budget; see [SCRYPT_N].
     *
     * Ten, and the ceiling is a guess rather than a measurement. **Nobody has timed this on
     * a real phone yet.** The 0.5 to 1.0 s quoted above is an extrapolation from scrypt's own paper
     * (N=2¹⁴, r=8, p=1 ≈ 0.1 s on 2009 desktop hardware) to a modern phone's big core running
     * native BoringSSL. The budget is one second, the pessimistic end of that extrapolation at p=10
     * is exactly one second, and that is the entire reason p stops here rather than at 16: a
     * device slower than the guess blows the budget at p=10 already, and p=16 would blow it on a
     * device that matched the guess.
     *
     * So **this number deliberately leaves headroom on the table**. p is free security:
     * it is linear in attacker cost and does not touch the 16 MiB footprint, so every point of p is
     * 10% more GPU-days at no memory cost and no risk to either implementation's limits. Once
     * somebody has an actual figure, **raise it**: p=16 if the real number is at the fast end,
     * p=20 if it is faster still; the only ceiling is what a user will wait for.
     *
     * **How to measure:** wrap the [channelKey] call in `PairJoiner.join` in a
     * `SystemClock.elapsedRealtime()` pair and log the delta on the oldest and slowest device
     * the project cares about, with the screen on and the CPU not already warm from a build.
     *
     * If it turns out to be **slower** than the guess (a real measurement over ~1.2 s), drop
     * to **p=8**. Below 8 is not worth doing: at [CODE_DIGITS] digits, p=8 is already
     * 13 GPU-days, and the answer to wanting more than that is a PAKE, not a smaller p.
     *
     * Whatever it becomes, `clipsync_pair.SCRYPT_P` moves with it in the same commit.
     */
    private const val SCRYPT_P = 10

    /** Output length in **bits**: `ScryptSecretKeyFactory` divides by 8 itself. */
    private const val DK_BITS = 256
    private const val SALT_BYTES = 16

    /**
     * The six numbers scrypt needs, in the one shape Conscrypt will accept from outside itself.
     *
     * Conscrypt's own `org.conscrypt.ScryptKeySpec` is unreachable: on Android the library
     * is repackaged to `com.android.org.conscrypt` and sits behind the hidden-API wall. But
     * `ScryptSecretKeyFactory.engineGenerateSecret` does not require it. After the
     * `instanceof ScryptKeySpec` branch it falls through to a reflective one, whose comment in
     * the AOSP source says it exists so "code [can] use BouncyCastle's KeySpec with the Conscrypt
     * provider", and which reads *any* `KeySpec` through exactly these six calls:
     *
     * ```
     *   password      = (char[]) getValue(inKeySpec, "getPassword");
     *   salt          = (byte[]) getValue(inKeySpec, "getSalt");
     *   n             = (int)    getValue(inKeySpec, "getCostParameter");
     *   r             = (int)    getValue(inKeySpec, "getBlockSize");
     *   p             = (int)    getValue(inKeySpec, "getParallelizationParameter");
     *   keyOutputBits = (int)    getValue(inKeySpec, "getKeyLength");
     * ```
     *
     * So nothing from the `conscrypt` package is ever named here. Three things about that
     * list are load-bearing and every one of them fails *silently-ish*, as a single
     * `InvalidKeySpecException("Not a valid scrypt KeySpec")` that says nothing about which
     * getter was missing:
     *
     * - **The names are literal.** `getCostParameter` is N and `getBlockSize` is
     *   r, which is the pair most likely to be guessed the other way round. There is no
     *   `getMaxMemory`: the memory cap is not ours to set, which is what pins N above.
     *   They are Kotlin functions, not properties, so the JVM method names are exactly these.
     * - **`getKeyLength` is in BITS.** The factory divides by 8 itself
     *   (`keyOutputBits / 8`) and rejects anything not a multiple of 8. Returning 32 here
     *   would quietly produce a four-byte key.
     * - **This class must be `public` and must survive R8.** The lookup is
     *   `spec.getClass().getMethod(name, (Class<?>[]) null)` followed by a plain
     *   `invoke` with no `setAccessible`, from a class in another package, so a
     *   non-public holder would pass `getMethod` and fail `invoke`. And
     *   minification is on for release builds: R8 would rename six getters that nothing in this
     *   app calls by name. `@Keep` is what stops it:
     *   `proguard-android-optimize.txt`, which this module uses, carries
     *   `-keep @androidx.annotation.Keep class * {*;}`, so the annotation keeps the class
     *   and every member of it unrenamed. **Do not remove it**; the result would be a release
     *   build that cannot pair while the debug build can.
     *
     * The password array is the caller's and is cleared by [channelKey]'s `finally`,
     * not copied, deliberately, so there is one copy to zero rather than two.
     */
    @Keep
    class ScryptSpec internal constructor(
        private val password: CharArray,
        private val salt: ByteArray,
    ) : KeySpec {

        fun getPassword(): CharArray { return password }

        fun getSalt(): ByteArray { return salt }

        /** scrypt's N. Named "cost parameter" by the reflective contract, not by scrypt. */
        fun getCostParameter(): Int { return SCRYPT_N }

        /** scrypt's r. */
        fun getBlockSize(): Int { return SCRYPT_R }

        /** scrypt's p. */
        fun getParallelizationParameter(): Int { return SCRYPT_P }

        /** In **bits**. See the class comment. */
        fun getKeyLength(): Int { return DK_BITS }
    }

    /**
     * A fresh nine-digit code, ungrouped. [grouped] is what puts it on a screen.
     *
     * `nextInt(bound)` rather than any arithmetic of our own: it rejection-samples
     * internally, so the distribution is flat without a loop here to get wrong. In particular it
     * avoids `Math.abs(nextInt())`, which is negative for `Integer.MIN_VALUE`, the one
     * input abs has no answer for.
     *
     * `1_000_000_000` still fits an `int`, because [Integer.MAX_VALUE] is
     * 2 147 483 647, so the bound is exact and `nextInt` stays unbiased over every one of the
     * 10⁹ codes. A tenth digit would not fit and would need `nextLong(bound)` or two draws.
     *
     * Formatted with leading zeros, because "007421234" is nine digits and 7421234 is not, so a
     * code the user reads as seven digits is a code they will type as seven.
     */
    internal fun newCode(): String {
        val rng: SecureRandom = Crypto.RNG
        return String.format(java.util.Locale.US, "%09d", rng.nextInt(1_000_000_000))
    }

    /**
     * The code as a person should see it: the code `"123456789"` is displayed as
     * `"123 456 789"`.
     *
     * **Display only.** The spaces exist because a nine-digit run is read back wrong and said
     * out loud worse, and for no other reason. Nothing derived from this string may reach
     * [channelKey], the wire, or a length check; those all take the nine digits. The one
     * safe direction is this one: digits in, decoration out, and the decoration is thrown away
     * again by [digitsOnly] on the side that types it.
     *
     * A plain U+0020 and not a thin space: this is rendered in `monospace`, where every
     * glyph including the space has the same advance, so the width is predictable and computable,
     * which is what the width budget of the code line in `ui/pair/PairSheet.kt` rests on. A U+2009 would either
     * measure one full advance anyway (if the font has it) or fall back to another font and measure
     * something nobody here can predict.
     *
     * Anything that is not exactly [CODE_DIGITS] digits is returned untouched, so a
     * placeholder or an error string passed here by mistake is not silently mangled.
     */
    fun grouped(code: String?): String? {
        if (code == null || code.length != CODE_DIGITS) return code
        val out = StringBuilder(CODE_DIGITS + 2)
        for (i in 0 until CODE_DIGITS) {
            if (i > 0 && i % 3 == 0) out.append(' ')
            out.append(code[i])
        }
        return out.toString()
    }

    /**
     * Everything that is not a digit, removed; this is the inverse of [grouped], and the gate the
     * typed code goes through before anything else looks at it.
     *
     * Because the code is *shown* grouped, it will be copied down grouped and typed
     * grouped, and a user who types the spaces has not made a mistake. This is also why it strips
     * rather than validating: what is left is then measured against [CODE_DIGITS], so a
     * grouped code and a bare one are the same nine digits and anything else is still rejected.
     */
    fun digitsOnly(typed: String?): String {
        if (typed == null) return ""
        val out = StringBuilder(typed.length)
        for (i in 0 until typed.length) {
            val c = typed[i]
            if (c >= '0' && c <= '9') out.append(c)
        }
        return out.toString()
    }

    /** A fresh salt for one pairing window, published in the advertisement. */
    internal fun newSalt(): ByteArray {
        val s = ByteArray(SALT_BYTES)
        Crypto.RNG.nextBytes(s)
        return s
    }

    /**
     * The channel key both ends derive from the code.
     *
     * Handed to [Connection] exactly where the PSK would go, which is what makes the rest
     * of the channel (nonces, per-direction keys, AEAD, replay resistance) come along unchanged.
     * Slow on purpose: of the order of a second, and 16 MiB of it; see [SCRYPT_N].
     * **Never on the main thread**, and never without something on screen saying so: a second on
     * a mid-range phone is longer than that on a slow one, and a frozen sheet right after the user
     * has typed nine digits reads as a crash.
     *
     * @param code **the nine digits and nothing else**, leading zeros included and no grouping
     *             spaces; [digitsOnly] is what guarantees that of anything a user typed.
     *             A grouped string derives a different key, silently, and is reported as a wrong
     *             code
     * @param salt from the advertisement, so no table covers two pairings
     */
    internal fun channelKey(code: String, salt: ByteArray): ByteArray {
        // The algorithm name is upper-case SCRYPT, which is how Conscrypt's provider registers it
        // (`put("SecretKeyFactory.SCRYPT", ...)`). JCA lookup is case-insensitive, so this is
        // documentation rather than a requirement, but it is the name to grep for in AOSP.
        //
        // No provider argument, deliberately, the same rule Crypto follows: naming a provider is how
        // a build ends up pinned to one that a future platform has moved or renamed. Conscrypt is
        // the highest-priority provider on Android and nothing else registers SCRYPT anyway.
        val password = code.toCharArray()
        try {
            val spec: KeySpec = ScryptSpec(password, salt)
            return SecretKeyFactory.getInstance("SCRYPT").generateSecret(spec).encoded
        } catch (e: Exception) {
            // Not a condition to recover from. Two things can land here and both mean the same to a
            // user: the platform has no SCRYPT (below API 35, or a Conscrypt that dropped it), or
            // ScryptSpec's getters got renamed or misspelled and the reflective read failed with
            // InvalidKeySpecException. Either way this build cannot pair at all; see ScryptSpec.
            throw IllegalStateException("scrypt unavailable", e)
        } finally {
            // The code, out of the heap as soon as it has been used. ScryptSpec holds this exact
            // array rather than a copy, so zeroing it here is enough, and a nine-digit code is the
            // whole of the authentication for the PSK itself, so it is worth the line. The caller's
            // own String cannot be cleared, which is exactly why the copy that can be, is.
            java.util.Arrays.fill(password, '\u0000')
        }
    }
}
