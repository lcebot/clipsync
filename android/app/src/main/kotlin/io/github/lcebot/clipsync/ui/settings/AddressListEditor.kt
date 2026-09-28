@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.common.NoLineBreaks
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.theme.Dimens

/**
 * An editable list of addresses: one field per row, a remove control on each, an add button below.
 *
 * The remove control is the field's own trailing icon, because it belongs to that row and to nothing
 * else. The last row has none: zero addresses is what the list's switch is for. Each row's label is
 * numbered, because several identical-looking fields give a screen reader nothing else to tell them
 * apart by.
 *
 * @param problems one entry per row, from the same validation pass as the rest of the form
 * @param firstRowOverride a save the file refused, shown on the first row when it has no problem of
 *                         its own
 */
@Composable
fun AddressListEditor(
    state: AddressListState,
    problems: List<RowProblem?>,
    firstRowOverride: String? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val switchName = stringResource(R.string.switch_direct)
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.SpacingField),
        modifier = modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        state.rows.forEachIndexed { i, row ->
            key(row.id) {
                val problem = problems.getOrNull(i)?.let { text(it, switchName) }
                    ?: firstRowOverride.takeIf { i == 0 }
                TextField(
                    state = row.field,
                    enabled = state.enabled,
                    label = { Text(stringResource(R.string.hint_peer, i + 1)) },
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    trailingIcon = if (state.removable) {
                        {
                            IconButton(onClick = haptics.ticking { state.remove(row) }) {
                                Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.peer_remove))
                            }
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    // A long host name wraps instead of scrolling out of sight; line breaks never
                    // get into it.
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                    inputTransformation = NoLineBreaks,
                    shape = TextFieldDefaults.roundedShape,
                    colors = TextFieldDefaults.tonalColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FilledTonalButton(
            onClick = haptics.ticking { state.add() },
            enabled = state.enabled,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.peer_add))
        }
    }
}

/**
 * What a row's problem says. A blank row names its way out: remove it, or, for the only row, which
 * cannot be removed, turn off the switch that asks for it.
 */
@Composable
private fun text(problem: RowProblem, switchName: String): String = when (problem) {
    is RowProblem.Required ->
        if (problem.lastRow) stringResource(R.string.peer_empty_last, switchName) else stringResource(R.string.peer_empty)
    is RowProblem.Invalid -> problem.message
    RowProblem.Duplicate -> stringResource(R.string.peer_duplicate)
    RowProblem.IsSelf -> stringResource(R.string.peer_is_self)
}
