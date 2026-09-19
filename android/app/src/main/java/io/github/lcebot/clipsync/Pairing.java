package io.github.lcebot.clipsync;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;

import androidx.annotation.Keep;

/**
 * Getting the PSK onto a second device without typing 64 hex characters into it.
 *
 * <p>One device generates the key and advertises {@code _clipsync-pair._tcp} while a window is open;
 * another finds it, connects, and is given the key. The transfer cannot be protected by the PSK —
 * the PSK is what is being transferred — so <b>a nine-digit code is the whole of the
 * authentication</b>: shown on the device that holds the key, typed on the device that wants it.
 *
 * <p><b>The code is nine digits, and nothing else.</b> It is <em>shown</em> in groups of three —
 * "123 456 789" — because that is how a person reads a long number off a screen and says it out
 * loud. The spaces are for the eye only: {@link #grouped} puts them in at the last moment, for
 * display, and nothing else in this project ever sees them. What {@link #channelKey} stretches, and
 * what the two ends have to agree on bit for bit, is the nine digits themselves. Feeding a grouped
 * string to the derivation derives a different key and reports it to the user as a wrong code.
 *
 * <p>This class is the part with no I/O in it: the code, the key the code turns into, and the limits
 * on how long and how often it may be tried.
 *
 * <h2>Why the code is stretched, where the design called only for HKDF</h2>
 *
 * The design bounds <em>online</em> guessing properly — five failed handshakes end the window — but a
 * listener on the same Wi-Fi can record the exchange and try every code <em>offline</em>, at
 * whatever rate its hardware allows. With a plain HKDF that is seconds of CPU for the key to the
 * user's clipboard, permanently. The window being short does not help: the recording outlives it.
 *
 * <p>Two knobs answer that, and they multiply. The code is <b>nine</b> digits rather than six,
 * which is the thousandfold part: a million codes is half a minute of offline work on a laptop, a
 * billion is not. And the derivation is deliberately slow, which is the constant factor on top.
 * Nine is where it stops: the three characters over six are the ones {@link #grouped} pays for by
 * printing them as three groups of three, which is a shape a person reads in one glance and says
 * aloud in three breaths — twelve would be a fourth group and a telephone number.
 *
 * <p>So the code goes through <b>scrypt</b> first. It does not remove the attack — nothing short of a
 * PAKE does, because a low-entropy secret is a low-entropy secret — but it moves the cost from
 * seconds to something a casual listener will not pay. The price on this side is <b>under a
 * second</b> — see {@link #SCRYPT_N} for where the parameters come from — paid once per pairing. The
 * honest summary is: <b>online guessing is bounded by design, offline guessing is only made
 * expensive.</b> A PAKE (SPAKE2 and friends) is the real answer and is a different project.
 *
 * <h2>What "expensive" is worth, in numbers</h2>
 *
 * The unit to measure in is: <em>how long does one consumer GPU need to walk the whole 10⁹ code
 * space?</em> Both figures below come from the same published RTX 4090 run of hashcat v6.2.6
 * (Chick3nman's benchmark gist), so they are comparable to each other rather than to a vendor claim.
 *
 * <ul>
 *   <li><b>The KDF this replaced — PBKDF2-HMAC-SHA256, 600 000 rounds</b>, priced over today's
 *       code space so the two lines differ only in the derivation. Hashcat mode 27500 measures
 *       PBKDF2-HMAC-SHA256 at 259 999 rounds and gets 30 676 H/s; PBKDF2 is exactly linear in the
 *       round count, so 600 000 rounds is ≈13 300 H/s. (Mode 10900 at 999 rounds, 8 865.7 kH/s,
 *       scales to ≈14 800 — the two agree within 10%, which is the cross-check that the scaling is
 *       honest.) 10⁹ ÷ 13 300 ≈ <b>21 hours</b> to exhaust, ~10 hours to expect a hit.
 *   <li><b>What is here now — scrypt, N=2¹⁴, r=8, p=10.</b> Hashcat mode 8900 measures scrypt at exactly
 *       N=16384, r=8, p=1 and gets 7 126 H/s (modes 22700 and 27700 are the same parameters and
 *       report 7 156 and 7 107 — three independent confirmations of the same number). The p loop is
 *       p sequential ROMix calls over one buffer, so p=10 divides that by 10: ≈713 H/s. 10⁹ ÷ 713 ≈
 *       <b>16 days</b> to exhaust, ~8 days to expect a hit.
 * </ul>
 *
 * <p>Four reference points, so the number above has a scale to sit on — all of them one RTX 4090
 * walking the whole code space. <b>Three of them are history, kept for the shape of the curve</b>,
 * not descriptions of what this build does:
 *
 * <ul>
 *   <li>six digits, PBKDF2-HMAC-SHA256 at 200 000 rounds (where this started): <b>~30 seconds</b>;
 *   <li>nine digits, scrypt p=8 (the value before p was raised): <b>13 days</b>;
 *   <li>eight digits, scrypt p=10 (the one revision the code was shortened for): <b>1.6 days</b>;
 *   <li>nine digits, scrypt p=10 (<b>here</b>): <b>16 days</b>.
 * </ul>
 *
 * <p>The ninth digit is therefore back, and it is a <b>10× gain</b> over the revision that dropped
 * it: the digit was traded away for two fewer glyphs to read across a room, and grouping them
 * three-and-three ({@link #grouped}) buys that legibility back without paying the factor of ten.
 * p is unchanged at 10 — it was never the thing that moved — so the honest way to state it is
 * <em>a fortnight</em> of one card rather than a day and a half.
 *
 * <p>So roughly <b>50 000× harder to brute-force than the six-digit PBKDF2 it started as, while the
 * phone spends less time than it used to</b>. The multiplier is not the interesting part;
 * <em>where</em> it comes from is. PBKDF2's state
 * is a few dozen bytes, so a GPU runs as many instances as it has cores. scrypt at these parameters
 * needs 16 MiB of fast random-access memory per instance, which caps a 24 GB card at ~1 500
 * concurrent hashes no matter how many cores it has, and makes memory bandwidth the binding
 * constraint. The same benchmark shows that wall directly: mode 9300 is scrypt at the same N=16384
 * with r=1 (2 MiB) and runs at 83 890 H/s — <b>8× the memory costs the GPU 11.8×</b>, superlinearly,
 * which is the memory-hardness doing something PBKDF2 cannot do at any round count.
 *
 * <p>None of which makes it safe against somebody who wants it. A fortnight on one card is a day on
 * sixteen, and a day of rented cloud time is not a deterrent to anyone who has decided to spend it.
 * The ninth digit moved that line by 10× and did not move which side of it anybody is on. What it still does is
 * make recording a pairing <em>on the off-chance</em> not worth the electricity, which is the threat
 * that is actually there. Against an attacker who is specifically after this clipboard, the answer
 * was never the code length — it is a PAKE, and that is a different project.
 *
 * <p>The salt is per-pairing and published in the advertisement's TXT record, which is what stops
 * one precomputed table from covering every pairing anyone ever performs. Public is fine — a salt is
 * not a secret, it only has to be unique.
 */
final class Pairing {
    /** Its own service type, so ordinary discovery never has to filter it out and vice versa. */
    static final String SERVICE_TYPE = "_clipsync-pair._tcp.";
    /**
     * TXT keys on the advertisement: the protocol version, and the salt below.
     *
     * <p>The salt travels as {@link Crypto#toHex} text, because a TXT value is text. It is read back
     * with {@link Crypto#fromHex}, which answers null rather than throwing — a truncated or foreign
     * advertisement is a thing to skip, not an exception to handle.
     */
    static final String TXT_VERSION = "v", TXT_SALT = "s";

    /**
     * How long a pairing window stays open.
     *
     * <p>Long enough to walk to the other device and type the code, short enough that an
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

    /**
     * How many digits {@link #newCode()} produces, and how many the field accepts once the spaces
     * are taken back out.
     *
     * <p>Nine, restored from the eight one revision spent. The argument for dropping it was that a
     * ninth glyph is a real cost to a user reading a code across a room; the answer to that turned
     * out not to be a shorter code but a better-shaped one, so nine digits are now <em>shown</em>
     * as three groups of three ({@link #grouped}) and the factor of ten is kept. 16 days on one
     * card rather than 1.6 — see the class comment.
     *
     * <p><b>This counts digits, never the displayed spaces.</b> Nine is the length of what is
     * generated, what is compared, and what {@link #channelKey} stretches; the field may hold
     * eleven characters, and what it holds is stripped before it is measured against this.
     */
    static final int CODE_DIGITS = 9;

    /**
     * scrypt's cost parameter N, and the anchor of the whole choice below.
     *
     * <h3>Why scrypt, and why only now</h3>
     *
     * This was PBKDF2-HMAC-SHA256 at 600 000 rounds until {@code minSdk} reached 35, and the reason
     * was never that PBKDF2 was good here — it was that scrypt did not exist on the platform.
     * <b>Conscrypt does not implement PBKDF2 at all</b>, so
     * {@code SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")} resolved to the BouncyCastle copy
     * Android bundles ({@code com.android.org.bouncycastle}): pure Java, no native or hardware
     * acceleration. Measured, 310 000 rounds of {@code PBKDF2WithHmacSha256} took roughly 2.2 s on a
     * Pixel 3 (Daniel Hugenroth, "Password hashing on Android"), so 600 000 rounds was <b>about 4 s
     * on a Pixel 3 and 1.5–2.5 s on a newer mid-range phone</b>. Four seconds of a frozen sheet,
     * for less brute-force resistance than a second of scrypt buys — see the class comment for
     * the measured comparison.
     *
     * <p>Conscrypt's provider registers {@code SecretKeyFactory.SCRYPT} (with the OID alias
     * {@code 1.3.6.1.4.1.11591.4.11}), backed by BoringSSL's {@code EVP_PBE_scrypt}. In AOSP
     * {@code external/conscrypt} those three registration lines <b>first appear on
     * {@code android15-release}</b>; on {@code android14-release} and {@code android14-qpr3-release}
     * the {@code SecretKeyFactory} block is {@code DESEDE} plus its {@code TDEA} alias and nothing
     * else. That is the entire reason {@code minSdk} is 35 — see the note on it in
     * {@code build.gradle.kts}, which also records what that costs in devices.
     *
     * <p>The name is undocumented: {@code SCRYPT} appears nowhere in the supported-algorithm table
     * on {@code developer.android.com/reference/javax/crypto/SecretKeyFactory}, whose list stops at
     * the {@code PBKDF2withHmac*} family. That is a real risk and it is accepted with open eyes,
     * because the failure is loud rather than silent: {@code getInstance} throws
     * {@link java.security.NoSuchAlgorithmException} and {@link #channelKey} turns it into an
     * {@link IllegalStateException}. A build that cannot pair is obvious on the first attempt.
     *
     * <h3>Where N, r and p come from</h3>
     *
     * Four constraints, and they pin the answer almost exactly:
     *
     * <ul>
     *   <li><b>Under a second on a mid-range phone.</b> One ROMix at N=2¹⁴, r=8 is a few tens of
     *       milliseconds of native code; p=10 makes it ten of those, so roughly <b>0.5–1.0 s</b>.
     *       See {@link #SCRYPT_P} for why that upper bound is where p stopped, and for the one
     *       measurement that would let it move.
     *   <li><b>16 MiB, and not one byte more.</b> The footprint is {@code 128·r·N} = 16 MiB
     *       (BoringSSL also allocates {@code 128·r·(p+1)} = 11 KiB for B and T, so ~16.01 MiB in
     *       total). It is a native allocation, outside the Java heap, so it costs no GC at all —
     *       but it is still 16 MiB of RSS on a phone that may have little to spare, and it is
     *       transient.
     *   <li><b>No implementation's memory cap is anywhere near it, and there is no way to raise one
     *       from here.</b> This matters more than it looks: the reflective KeySpec below has six
     *       fields and <b>{@code max_mem} is not one of them</b>, so whatever cap Conscrypt hands
     *       BoringSSL is fixed and unreachable from app code. BoringSSL's default
     *       ({@code SCRYPT_MAX_MEM} in {@code crypto/evp/scrypt.cc}) is {@code 1024*1024*65} = 65
     *       MiB; OpenSSL's, which is what the PC end gets by default, is 32 MiB. 16 MiB clears both
     *       with room to spare. <b>N=2¹⁵ was the obvious alternative and is exactly why it was not
     *       taken</b>: {@code 128·r·N} is then precisely 32 MiB, which is precisely OpenSSL's
     *       default limit, i.e. a build that works on one end and throws "memory limit exceeded" on
     *       the other. Sitting on a documented cliff edge to buy ~10% is not a trade.
     *   <li><b>p, not a bigger N, spends the remaining time budget.</b> p is ten sequential ROMix
     *       calls over the same buffer: it multiplies the work by 10 without touching the memory
     *       footprint, and it slows a GPU attacker by exactly 10 too, so the trade is one-for-one.
     *       A bigger N would have been marginally better per unit of defender time (it raises the
     *       attacker's memory <em>capacity</em> requirement as well as its bandwidth bill) — but the
     *       previous point forbids it, and p has the compensating virtue that N=2¹⁴, r=8, p=1 is
     *       exactly what hashcat mode 8900 benchmarks, so the attacker-side number in the class
     *       comment is a measurement divided by p rather than an extrapolation.
     * </ul>
     *
     * <p>{@link #DK_BITS} is 256 because that is what {@link Connection} wants where the PSK would
     * go; nothing about scrypt suggests it.
     *
     * <p><b>Both ends must agree bit for bit</b>, and so must {@link #CODE_DIGITS}: all of it feeds
     * the derivation, so two ends that disagree about any of it do not fail with "wrong parameters"
     * — they derive different keys and the user is told the code was wrong. There is no negotiation
     * and deliberately none: the two ends ship together, and a parameter announced in a plaintext
     * advertisement is a parameter an attacker on the same Wi-Fi can ask to have lowered.
     * {@code clipsync_pair.py} carries the same four numbers and the same reasoning.
     *
     * <p>Under a second is short enough that this is no longer the UI problem it was, but the rule
     * it created still stands and is not negotiable: {@link #channelKey} runs on a worker thread and
     * says on screen that it is working. A mid-range phone is not the slowest phone, and the
     * derivation is not the only thing between the button and the answer.
     */
    private static final int SCRYPT_N = 1 << 14;
    /** scrypt's block size r. 8 is the standard value and the one every published figure uses. */
    private static final int SCRYPT_R = 8;
    /**
     * scrypt's parallelisation parameter p — the whole of the time budget, see {@link #SCRYPT_N}.
     *
     * <p>Ten, and the ceiling is a guess rather than a measurement. <b>Nobody has ever timed this on
     * a real phone.</b> The 0.5–1.0 s quoted above is an extrapolation from scrypt's own paper
     * (N=2¹⁴, r=8, p=1 ≈ 0.1 s on 2009 desktop hardware) to a modern phone's big core running
     * native BoringSSL. The budget is one second, the pessimistic end of that extrapolation at p=10
     * is exactly one second, and that is the entire reason p stopped here rather than at 16: a
     * device slower than the guess blows the budget at p=10 already, and p=16 would blow it on a
     * device that matched the guess.
     *
     * <p>So <b>this number is deliberately left with headroom on the table</b>. p is free security:
     * it is linear in attacker cost and does not touch the 16 MiB footprint, so every point of p is
     * 10% more GPU-days at no memory cost and no risk to either implementation's limits. Once
     * somebody has an actual figure, <b>raise it</b> — p=16 if the real number is at the fast end,
     * p=20 if it is faster still; the only ceiling is what a user will wait for.
     *
     * <p><b>How to measure:</b> wrap the {@link #channelKey} call in {@code PairSheet.connect} in a
     * {@code SystemClock.elapsedRealtime()} pair and log the delta on the oldest and slowest device
     * the project cares about, with the screen on and the CPU not already warm from a build.
     *
     * <p>If it turns out to be <b>slower</b> than the guess — a real measurement over ~1.2 s — drop
     * to <b>p=8</b>, which is the value this carried for the revisions before it and is known to be
     * tolerable in the same sense (i.e. not measured either, but it shipped). Below 8 is not worth
     * doing: at {@link #CODE_DIGITS} digits, p=8 is already 13 GPU-days, and the answer to wanting
     * more than that is a PAKE, not a smaller p.
     *
     * <p>Whatever it becomes, {@code clipsync_pair.SCRYPT_P} moves with it in the same commit.
     */
    private static final int SCRYPT_P = 10;
    /** Output length in <b>bits</b>: {@code ScryptSecretKeyFactory} divides by 8 itself. */
    private static final int DK_BITS = 256;
    private static final int SALT_BYTES = 16;

    /**
     * The six numbers scrypt needs, in the one shape Conscrypt will accept from outside itself.
     *
     * <p>Conscrypt's own {@code org.conscrypt.ScryptKeySpec} is unreachable: on Android the library
     * is repackaged to {@code com.android.org.conscrypt} and sits behind the hidden-API wall. But
     * {@code ScryptSecretKeyFactory.engineGenerateSecret} does not require it. After the
     * {@code instanceof ScryptKeySpec} branch it falls through to a reflective one, whose comment in
     * the AOSP source says it exists so "code [can] use BouncyCastle's KeySpec with the Conscrypt
     * provider", and which reads <em>any</em> {@code KeySpec} through exactly these six calls:
     *
     * <pre>
     *   password      = (char[]) getValue(inKeySpec, "getPassword");
     *   salt          = (byte[]) getValue(inKeySpec, "getSalt");
     *   n             = (int)    getValue(inKeySpec, "getCostParameter");
     *   r             = (int)    getValue(inKeySpec, "getBlockSize");
     *   p             = (int)    getValue(inKeySpec, "getParallelizationParameter");
     *   keyOutputBits = (int)    getValue(inKeySpec, "getKeyLength");
     * </pre>
     *
     * <p>So nothing from the {@code conscrypt} package is ever named here. Three things about that
     * list are load-bearing and every one of them fails <em>silently-ish</em> — as a single
     * {@code InvalidKeySpecException("Not a valid scrypt KeySpec")} that says nothing about which
     * getter was missing:
     *
     * <ul>
     *   <li><b>The names are literal.</b> {@code getCostParameter} is N and {@code getBlockSize} is
     *       r, which is the pair most likely to be guessed the other way round. There is no
     *       {@code getMaxMemory}: the memory cap is not ours to set, which is what pins N above.
     *   <li><b>{@code getKeyLength} is in BITS.</b> The factory divides by 8 itself
     *       ({@code keyOutputBits / 8}) and rejects anything not a multiple of 8. Returning 32 here
     *       would quietly produce a four-byte key.
     *   <li><b>This class must be {@code public} and must survive R8.</b> The lookup is
     *       {@code spec.getClass().getMethod(name, (Class<?>[]) null)} followed by a plain
     *       {@code invoke} with no {@code setAccessible}, from a class in another package — so a
     *       package-private holder would pass {@code getMethod} and fail {@code invoke}. And
     *       minification is on for release builds: R8 would rename six getters that nothing in this
     *       app calls by name. {@code @Keep} is what stops it —
     *       {@code proguard-android-optimize.txt}, which this module uses, carries
     *       {@code -keep @androidx.annotation.Keep class * {*;}}, so the annotation keeps the class
     *       and every member of it unrenamed. <b>Do not remove it</b>; the result would be a release
     *       build that cannot pair while the debug build can.
     * </ul>
     *
     * <p>The password array is the caller's and is cleared by {@link #channelKey}'s {@code finally},
     * not copied — deliberately, so there is one copy to zero rather than two.
     */
    @Keep
    public static final class ScryptSpec implements KeySpec {
        private final char[] password;
        private final byte[] salt;

        ScryptSpec(char[] password, byte[] salt) {
            this.password = password;
            this.salt = salt;
        }

        public char[] getPassword() { return password; }

        public byte[] getSalt() { return salt; }

        /** scrypt's N. Named "cost parameter" by the reflective contract, not by scrypt. */
        public int getCostParameter() { return SCRYPT_N; }

        /** scrypt's r. */
        public int getBlockSize() { return SCRYPT_R; }

        /** scrypt's p. */
        public int getParallelizationParameter() { return SCRYPT_P; }

        /** In <b>bits</b>. See the class comment. */
        public int getKeyLength() { return DK_BITS; }
    }

    private Pairing() {}

    /**
     * A fresh nine-digit code, ungrouped. {@link #grouped} is what puts it on a screen.
     *
     * <p>{@code nextInt(bound)} rather than any arithmetic of our own: it rejection-samples
     * internally, so the distribution is flat without a loop here to get wrong. (It replaced one:
     * {@code Math.abs(nextInt())} is negative for {@code Integer.MIN_VALUE}, the one input where
     * abs has no answer, and the guard for that was a line nobody could check by reading.)
     *
     * <p>{@code 1_000_000_000} still fits an {@code int} — {@link Integer#MAX_VALUE} is 2 147 483
     * 647 — so the bound is exact and {@code nextInt} stays unbiased over every one of the 10⁹
     * codes. A tenth digit would not fit and would need {@code nextLong(bound)} or two draws.
     *
     * <p>Formatted with leading zeros, because "007421234" is nine digits and 7421234 is not — a
     * code the user reads as seven digits is a code they will type as seven.
     */
    static String newCode() {
        SecureRandom rng = Crypto.RNG;
        return String.format(java.util.Locale.US, "%09d", rng.nextInt(1_000_000_000));
    }

    /**
     * The code as a person should see it: {@code "123456789"} → {@code "123 456 789"}.
     *
     * <p><b>Display only.</b> The spaces exist because a nine-digit run is read back wrong and said
     * out loud worse, and for no other reason. Nothing derived from this string may reach
     * {@link #channelKey}, the wire, or a length check — those all take the nine digits. The one
     * safe direction is this one: digits in, decoration out, and the decoration is thrown away
     * again by {@link #digitsOnly} on the side that types it.
     *
     * <p>A plain U+0020 and not a thin space: this is rendered in {@code monospace}, where every
     * glyph including the space has the same advance, so the width is predictable and computable —
     * which is what the budget in {@code sheet_pair.xml} is computed from. A U+2009 would either
     * measure one full advance anyway (if the font has it) or fall back to another font and measure
     * something nobody here can predict.
     *
     * <p>Anything that is not exactly {@link #CODE_DIGITS} digits is returned untouched, so a
     * placeholder or an error string passed here by mistake is not silently mangled.
     */
    static String grouped(String code) {
        if (code == null || code.length() != CODE_DIGITS) return code;
        StringBuilder out = new StringBuilder(CODE_DIGITS + 2);
        for (int i = 0; i < CODE_DIGITS; i++) {
            if (i > 0 && i % 3 == 0) out.append(' ');
            out.append(code.charAt(i));
        }
        return out.toString();
    }

    /**
     * Everything that is not a digit, removed — the inverse of {@link #grouped}, and the gate the
     * typed code goes through before anything else looks at it.
     *
     * <p>Because the code is <em>shown</em> grouped, it will be copied down grouped and typed
     * grouped, and a user who types the spaces has not made a mistake. This is also why it strips
     * rather than validating: what is left is then measured against {@link #CODE_DIGITS}, so a
     * grouped code and a bare one are the same nine digits and anything else is still rejected.
     */
    static String digitsOnly(String typed) {
        if (typed == null) return "";
        StringBuilder out = new StringBuilder(typed.length());
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
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
     * Slow on purpose — of the order of a second, and 16 MiB of it, see {@link #SCRYPT_N}.
     * <b>Never on the main thread</b>, and never without something on screen saying so: a second on
     * a mid-range phone is longer than that on a slow one, and a frozen sheet right after the user
     * has typed nine digits reads as a crash.
     *
     * @param code <b>the nine digits and nothing else</b>, leading zeros included and no grouping
     *             spaces — {@link #digitsOnly} is what guarantees that of anything a user typed.
     *             A grouped string derives a different key, silently, and is reported as a wrong
     *             code
     * @param salt from the advertisement, so no table covers two pairings
     */
    static byte[] channelKey(String code, byte[] salt) {
        // The algorithm name is upper-case SCRYPT, which is how Conscrypt's provider registers it
        // (`put("SecretKeyFactory.SCRYPT", ...)`). JCA lookup is case-insensitive, so this is
        // documentation rather than a requirement — but it is the name to grep for in AOSP.
        //
        // No provider argument, deliberately, the same rule Crypto follows: naming a provider is how
        // a build ends up pinned to one that a future platform has moved or renamed. Conscrypt is
        // the highest-priority provider on Android and nothing else registers SCRYPT anyway.
        char[] password = code.toCharArray();
        try {
            KeySpec spec = new ScryptSpec(password, salt);
            return SecretKeyFactory.getInstance("SCRYPT").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // Not a condition to recover from. Two things can land here and both mean the same to a
            // user: the platform has no SCRYPT (below API 35, or a Conscrypt that dropped it), or
            // ScryptSpec's getters got renamed or misspelled and the reflective read failed with
            // InvalidKeySpecException. Either way this build cannot pair at all — see ScryptSpec.
            throw new IllegalStateException("scrypt unavailable", e);
        } finally {
            // The code, out of the heap as soon as it has been used. ScryptSpec holds this exact
            // array rather than a copy, so zeroing it here is enough — and a nine-digit code is the
            // whole of the authentication for the PSK itself, so it is worth the line. The caller's
            // own String cannot be cleared, which is exactly why the copy that can be, is.
            java.util.Arrays.fill(password, '\0');
        }
    }
}
