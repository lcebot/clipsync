package io.github.lcebot.clipsync.ui.settings

import androidx.compose.runtime.Immutable
import io.github.lcebot.clipsync.Config
import java.util.Properties

/**
 * Everything the form holds, as plain values: what [SettingsRules] reads and writes. The two address
 * lists are the rows as shown, blanks included, because validation speaks about rows.
 */
@Immutable
data class SettingsValues(
    val discovery: Boolean,
    val direct: Boolean,
    val peers: List<String>,
    val ownAddresses: List<String>,
    val port: String,
    val psk: String,
    val pskRotate: Boolean,
    val relayOptOut: Boolean,
    val textKb: String,
    val fileMb: String,
    val fileMbLocal: String,
    val path: String,
    val keepHours: String,
    val keepMb: String,
    val threadsIndex: Int,
    val browseIndex: Int,
) {
    /**
     * Never prints the key. A data class's generated toString would, and one log line or crash
     * report with it is a leaked pairing.
     */
    override fun toString(): String = "SettingsValues(discovery=$discovery, direct=$direct, " +
        "peers=${peers.size}, own=${ownAddresses.size}, port=$port, psk=<${psk.length} chars>)"
}

/** Why one row of an address list is refused. Resolved to text by the UI. */
sealed interface RowProblem {
    /** Blank, and the list needs an entry. [lastRow]: this is the only row, so removing it is no answer. */
    data class Required(val lastRow: Boolean) : RowProblem
    data class Invalid(val message: String) : RowProblem
    data object Duplicate : RowProblem
    data object IsSelf : RowProblem
}

/** The outcome of one validation pass over the whole form. */
@Immutable
data class Validation(
    val port: String? = null,
    val psk: String? = null,
    val textKb: String? = null,
    val fileMb: String? = null,
    val fileMbLocal: String? = null,
    val path: String? = null,
    val keepHours: String? = null,
    val keepMb: String? = null,
    val peers: List<RowProblem?> = emptyList(),
    val ownAddresses: List<RowProblem?> = emptyList(),
    /**
     * Both path switches off. Config.from refuses that config, so Apply must refuse it first, and it
     * has no field of its own: with both switches off, both group cards are hidden.
     */
    val noPath: Boolean = false,
) {
    val ownHasError: Boolean get() = ownAddresses.any { it != null }

    val ok: Boolean
        get() = !noPath && port == null && psk == null && textKb == null && fileMb == null &&
            fileMbLocal == null && path == null && keepHours == null && keepMb == null &&
            peers.all { it == null } && ownAddresses.all { it == null }
}

/**
 * What the settings fields mean in the config file, and whether they are valid.
 *
 * The file format on one side, plain values on the other. Nothing here saves: the caller decides
 * what to do with [toProperties], which is also why a save that fails comes back to the caller.
 */
object SettingsRules {
    const val DEFAULT_PORT = "47521"

    fun load(p: Properties): SettingsValues {
        val threads = Config.snapThreads(longOf(p, "threads", 8).toInt())
        val browse = Config.snapBrowse(longOf(p, "mdns_timeout_ms", 4000).toInt())
        return SettingsValues(
            discovery = bool(p.getProperty("discovery", "true")),
            direct = bool(p.getProperty("direct", "false")),
            peers = Config.peerList(p.getProperty("peers", "")),
            ownAddresses = Config.peerList(p.getProperty("own_addresses", "")),
            port = p.getProperty("port", DEFAULT_PORT),
            psk = p.getProperty("psk", ""),
            pskRotate = bool(p.getProperty("psk_rotate", "false")),
            relayOptOut = bool(p.getProperty("relay_opt_out", "false")),
            textKb = (longOf(p, "max_bytes", 1_048_576) / 1024).toString(),
            fileMb = (longOf(p, "max_file_bytes", 10_485_760) / MIB).toString(),
            fileMbLocal = (longOf(p, "max_file_bytes_local", 104_857_600) / MIB).toString(),
            path = p.getProperty("files_dir", Config.DEFAULT_FILES_DIR),
            keepHours = longOf(p, "keep_hours", 2).toString(),
            keepMb = longOf(p, "keep_max_mb", 256).toString(),
            threadsIndex = indexOf(Config.THREAD_STEPS, threads),
            browseIndex = indexOf(Config.BROWSE_STEPS_MS, browse),
        )
    }

    /**
     * The values as config properties.
     *
     * A key typed or generated here is a NEW key, so its clock starts now; otherwise rotation would
     * retire it early on the strength of how long the key it replaces had been in use. Only when the
     * key differs from [storedPsk], because applying an edited limit must not keep resetting the age
     * of a key that has been in service for a day.
     */
    fun toProperties(v: SettingsValues, storedPsk: String, now: Long): Properties {
        val p = Properties()
        p.setProperty("discovery", v.discovery.toString())
        p.setProperty("direct", v.direct.toString())
        p.setProperty("peers", Config.storePeers(AddressRules.values(v.peers)))
        p.setProperty("own_addresses", Config.storePeers(AddressRules.values(v.ownAddresses)))
        p.setProperty("port", v.port)
        p.setProperty("psk", v.psk)
        p.setProperty("psk_rotate", v.pskRotate.toString())
        p.setProperty("relay_opt_out", v.relayOptOut.toString())
        if (!v.psk.equals(storedPsk.trim(), ignoreCase = true)) {
            p.setProperty("psk_since", now.toString())
            p.setProperty("psk_next", "")
            p.setProperty("psk_old", "")
            p.setProperty("psk_retire", "0")
            p.setProperty("psk_agreed", "0")
        }
        p.setProperty("mdns_timeout_ms", snap(Config.BROWSE_STEPS_MS, v.browseIndex).toString())
        p.setProperty("threads", snap(Config.THREAD_STEPS, v.threadsIndex).toString())
        p.setProperty("max_bytes", scaled(v.textKb, 1024))
        p.setProperty("max_file_bytes", scaled(v.fileMb, MIB))
        p.setProperty("max_file_bytes_local", scaled(v.fileMbLocal, MIB))
        p.setProperty("files_dir", v.path)
        p.setProperty("keep_hours", v.keepHours)
        p.setProperty("keep_max_mb", v.keepMb)
        return p
    }

    fun validate(v: SettingsValues): Validation {
        // The own list first: the peer list is checked against it.
        val own = AddressRules.validate(v.ownAddresses, allowEmpty = true, enabled = true, forbidden = emptySet())
        val peers = AddressRules.validate(
            v.peers, allowEmpty = false, enabled = v.direct, forbidden = AddressRules.normalised(v.ownAddresses),
        )
        return Validation(
            port = Config.checkPort(v.port),
            psk = Config.checkPsk(v.psk),
            textKb = Config.checkRange(v.textKb, 1, 65_536, "KB"),
            fileMb = Config.checkRange(v.fileMb, 1, 4096, "MB"),
            fileMbLocal = Config.checkRange(v.fileMbLocal, 1, 4096, "MB"),
            path = Config.checkPath(v.path),
            keepHours = Config.checkRange(v.keepHours, 0, 8760, "h"),
            keepMb = Config.checkRange(v.keepMb, 0, 1024L * 1024L, "MB"),
            peers = peers,
            ownAddresses = own,
            noPath = !v.discovery && !v.direct,
        )
    }

    /**
     * The step a slider position lands on.
     *
     * The sliders carry an index, not the value: the steps are not evenly spaced, so the control
     * runs from 0 to the table's last index and the table says what each position means. Clamping
     * here means the table's size is never restated as a literal elsewhere.
     */
    fun snap(steps: IntArray, index: Int): Int = steps[index.coerceIn(0, steps.lastIndex)]

    /** The slider position for a value, or the first one if no step matches. */
    fun indexOf(steps: IntArray, value: Int): Int = steps.indexOf(value).coerceAtLeast(0)

    private const val MIB = 1024L * 1024L

    private fun bool(s: String): Boolean = s.trim().lowercase() in setOf("true", "1", "yes", "on")

    /** A count of units in bytes; anything that is not a number is passed through for the check to name. */
    private fun scaled(s: String, unit: Long): String =
        s.trim().toLongOrNull()?.let { (it * unit).toString() } ?: s

    private fun longOf(p: Properties, key: String, default: Long): Long =
        p.getProperty(key, default.toString()).trim().toLongOrNull() ?: default
}

/**
 * The rules one address list follows. Used for both lists, which is the point: peers and own
 * addresses accept exactly the same things and must not drift apart. They differ in two parameters:
 * the peer list needs an entry while Direct connections is on, and a peer is refused when it names
 * this device.
 */
object AddressRules {
    /** Trimmed, non-blank entries: what gets stored. */
    fun values(rows: List<String>): List<String> = rows.map { it.trim() }.filter { it.isNotEmpty() }

    fun normalised(rows: List<String>): Set<String> = values(rows).mapTo(LinkedHashSet()) { Config.normalisePeer(it) }

    /**
     * One problem per row, in row order. A disabled list reports none: its rows are kept for when
     * it is enabled again, and errors on something the user switched off are noise.
     */
    fun validate(
        rows: List<String>,
        allowEmpty: Boolean,
        enabled: Boolean,
        forbidden: Set<String>,
    ): List<RowProblem?> {
        if (!enabled) return rows.map { null }
        val seen = HashSet<String>()
        return rows.map { row ->
            val raw = row.trim()
            if (raw.isEmpty()) {
                if (allowEmpty) null else RowProblem.Required(lastRow = rows.size == 1)
            } else {
                val invalid = Config.checkPeer(raw)
                val normal = Config.normalisePeer(raw)
                when {
                    invalid != null -> RowProblem.Invalid(invalid)
                    normal in seen -> RowProblem.Duplicate
                    normal in forbidden -> RowProblem.IsSelf
                    else -> {
                        seen.add(normal)
                        null
                    }
                }
            }
        }
    }
}
