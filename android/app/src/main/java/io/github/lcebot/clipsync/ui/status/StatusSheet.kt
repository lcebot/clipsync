@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.clipsync.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lcebot.clipsync.Node
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.Status
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.theme.Dimens

/**
 * Peers, shown when the status chip is tapped.
 *
 * A bottom sheet rather than a dialog: M3 reserves dialogs for decisions that need an answer, and
 * this is supplementary content to glance at and dismiss. It renders the same [StatusUi] the chip
 * does, so "Connected (2)" can never sit above a sheet that lists one peer.
 */
@Composable
fun StatusSheet(status: StatusUi, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Dimens.SheetGutter),
        )
        // Under the title, not at the foot: a sheet arrives from the bottom edge, so its last line is
        // the one the animation sweeps past and the one a partly open sheet cuts off.
        Text(
            text = stringResource(R.string.dialog_tap_to_copy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 2.dp, bottom = Dimens.SpacingField),
        )
        LazyColumn(Modifier.padding(bottom = Dimens.PageGutter)) {
            val rows = rowsFor(status)
            items(count = rows.size, key = { rows[it].key }) { i ->
                val row = rows[i]
                val motion = Modifier.animateItem()
                when (row) {
                    is SheetRow.Header -> Header(stringResource(row.text), motion)
                    is SheetRow.Device -> DeviceCard(row, onCopy, motion)
                }
            }
        }
    }
}

/**
 * One row of the sheet. [key] is identity across refreshes, not a label, so a peer that moves
 * between groups animates as itself.
 */
@Immutable
private sealed interface SheetRow {
    val key: String

    data class Header(override val key: String, val text: Int) : SheetRow

    data class Device(
        override val key: String,
        val name: String,
        val fields: List<Field>,
        /** What a tap copies, or null for the "nothing connected" card, which copies nothing. */
        val copy: String?,
    ) : SheetRow
}

@Immutable
private data class Field(val label: Int, val value: String, val why: Status.Why = Status.Why.WAITING, val mono: Boolean = false)

/**
 * The sheet's rows in reading order. A group without members has no heading, so an empty group
 * never announces itself. Relaying comes first because it explains why this device is awake.
 */
private fun rowsFor(s: StatusUi): List<SheetRow> {
    val rows = ArrayList<SheetRow>()
    if (s.relayCount > 0) rows += SheetRow.Header("h:relay", R.string.sheet_relaying)
    fun group(key: String, header: Int, peers: List<PeerUi>) {
        if (peers.isEmpty()) return
        rows += SheetRow.Header(key, header)
        peers.forEach { p ->
            val name = p.name.orUnknown()
            val address = p.address.orUnknown()
            rows += SheetRow.Device(
                key = "p:${p.id}",
                name = name,
                fields = listOf(
                    Field(R.string.field_id, p.id?.let { Node.shortId(it) }.orUnknown(), mono = true),
                    Field(R.string.field_type, p.type.orUnknown()),
                    Field(R.string.field_address, address, mono = true),
                ),
                copy = name + "\n" + p.id.orEmpty() + "\n" + address,
            )
        }
    }
    group("h:lan", R.string.sheet_on_lan, s.peers.filter { it.lan })
    group("h:wan", R.string.sheet_over_internet, s.peers.filter { !it.lan })
    if (s.indirectPeers.isNotEmpty()) {
        rows += SheetRow.Header("h:indirect", R.string.sheet_indirect)
        s.indirectPeers.forEach { ip ->
            val name = ip.name.orUnknown()
            rows += SheetRow.Device(
                key = "i:${ip.id}",
                name = name,
                fields = listOf(
                    Field(R.string.field_id, ip.id?.let { Node.shortId(it) }.orUnknown(), mono = true),
                    Field(R.string.field_type, ip.type.orUnknown()),
                    Field(R.string.field_via, ip.via.orUnknown()),
                ),
                copy = name + "\n" + ip.id.orEmpty(),
            )
        }
    }
    if (s.targets.isNotEmpty()) {
        rows += SheetRow.Header("h:down", R.string.sheet_not_connected)
        s.targets.forEach { t ->
            val target = t.target.orEmpty()
            rows += SheetRow.Device(
                key = "t:$target",
                name = target,
                fields = listOf(Field(R.string.field_reason, t.reason.orUnknown(), why = t.why)),
                copy = target,
            )
        }
    }
    if (rows.isEmpty()) rows += SheetRow.Device("none", "", emptyList(), copy = null)
    return uniqueKeys(rows)
}

/**
 * Keys come from what peers report about themselves, which can repeat (a missing id, one device
 * reached two ways); a repeated key crashes a lazy list. Later repeats get a suffix, so the first
 * keeps its identity and animation.
 */
private fun uniqueKeys(rows: List<SheetRow>): List<SheetRow> {
    val seen = HashMap<String, Int>()
    return rows.map { row ->
        val n = seen.merge(row.key, 1, Int::plus) ?: 1
        if (n == 1) row
        else when (row) {
            is SheetRow.Header -> row.copy(key = "${row.key}#$n")
            is SheetRow.Device -> row.copy(key = "${row.key}#$n")
        }
    }
}

private fun String?.orUnknown(): String = if (isNullOrEmpty()) "?" else this

/** Same treatment as a Settings section title, so the two surfaces read as one app. */
@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = Dimens.CardPadding, bottom = Dimens.SpacingGroup)
            .semantics { heading() },
    )
}

/**
 * One device: a peer that is up, or a target that is not. The whole card copies its details, and
 * the click is labelled, because nothing in the card's own text says what pressing it does.
 * Labelled fields in two columns, so values line up down a card and can be compared across cards.
 */
@Composable
private fun DeviceCard(row: SheetRow.Device, onCopy: (String) -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    val copyLabel = stringResource(R.string.card_copy)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(Dimens.CardPadding)) {
            Text(
                text = row.name.ifEmpty { stringResource(R.string.sheet_none) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.fields.isNotEmpty()) {
                // A field is closer to its neighbours than the group is to the name, which is what
                // makes the fields read as one block under it.
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSeam),
                    modifier = Modifier.padding(top = Dimens.SpacingGroup),
                ) {
                    row.fields.forEach { FieldRow(it) }
                }
            }
        }
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, bottom = Dimens.SpacingGroup)
    val copy = row.copy
    if (copy == null) {
        Card(modifier = cardModifier) { body() }
    } else {
        // clickable on a clipped plain card rather than Card(onClick), because only clickable takes
        // a label for the action; the clip keeps the ripple on the card's rounded shape.
        Card(
            modifier = cardModifier
                .clip(CardDefaults.shape)
                .clickable(onClickLabel = copyLabel, onClick = haptics.ticking { onCopy(copy) }),
        ) { body() }
    }
}

/**
 * The label column has a minimum width, not a fixed one: at a large font scale a fixed column clips
 * the label, and a label pushing its value right is a smaller problem than one that cannot be read.
 */
@Composable
private fun FieldRow(field: Field) {
    Row {
        Text(
            text = stringResource(field.label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 84.dp)
                .padding(end = 8.dp)
                .alignByBaseline(),
        )
        Text(
            text = field.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (field.mono) FontFamily.Monospace else null,
            color = whyColor(field.why),
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/**
 * Red means the user has something to fix; tertiary is a peer that announced it is asleep, an accent
 * and not an alarm; a note (such as "same device as") recedes; everything else reads as ordinary.
 */
@Composable
private fun whyColor(why: Status.Why): Color = when (why) {
    Status.Why.FAULT -> MaterialTheme.colorScheme.error
    Status.Why.ASLEEP -> MaterialTheme.colorScheme.tertiary
    Status.Why.NOTED -> MaterialTheme.colorScheme.onSurfaceVariant
    Status.Why.WAITING -> MaterialTheme.colorScheme.onSurface
}
