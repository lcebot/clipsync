package io.github.lcebot.clipsync

import android.content.Context

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.Properties

/**
 * Everything is configured at runtime, in files/clipsync.conf, which MainActivity writes. There are
 * no compile-time defaults: a released APK carries no configuration at all, which is also why it
 * cannot carry anybody's key.
 *
 * Keys: peers, discovery, direct, port, psk, mdns_timeout_ms, threads, max_bytes,
 * max_file_bytes, max_file_bytes_local, files_dir, keep_hours, keep_max_mb.
 * Every value is validated in [from]; the UI runs the same checks live through
 * the `check*` helpers.
 *
 * Peers are found two ways, and the two are independent switches rather than a mode:
 * `discovery` asks the local network (mDNS), `direct` works through the `peers` list.
 * They are named for how a peer is found, not for the route taken to it; a listed address is very
 * often a LAN address too. At least one of them has to be on.
 *
 * ## Why the PSK is still in a plain text file
 *
 * `psk`, `psk_next` and `psk_old` are the keys to the user's clipboard, and
 * they sit in this file as hex. The obvious hardening, `EncryptedSharedPreferences` or
 * wrapping the value with a Keystore key, was evaluated and **rejected on a constraint, not on
 * effort**: `SyncService` runs in its own `:sync` process and the UI runs in the main
 * one, and *both* read and write this file. `EncryptedSharedPreferences` is explicitly
 * not multi-process safe (neither is plain `SharedPreferences`); two processes with it open
 * lose writes and can corrupt the store, which for the file that carries the PSK means a device
 * that has to be paired again. Key rotation makes that worse, not better, because the service
 * rewrites the schedule whenever a peer reports one. A Keystore-wrapped blob inside this same file
 * would keep the atomic-rename write and survive the two processes, but it buys very little: what
 * it defends against is an attacker who can already read `/data/data/<pkg>/files`, which on a
 * non-rooted device is nobody, and on a rooted one is somebody who can also ask Keystore to
 * unwrap it as this app.
 *
 * So the protection is the three things that actually apply here: the file lives in the app's
 * private directory, `android:allowBackup="false"` keeps it out of cloud and adb backups,
 * and it is written through [Files.atomicWrite] so it is never observed half-written. The key
 * material in `String` form is deliberately confined to this class, [Keys.Schedule] and
 * the pairing exchange; everywhere else it is a `byte[]` that can be, and is, zeroed.
 */
class Config private constructor(p: Properties) {
    val peers: List<String>    // host names or literal addresses, in the order entered
    val direct: Boolean        // dial the list at all
    val discovery: Boolean     // look for peers on the local network (mDNS)
    /**
     * The names and literals that point at THIS device, typically a domain a dynamic DNS client
     * here keeps pointed at it. Read whether or not [direct] is on:
     * it is what the node knows itself by, which stays true when it is dialling nobody.
     */
    val ownAddresses: List<String>
    /** [ownAddresses] normalised, for the membership tests that actually run. */
    val own: Set<String>
    val port: Int
    val psk: ByteArray
    val pskHex: String
    val maxBytes: Int          // text limit
    val maxFileBytes: Long     // image / file limit for a peer reached over the internet
    val maxFileBytesLocal: Long// ... and for one on the LAN (mDNS, or on-link address)
    val keepHours: Int         // received files unused for this long are deleted (0 = never)
    val keepMaxBytes: Long     // and LRU-first above this total (0 = unlimited)
    val mdnsTimeoutMs: Int     // how long one browse may take
    val threads: Int           // parallel data connections per file transfer (1,2,4,8,16)
    val filesDir: String       // absolute path on internal storage where received files go
    val relativePath: String   // the same as a MediaStore RELATIVE_PATH ("Download/ClipSync")
    /** Whether this device replaces its key on a schedule at all; [Keys] holds the schedule. */
    val rotate: Boolean
    /**
     * Opt out of relaying files for other devices on the LAN. When true,
     * this node reports `persistent=false` in HELLO regardless of its actual state, making
     * it unlikely to be elected, and directly declines any RELAY_ASK with "refused".
     */
    val relayOptOut: Boolean
    /**
     * The key's life: successor, superseded ring, and the deadlines.
     *
     * Read whether or not [rotate] is on, because the **ring still applies**: a device
     * that rotated and then had rotation turned off must go on accepting the keys it has already
     * superseded, or it would lock out the peers it rotated away from.
     */
    internal val keys: Keys.Schedule

    init {
        direct = bool(p.getProperty("direct", "true"))
        discovery = bool(p.getProperty("discovery", "true"))
        peers = if (direct) peerList(p.getProperty("peers", "")) else java.util.List.of()
        ownAddresses = peerList(p.getProperty("own_addresses", ""))
        own = java.util.Set.copyOf(ownAddresses)          // peerList already normalised and deduped them
        port = jtrim(p.getProperty("port")).toInt()
        pskHex = lower(jtrim(p.getProperty("psk")))
        // Unreachable through the ordinary path, because from() has already run checkPsk, but this
        // constructor takes a Properties and a future caller could skip that. A null key would not
        // fail here; it would fail later, inside a handshake, as an obscure NPE on a worker thread.
        psk = Crypto.fromHex(pskHex) ?: throw IllegalArgumentException("psk: must be 64 hex characters")
        rotate = bool(p.getProperty("psk_rotate", "false"))
        relayOptOut = bool(p.getProperty("relay_opt_out", "false"))
        keys = schedule(p)
        maxBytes = jtrim(p.getProperty("max_bytes")).toInt()
        maxFileBytes = jtrim(p.getProperty("max_file_bytes")).toLong()
        maxFileBytesLocal = jtrim(p.getProperty("max_file_bytes_local")).toLong()
        keepHours = jtrim(p.getProperty("keep_hours")).toInt()
        keepMaxBytes = jtrim(p.getProperty("keep_max_mb")).toLong() * 1024 * 1024
        mdnsTimeoutMs = jtrim(p.getProperty("mdns_timeout_ms")).toInt()
        threads = snapThreads(jtrim(p.getProperty("threads")).toInt())
        filesDir = jtrim(p.getProperty("files_dir"))
        relativePath = relativePathOf(filesDir)
    }

    fun maxFileAny(): Long {
        return Math.max(maxFileBytes, maxFileBytesLocal)
    }

    /** Largest frame we accept: a CHUNK, or a CLIP whose JSON escaping doubled the text. */
    fun maxFrame(): Int {
        return Math.max(Connection.CHUNK, maxBytes * 2) + 64 * 1024
    }

    companion object {
        const val FILE = "clipsync.conf"

        const val DEFAULT_FILES_DIR = "/storage/emulated/0/Download/ClipSync"
        val THREAD_STEPS = intArrayOf(1, 2, 4, 8, 16)
        val BROWSE_STEPS_MS = intArrayOf(1000, 2000, 3000, 4000, 6000, 8000, 10000)

        /** The monitor that `static synchronized` stood for: shared by [raw] and [invalidate]. */
        private val classLock = Any()

        /**
         * `String.trim()` as Java defines it: strips every character up to and including U+0020 from
         * both ends. Kotlin's own `trim()` strips Unicode whitespace instead, a different set, which
         * would change which values validate and what gets stored.
         */
        private fun jtrim(s: String): String = s.trim { it <= ' ' }

        /** `String.toLowerCase()` as Java defines it: in the default locale. */
        private fun lower(s: String): String = s.lowercase(Locale.getDefault())

        /**
         * Read the key schedule, treating a missing activation time as **now**.
         *
         * Not as zero, which is what a missing number would otherwise mean and which would make any
         * key with no recorded activation time 55 years old, pre-retired on the first read, and rotated
         * out from under a household that had not asked for any of this. "I do not know when this key
         * started" has exactly one safe reading, and it is the generous one.
         *
         * **Generous is not the same as amnesiac, and that distinction is the whole of
         * [unknownAge].** Reading a missing activation time as *now* makes the key's
         * age restart-proof in the wrong direction: every launch re-decides it, so a device that is
         * restarted more often than the rotation deadline never reaches the deadline at all, and the
         * key is immortal by accident. The service writes the answer down the first time it sees the
         * gap, which closes the hole, but only if that write lands, and a fallback whose correctness
         * depends on a write having succeeded is not a fallback.
         *
         * So the anchor is the configuration file's own modification time, not the clock. It is
         * already on disk, it survives restarts because nothing here rewrites it for fun, and it is
         * conservative in the direction that matters: the key cannot be newer than the file that holds
         * it. A device restarted hourly therefore ages its key by an hour each time rather than by
         * nothing.
         */
        private fun schedule(p: Properties): Keys.Schedule {
            val since = longOr(p, "psk_since", 0)
            return Keys.Schedule(
                lower(jtrim(p.getProperty("psk", ""))),
                lower(jtrim(p.getProperty("psk_next", ""))),
                peerList(p.getProperty("psk_old", "")),
                if (since > 0) since else unknownAge(),
                longOr(p, "psk_retire", 0),
                longOr(p, "psk_agreed", 0),
            )
        }

        /**
         * When to say a key started, when the file does not say.
         *
         * The file's own mtime, falling back to the clock only when there is no file, which is a first
         * run, where "now" is exactly right because the key is about to be written for the first time.
         */
        private fun unknownAge(): Long {
            val stamp = fileStamp
            return if (stamp > 0) stamp else System.currentTimeMillis()
        }

        /**
         * The configuration file's modification time as of the last read, or 0 if it does not exist.
         *
         * Kept beside the cache because [raw] already has to stat the file, so this costs
         * nothing, and because [schedule] is handed a [Properties] and has no file to ask.
         */
        @Volatile
        private var fileStamp = 0L

        private fun longOr(p: Properties, key: String, dflt: Long): Long {
            return try {
                jtrim(p.getProperty(key, "")).toLong()
            } catch (e: NumberFormatException) {
                dflt
            }
        }

        /**
         * Record a key as newly in service: its clock starts now, and any schedule around the old one
         * is cleared.
         *
         * Every way a key arrives goes through here, whether typed, generated, or taken from a peer
         * while pairing, because [save] merges over what is stored, so a caller that sets only
         * `psk` inherits the *previous* key's activation time and successor. The key would then be
         * pre-retired on the strength of how long the one it replaced had been in use, which for a key
         * two days old is within minutes of being written.
         */
        fun freshKey(v: Properties, pskHex: String) {
            v.setProperty("psk", pskHex)
            v.setProperty("psk_since", System.currentTimeMillis().toString())
            v.setProperty("psk_next", "")
            v.setProperty("psk_old", "")
            v.setProperty("psk_retire", "0")
            v.setProperty("psk_agreed", "0")
        }

        /** The schedule as the fields a configuration file holds, for [save]. */
        internal fun store(s: Keys.Schedule, rotate: Boolean): Properties {
            val v = Properties()
            v.setProperty("psk", s.psk)
            v.setProperty("psk_next", s.next)
            v.setProperty("psk_old", s.old.joinToString(","))
            v.setProperty("psk_since", s.since.toString())
            v.setProperty("psk_retire", s.retireAt.toString())
            v.setProperty("psk_agreed", s.agreedAt.toString())
            v.setProperty("psk_rotate", rotate.toString())
            return v
        }

        // ------------------------------------------------------------------ peers list
        /**
         * Splits the stored form. Comma-separated and not colon-separated for a reason: an IPv6 literal
         * is nothing but colons. Blanks are dropped and repeats collapse, so a list that round-trips
         * through the UI cannot grow empty rows.
         */
        fun peerList(stored: String): MutableList<String> {
            val out = LinkedHashSet<String>()
            for (s in stored.split(",")) {
                val t = normalisePeer(s)
                if (!t.isEmpty()) out.add(t)
            }
            return ArrayList(out)
        }

        fun storePeers(peers: List<String>): String {
            return peers.joinToString(",")
        }

        /** Trims, drops a bracketed IPv6's brackets, and lower-cases so duplicates compare equal. */
        fun normalisePeer(s: String): String {
            var t = jtrim(s)
            if (t.startsWith("[") && t.endsWith("]") && t.length > 2) t = t.substring(1, t.length - 1)
            return lower(t)
        }

        // ------------------------------------------------------------------ load / save
        /**
         * The last parse of the file, and the stamp it was parsed at.
         *
         * [raw] is called from several places on the UI thread, including every keystroke validation
         * path, the PSK comparison and the pairing sheet, and without this each call would be a file
         * open, a parse and a close. The cache is keyed on the file's modification time *and* its
         * length, because either alone is guessable: mtime has one-second granularity on some
         * filesystems, and a rewrite that changes only a key's hex digits keeps the length. Together
         * they are enough for a file that is rewritten by a person, not by a loop.
         *
         * It has to be invalidated across processes, not just within one: the UI is in the main
         * process and `SyncService` is in `:sync`, and both write this file. That is why the
         * stamp is taken from the file rather than from a flag, because a write by the other process
         * moves the mtime, which is all this needs to see. [save] additionally clears it outright,
         * because a write and the read that follows it can land inside the same mtime tick.
         */
        private var cached: Properties? = null
        private var cachedStamp = -1L
        private var cachedLength = -1L

        /** Raw merged properties (defaults + file), without validation. */
        fun raw(ctx: Context): Properties {
            synchronized(classLock) {
                val f = File(ctx.filesDir, FILE)
                val stamp = f.lastModified()
                val length = f.length()
                fileStamp = stamp      // the anchor for a key whose activation time was never written
                // A copy every time, never the cached object: callers mutate what they get back (save()
                // merges into it, the UI overwrites fields before validating), and handing out the cache
                // itself would let one caller's edits become the next caller's file contents.
                val c = cached
                if (c != null && stamp == cachedStamp && length == cachedLength) {
                    val copy = Properties()
                    copy.putAll(c)
                    return copy
                }
                val p = parse(f)
                cached = p
                cachedStamp = stamp
                cachedLength = length
                val copy = Properties()
                copy.putAll(p)
                return copy
            }
        }

        /** Forget the cached parse. Called after every write, from whichever process performed it. */
        private fun invalidate() {
            synchronized(classLock) {
                cached = null
                cachedStamp = -1L
                cachedLength = -1L
            }
        }

        /** The defaults, then the file laid over them. The part [raw] caches. */
        private fun parse(f: File): Properties {
            val p = Properties()
            p.setProperty("peers", "")
            p.setProperty("own_addresses", "")     // most devices have no name of their own
            p.setProperty("direct", "false")      // nothing to dial until the user adds an address
            p.setProperty("discovery", "true")    // works with no configuration at all
            p.setProperty("port", "47521")
            p.setProperty("psk", "")
            // Key rotation. Off by default: it silently changes the one setting every device has to
            // agree on, and a user who has not asked for that should not get it.
            p.setProperty("psk_rotate", "false")
            p.setProperty("relay_opt_out", "false")
            // When the current key became active. Absent means "unknown", and unknown is read as NOW
            // rather than as the epoch, which is the conservative direction, because the alternative is
            // a first launch that finds a key 55 years old and rotates it before the user has finished
            // setting up the second device.
            p.setProperty("psk_since", "0")
            p.setProperty("psk_next", "")
            p.setProperty("psk_retire", "0")
            p.setProperty("psk_agreed", "0")
            // Superseded keys, newest first, still accepted. A comma list like `peers`, for the same
            // reason: Properties holds strings, and this is the shape the file already uses for one.
            p.setProperty("psk_old", "")
            p.setProperty("mdns_timeout_ms", "4000")
            p.setProperty("threads", "8")
            p.setProperty("max_bytes", "1048576")
            p.setProperty("max_file_bytes", "10485760")
            p.setProperty("max_file_bytes_local", "104857600")
            p.setProperty("files_dir", DEFAULT_FILES_DIR)
            p.setProperty("keep_hours", "2")
            p.setProperty("keep_max_mb", "256")
            if (f.exists()) {
                try {
                    FileInputStream(f).use { inp -> p.load(inp) }
                } catch (e: Exception) {
                    // Never silently: falling back to the defaults means falling back to psk="", which
                    // fails validation, which the user sees as "invalid config" with nothing in the log
                    // to say the file could not be read at all. The two have opposite fixes.
                    Logger.w("config: cannot read $FILE, falling back to defaults: $e")
                }
            }
            return p
        }

        /** @throws IllegalArgumentException with a field-prefixed, user-readable message */
        fun load(ctx: Context): Config {
            return from(raw(ctx))
        }

        fun from(p: Properties): Config {
            val direct = bool(p.getProperty("direct", "true"))
            val discovery = bool(p.getProperty("discovery", "true"))
            // discovery and direct are independent switches, so this is the one state they can jointly
            // reach that leaves no way to find any peer at all, and it is checked for exactly that reason
            if (!direct && !discovery)
                throw IllegalArgumentException("discovery: turn on local network discovery or direct connections")
            fail("own_addresses", checkAddresses(p.getProperty("own_addresses", ""), true, java.util.Set.of()))
            // Checked against the own list, so this device's own name pasted into the peer list is
            // refused here rather than dialled, connected, handshaken and discarded. The id comparison
            // in HELLO stays the authority: a second name for the same host looks like any other name.
            if (direct) fail(
                "peers",
                checkAddresses(
                    p.getProperty("peers", ""), false,
                    java.util.Set.copyOf(peerList(p.getProperty("own_addresses", ""))),
                ),
            )
            fail("port", checkPort(p.getProperty("port", "")))
            fail("psk", checkPsk(p.getProperty("psk", "")))
            // The successor and the ring are checked here for the same reason `psk` is: all three are
            // parsed on every inbound connection, and an authenticated peer can write to psk_next and
            // psk_old through T_KEYS. Connection skips a ring entry that fails to parse rather than
            // failing the whole accept, but this is still the point where a bad value can be refused
            // outright and the user actually told about it, instead of it being silently written and
            // only skipped later.
            fail("psk_next", checkPskOrEmpty(p.getProperty("psk_next", "")))
            for (k in peerList(p.getProperty("psk_old", ""))) fail("psk_old", checkPsk(k))
            fail("mdns_timeout_ms", checkRange(p.getProperty("mdns_timeout_ms", ""), 500, 60000, "ms"))
            fail("threads", checkRange(p.getProperty("threads", ""), 1, 32, ""))
            fail("max_bytes", checkRange(p.getProperty("max_bytes", ""), 1024, 65536L * 1024, "bytes"))
            fail(
                "max_file_bytes",
                checkRange(p.getProperty("max_file_bytes", ""), 1024L * 1024, 4096L * 1024 * 1024, "bytes"),
            )
            fail(
                "max_file_bytes_local",
                checkRange(p.getProperty("max_file_bytes_local", ""), 1024L * 1024, 4096L * 1024 * 1024, "bytes"),
            )
            fail("files_dir", checkPath(p.getProperty("files_dir", "")))
            fail("keep_hours", checkRange(p.getProperty("keep_hours", ""), 0, 8760, "h"))
            fail("keep_max_mb", checkRange(p.getProperty("keep_max_mb", ""), 0, 1024L * 1024, "MB"))
            return Config(p)
        }

        private fun fail(key: String, problem: String?) {
            if (problem != null) throw IllegalArgumentException("$key: $problem")
        }

        /**
         * Validates, then writes files/clipsync.conf. Returns the parsed config.
         *
         * Atomically, through [Files.atomicWrite]: this file carries the PSK, it is rewritten
         * every time a peer reports a key schedule, and a process killed halfway through an in-place
         * rewrite leaves a key file that no longer parses, which the service reports as "invalid
         * config" and which survives every restart, so the only way out is to pair every device again.
         */
        @Throws(IOException::class)
        fun save(ctx: Context, values: Properties): Config {
            val p = raw(ctx)
            for (k in values.stringPropertyNames()) p.setProperty(k, jtrim(values.getProperty(k)))
            val c = from(p)                       // throws before anything is written
            Files.atomicWrite(File(ctx.filesDir, FILE)) { out -> p.store(out, "written by ClipSync MainActivity") }
            invalidate()       // the next raw() must see what was just written, mtime tick or not
            return c
        }

        // ------------------------------------------------------------------ field checks (null = ok)
        /**
         * One entry of the peers list: a host name, an IPv4 literal or an IPv6 literal. The field does
         * not require dynamic DNS; a static address, a LAN address, a `.local` name or a VPN address
         * are all equally valid, and all are resolved the same way.
         */
        fun checkPeer(s: String): String? {
            val t = normalisePeer(s)
            if (t.isEmpty()) return "required"
            if (t.contains("://")) return "just the host or address, without http://"
            if (t.contains("%")) return "an interface name means nothing on another device"
            if (isIpLiteral(t)) return null                 // covers IPv4 and every IPv6 form
            // only now can a colon mean a port: an IPv6 literal is nothing but colons
            if (t.matches(Regex(".+:\\d+"))) return "the port has its own field"
            val h = if (t.endsWith(".")) t.substring(0, t.length - 1) else t   // a trailing dot is legal
            if (h.length > 253) return "too long for a host name"
            for (label in h.split(".")) {
                if (label.isEmpty() || label.length > 63) return "not a valid host name"
                if (label.startsWith("-") || label.endsWith("-")) return "not a valid host name"
                if (!label.matches(Regex("[a-z0-9-]+"))) return "not a valid host name"
            }
            return null
        }

        /**
         * A whole address list: every entry valid, no repeats, and optionally at least one entry.
         *
         * Used for both lists, which is the point: `peers` and `own_addresses` accept
         * exactly the same things and must not drift into accepting different ones. They differ in two
         * parameters only: the peer list needs an entry while Direct connections is on and the own list
         * never does, and a peer entry is additionally refused when it names this device.
         *
         * @param own normalised own-address list; empty when checking the own list itself
         */
        fun checkAddresses(stored: String, allowEmpty: Boolean, own: Set<String>): String? {
            val seen = ArrayList<String>()
            var any = false
            for (s in stored.split(",")) {
                if (jtrim(s).isEmpty()) continue
                any = true
                val problem = checkPeer(s)
                if (problem != null) return problem
                val t = normalisePeer(s)
                if (seen.contains(t)) return "listed twice: $t"
                if (own.contains(t)) return "that is this device: $t"
                seen.add(t)
            }
            if (!any && !allowEmpty) return "add an address, or turn direct connections off"
            return null
        }

        /**
         * Does this address name this device, as far as the declared list can tell?
         *
         * A string comparison on the normalised form, never a DNS lookup, because this runs on every
         * keystroke on the UI thread, which is the same reason [isIpLiteral] uses
         * `InetAddresses.isNumericAddress`. It therefore catches the spellings that were declared
         * and nothing else; a second name for the same host still reaches the handshake.
         */
        fun isSelf(address: String, own: Set<String>): Boolean {
            return own.contains(normalisePeer(address))
        }

        /**
         * Literal address in any form the platform accepts: dotted quad, full or compressed IPv6,
         * with brackets already stripped.
         *
         * [android.net.InetAddresses.isNumericAddress] and not `InetAddress.getByName`:
         * the latter performs a DNS lookup for anything that is not a literal, and this runs on the UI
         * thread on every keystroke. "abc" is all hex digits and also a perfectly good host name, so no
         * amount of pattern-matching first makes that safe.
         */
        private fun isIpLiteral(s: String): Boolean {
            return try {
                android.net.InetAddresses.isNumericAddress(s)
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        fun checkPort(s: String): String? {
            return checkRange(s, 1, 65535, "")
        }

        /** [checkPsk], but "no key" is a legal answer, which it is for a successor, not for a PSK. */
        fun checkPskOrEmpty(s: String): String? {
            return if (jtrim(s).isEmpty()) null else checkPsk(s)
        }

        fun checkPsk(s: String): String? {
            val t = jtrim(s)
            if (t.isEmpty()) return "required"
            if (!lower(t).matches(Regex("[0-9a-f]{64}"))) return "must be 64 hex characters (" + t.length + " given)"
            return null
        }

        fun checkPath(s: String): String? {
            val t = jtrim(s)
            if (t.isEmpty()) return "required"
            return try {
                relativePathOf(t)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }

        /** Integer in [min, max]; unit only decorates the message. */
        fun checkRange(s: String, min: Long, max: Long, unit: String): String? {
            val t = jtrim(s)
            if (t.isEmpty()) return "required"
            val v: Long
            try {
                v = t.toLong()
            } catch (e: NumberFormatException) {
                return "must be a whole number"
            }
            val u = if (unit.isEmpty()) "" else " $unit"
            if (v < min || v > max) return "must be $min to $max$u"
            return null
        }

        /** Nearest of 1, 2, 4, 8, 16. */
        fun snapThreads(n: Int): Int {
            var best = THREAD_STEPS[0]
            for (s in THREAD_STEPS) if (Math.abs(s - n) < Math.abs(best - n)) best = s
            return best
        }

        /** Nearest of the browse-time steps. */
        fun snapBrowse(ms: Int): Int {
            var best = BROWSE_STEPS_MS[0]
            for (s in BROWSE_STEPS_MS) if (Math.abs(s - ms) < Math.abs(best - ms)) best = s
            return best
        }

        /**
         * "/storage/emulated/0/Download/ClipSync" becomes "Download/ClipSync". MediaStore can only create
         * files for us under the well-known top-level folders; for arbitrary content that means
         * Download/ or Documents/ (Pictures/, Movies/, Music/ reject non-matching MIME types).
         */
        fun relativePathOf(abs: String): String {
            val root = android.os.Environment.getExternalStorageDirectory().path
            var s = jtrim(abs.replace('\\', '/'))
            while (s.endsWith("/")) s = s.substring(0, s.length - 1)
            for (prefix in arrayOf("$root/", "/sdcard/", "/storage/emulated/0/", "/storage/self/primary/")) {
                if (s.startsWith(prefix)) { s = s.substring(prefix.length); break }
            }
            if (s.startsWith("/")) throw IllegalArgumentException("must be on internal storage ($root/…)")
            if (s.isEmpty() || s.contains("..")) {
                throw IllegalArgumentException("must name a folder under Download/ or Documents/")
            }
            val top = if (s.contains("/")) s.substring(0, s.indexOf('/')) else s
            if (top != "Download" && top != "Documents")
                throw IllegalArgumentException("must be under Download/ or Documents/")
            return s
        }

        private fun bool(s: String): Boolean {
            return listOf("true", "1", "yes", "on").contains(lower(jtrim(s)))
        }
    }
}
