package io.github.lcebot.clipsync.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One editable address list: the rows, and whether the list is switched on.
 *
 * Rows carry a stable id so the UI can key them: a row removed from the middle animates out as
 * itself, and the rows below keep their text fields, focus and cursor.
 */
@Stable
class AddressListState(initial: List<String>) {
    @Stable
    class Row internal constructor(val id: Long, val field: TextFieldState)

    private var nextId = 0L

    val rows = mutableStateListOf<Row>()

    var enabled by mutableStateOf(true)
        private set

    /** A single row stays: it is where the user types the first address. */
    val removable: Boolean get() = enabled && rows.size > 1

    init {
        setValues(initial)
    }

    /** Replaces every row. An empty list still shows one blank row to type into. */
    fun setValues(values: List<String>) {
        rows.clear()
        values.ifEmpty { listOf("") }.forEach { rows.add(newRow(it)) }
    }

    fun add() {
        rows.add(newRow(""))
    }

    fun remove(row: Row) {
        if (rows.size > 1) rows.remove(row)
    }

    /**
     * Switching a list off drops its blank rows (keeping one), because a blank row in a disabled
     * list is neither an entry nor something the user can act on.
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            for (i in rows.indices.reversed()) {
                if (rows.size <= 1) break
                if (rows[i].field.text.isBlank()) rows.removeAt(i)
            }
        }
    }

    /** The rows as typed, blanks included, in order. */
    fun texts(): List<String> = rows.map { it.field.text.toString() }

    private fun newRow(text: String) = Row(nextId++, TextFieldState(text))
}

/**
 * The settings form's state: every field, switch and slider, and the rules applied to them.
 *
 * Validation is not stored. [validate] reads snapshot state, so a caller wrapping it in
 * `derivedStateOf` gets a result that updates on every keystroke and only then; Apply's enabled
 * state, every field's error and the own-addresses rule all come from that one pass.
 */
@Stable
class SettingsState(initial: SettingsValues) {
    val port = TextFieldState()
    val psk = TextFieldState()
    val textKb = TextFieldState()
    val fileMb = TextFieldState()
    val fileMbLocal = TextFieldState()
    val path = TextFieldState()
    val keepHours = TextFieldState()
    val keepMb = TextFieldState()

    val peers = AddressListState(emptyList())
    val ownAddresses = AddressListState(emptyList())

    var discovery by mutableStateOf(true)

    private var directOn by mutableStateOf(false)

    /** The peer list follows this switch: its rows are only editable and checked while it is on. */
    var direct: Boolean
        get() = directOn
        set(on) {
            directOn = on
            peers.setEnabled(on)
        }

    var pskRotate by mutableStateOf(false)
    var relayOptOut by mutableStateOf(false)
    var threadsIndex by mutableStateOf(0)
    var browseIndex by mutableStateOf(0)

    var ownExpanded by mutableStateOf(false)
        private set

    /**
     * A save the file refused although the form passed. Shown on its field until the next edit or
     * the next successful Apply.
     */
    var saveProblem by mutableStateOf<SaveProblem?>(null)

    init {
        load(initial)
    }

    /**
     * Fills every field from the file's values. Also the refresh after pairing: pairing writes more
     * than the key (it turns discovery on), and re-reading everything is the only version of this
     * that cannot fall behind whatever pairing writes next.
     */
    fun load(v: SettingsValues) {
        discovery = v.discovery
        directOn = v.direct
        peers.setValues(v.peers)
        peers.setEnabled(v.direct)
        ownAddresses.setValues(v.ownAddresses)
        // Collapsed by default, but never over content: a device with an address of its own shows
        // it, and a group the user cannot see is worse than one that takes a tap.
        ownExpanded = v.ownAddresses.isNotEmpty()
        port.setTextAndPlaceCursorAtEnd(v.port)
        psk.setTextAndPlaceCursorAtEnd(v.psk)
        pskRotate = v.pskRotate
        relayOptOut = v.relayOptOut
        textKb.setTextAndPlaceCursorAtEnd(v.textKb)
        fileMb.setTextAndPlaceCursorAtEnd(v.fileMb)
        fileMbLocal.setTextAndPlaceCursorAtEnd(v.fileMbLocal)
        path.setTextAndPlaceCursorAtEnd(v.path)
        keepHours.setTextAndPlaceCursorAtEnd(v.keepHours)
        keepMb.setTextAndPlaceCursorAtEnd(v.keepMb)
        threadsIndex = v.threadsIndex
        browseIndex = v.browseIndex
    }

    fun values(): SettingsValues = SettingsValues(
        discovery = discovery,
        direct = direct,
        peers = peers.texts(),
        ownAddresses = ownAddresses.texts(),
        port = port.text.toString(),
        psk = psk.text.toString(),
        pskRotate = pskRotate,
        relayOptOut = relayOptOut,
        textKb = textKb.text.toString(),
        fileMb = fileMb.text.toString(),
        fileMbLocal = fileMbLocal.text.toString(),
        path = path.text.toString(),
        keepHours = keepHours.text.toString(),
        keepMb = keepMb.text.toString(),
        threadsIndex = threadsIndex,
        browseIndex = browseIndex,
    )

    fun validate(checks: SettingsChecks = ConfigChecks): Validation = SettingsRules.validate(values(), checks)

    /**
     * Opens or closes the own-addresses group. Closing is refused while the group holds an error:
     * the message is inside it, and hiding it would leave Apply disabled with nothing on screen to
     * say why. Refusing is not a dead end, because the reason is the red line the user is looking at.
     *
     * @return whether the group is now in the requested state
     */
    fun setOwnExpanded(open: Boolean, validation: Validation): Boolean {
        if (!open && validation.ownHasError) return false
        ownExpanded = open
        return true
    }

    /**
     * An error inside a collapsed group is an error nobody can act on, so a validation pass that
     * finds one opens the group.
     */
    fun reveal(validation: Validation) {
        if (validation.ownHasError) ownExpanded = true
    }

    /**
     * Pairing is mDNS at both ends, so it turns discovery on in the form rather than refusing: the
     * only reading of "Pair" with discovery off is that the user wants both. The file is not
     * written; Apply writes, everywhere on this page.
     *
     * @return true when the switch had to change, so the caller can say so out loud
     */
    fun ensureDiscovery(): Boolean {
        if (discovery) return false
        discovery = true
        return true
    }
}

/** A save refused by Config.from, mapped back to the field that caused it. */
data class SaveProblem(val key: String, val message: String) {
    companion object {
        /** Config.from reports "key: problem"; anything else is shown on the port field. */
        fun parse(raw: String?): SaveProblem {
            val text = raw ?: "invalid value"
            val colon = text.indexOf(':')
            return if (colon > 0) SaveProblem(text.substring(0, colon), text.substring(colon + 1).trim())
            else SaveProblem("port", text)
        }
    }
}
