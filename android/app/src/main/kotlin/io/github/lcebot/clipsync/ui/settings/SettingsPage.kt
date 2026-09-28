@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lcebot.clipsync.Config
import io.github.lcebot.clipsync.Crypto
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.common.ContentItem
import io.github.lcebot.clipsync.ui.common.Group
import io.github.lcebot.clipsync.ui.common.Haptics
import io.github.lcebot.clipsync.ui.common.SliderGrabHaptics
import io.github.lcebot.clipsync.ui.common.SwitchItem
import io.github.lcebot.clipsync.ui.common.TonalField
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.status.StatusUi
import io.github.lcebot.clipsync.ui.theme.Dimens
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * The Settings page: every field of the config file, validated on every keystroke.
 *
 * Laid out as expressive list groups: each group is one rounded block of rows, so where a group
 * ends and the next begins is visible without dividers. A switch is the first row of the group it
 * governs, and the rows it reveals join that same block, so the switch and what it controls read as
 * one thing.
 */
@Composable
fun SettingsPage(
    settings: SettingsState,
    validation: Validation,
    battery: StatusUi.Battery,
    scroll: ScrollState,
    contentPadding: PaddingValues,
    onBatteryFix: () -> Unit,
    onPair: () -> Unit,
    onSetup: () -> Unit,
) {
    val haptics = rememberHaptics()
    val problem = settings.saveProblem
    var confirmReplace by remember { mutableStateOf(false) }
    var explainRotation by remember { mutableStateOf(false) }

    // An edit anywhere retires a refused save: the message was about values that are gone.
    LaunchedEffect(settings) {
        snapshotFlow { settings.values() }.drop(1).collect { settings.saveProblem = null }
    }
    // An error inside a collapsed group is an error nobody can act on, so it opens the group.
    LaunchedEffect(validation.ownHasError) { settings.reveal(validation) }

    // No arranged spacing: each block carries its own gap above it, so a block that folds away takes
    // its gap with it inside the same animation instead of dropping it at the end.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = Dimens.PageGutter),
    ) {
        Hideable(visible = battery != StatusUi.Battery.HIDDEN) {
            BatteryNotice(battery, onBatteryFix, haptics)
        }

        // Port and key first: "who we are", then "how we find each other". The key's own actions
        // (a fresh random key, show or hide) live in its field; the ways to hand it to another
        // device close the group.
        Group(title = stringResource(R.string.section_connection), modifier = Modifier.padding(top = TitledGroupGap)) {
            item { FieldItem(it) { NumberField(settings.port, R.string.hint_port, validation.port ?: problem.on("port")) } }
            item {
                FieldItem(it) {
                    PskField(
                        state = settings.psk,
                        error = validation.psk ?: problem.on("psk"),
                        onGenerate = {
                            // Replacing a usable key cuts off every other device at once; a mis-tap
                            // that costs re-pairing the household is asked about first.
                            if (Config.checkPsk(settings.psk.text.toString()) != null) settings.newPsk()
                            else confirmReplace = true
                        },
                    )
                }
            }
            item {
                SwitchItem(
                    shapes = it,
                    title = stringResource(R.string.psk_rotate),
                    checked = settings.pskRotate,
                    onCheckedChange = { on ->
                        settings.pskRotate = on
                        // Said once, when it is turned on: how often, who is still let in, and the
                        // one bad outcome.
                        if (on) explainRotation = true
                    },
                )
            }
            // The two ways to get this key onto another device, as a pair of buttons at the foot of
            // the key's group: pairing is the one almost everyone wants, so it carries the tone.
            item {
                ContentItem(it) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = haptics.ticking(onPair),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.pair_button)) }
                        OutlinedButton(
                            onClick = haptics.ticking(onSetup),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.setup_button)) }
                    }
                }
            }
        }

        OwnAddresses(settings, validation, problem.on("own_addresses"), haptics, Modifier.padding(top = GroupGap))

        // Named for HOW a peer is found, not for the route to it: a listed address is very often a
        // LAN address too. Named that way, neither switch needs a supporting line.
        Group(modifier = Modifier.padding(top = GroupGap)) {
            item {
                SwitchItem(
                    shapes = it,
                    title = stringResource(R.string.switch_discovery),
                    checked = settings.discovery,
                    onCheckedChange = { settings.discovery = it },
                )
            }
            if (settings.discovery) item { shapes ->
                val ms = SettingsRules.snap(Config.BROWSE_STEPS_MS, settings.browseIndex)
                StepSlider(
                    shapes = shapes,
                    label = stringResource(R.string.browse_label),
                    valueText = stringResource(R.string.value_ms, ms),
                    positions = Config.BROWSE_STEPS_MS.size,
                    index = settings.browseIndex,
                    onIndex = { settings.browseIndex = it },
                    haptics = haptics,
                )
            }
        }

        Group(modifier = Modifier.padding(top = GroupGap)) {
            item {
                SwitchItem(
                    shapes = it,
                    title = stringResource(R.string.switch_direct),
                    checked = settings.direct,
                    onCheckedChange = { settings.direct = it },
                )
            }
            if (settings.direct) item {
                FieldItem(it) {
                    AddressListEditor(
                        state = settings.peers,
                        problems = validation.peers,
                        firstRowOverride = problem.on("peers") ?: problem.on("discovery"),
                    )
                }
            }
        }
        // Under the pair rather than on either switch, because neither is wrong on its own.
        Hideable(visible = validation.noPath) {
            Text(
                text = stringResource(R.string.paths_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        Group(title = stringResource(R.string.section_limits), modifier = Modifier.padding(top = TitledGroupGap)) {
            item { FieldItem(it) { NumberField(settings.textKb, R.string.hint_text_kb, validation.textKb ?: problem.on("max_bytes")) } }
            item {
                FieldItem(it) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingField)) {
                        NumberField(
                            settings.fileMb, R.string.hint_file_mb, validation.fileMb ?: problem.on("max_file_bytes"),
                            Modifier.weight(1f),
                        )
                        NumberField(
                            settings.fileMbLocal, R.string.hint_file_mb_local,
                            validation.fileMbLocal ?: problem.on("max_file_bytes_local"),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            item { shapes ->
                val threads = SettingsRules.snap(Config.THREAD_STEPS, settings.threadsIndex)
                StepSlider(
                    shapes = shapes,
                    label = stringResource(R.string.threads_label),
                    valueText = threads.toString(),
                    positions = Config.THREAD_STEPS.size,
                    index = settings.threadsIndex,
                    onIndex = { settings.threadsIndex = it },
                    haptics = haptics,
                )
            }
            // With the limits because relaying is bounded by the same sizes and costs the same data.
            item {
                SwitchItem(
                    shapes = it,
                    title = stringResource(R.string.switch_relay_opt_out),
                    supporting = stringResource(R.string.relay_opt_out_help),
                    checked = settings.relayOptOut,
                    onCheckedChange = { settings.relayOptOut = it },
                )
            }
        }

        Group(title = stringResource(R.string.section_files), modifier = Modifier.padding(top = TitledGroupGap)) {
            item {
                FieldItem(it) {
                    SettingsField(
                        state = settings.path,
                        label = R.string.hint_path,
                        error = validation.path ?: problem.on("files_dir"),
                        helper = stringResource(R.string.helper_path),
                        keyboardType = KeyboardType.Uri,
                    )
                }
            }
            item { FieldItem(it) { NumberField(settings.keepHours, R.string.hint_keep_hours, validation.keepHours ?: problem.on("keep_hours")) } }
            item { FieldItem(it) { NumberField(settings.keepMb, R.string.hint_keep_mb, validation.keepMb ?: problem.on("keep_max_mb")) } }
        }
        // Clears the floating actions, so the last field can be scrolled out from under them.
        Spacer(Modifier.height(FAB_CLEARANCE))
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.psk_replace_title)) },
            text = { Text(stringResource(R.string.psk_replace_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    settings.newPsk()
                }) { Text(stringResource(R.string.psk_replace_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    if (explainRotation) {
        AlertDialog(
            onDismissRequest = { explainRotation = false },
            text = { Text(stringResource(R.string.psk_rotate_help)) },
            confirmButton = { TextButton(onClick = { explainRotation = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** A fresh random key, straight into the field. Applied with the rest of the form, never saved here. */
private fun SettingsState.newPsk() {
    psk.setTextAndPlaceCursorAtEnd(Crypto.randomPskHex())
}

private fun SaveProblem?.on(key: String): String? = this?.takeIf { it.key == key }?.message

/** Between groups: enough that each block reads as its own. */
private val GroupGap = 16.dp

/** Above a titled group, whose title brings its own space: the two add up to about a group gap. */
private val TitledGroupGap = 8.dp

private val FAB_CLEARANCE = 80.dp

/** A row holding text fields, inset a little less than a headline so the fields can be wide. */
@Composable
private fun FieldItem(shapes: ListItemShapes, content: @Composable () -> Unit) {
    ContentItem(shapes = shapes, contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** Shows or hides a whole block, folding the page around it instead of letting everything jump. */
@Composable
private fun ColumnScope.Hideable(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.defaultSpatialSpec()),
    ) {
        content()
    }
}

/**
 * The background-freeze warning, in the error container because it explains why nothing may be
 * syncing. Its action takes the container's own on-colour: the default primary would be a colour
 * from a different pair on this background.
 */
@Composable
private fun BatteryNotice(battery: StatusUi.Battery, onFix: () -> Unit, haptics: Haptics) {
    val c = MaterialTheme.colorScheme
    Group(modifier = Modifier.padding(top = 8.dp)) {
        item { shapes ->
            ContentItem(
                shapes = shapes,
                colors = ListItemDefaults.segmentedColors(
                    containerColor = c.errorContainer,
                    contentColor = c.onErrorContainer,
                    trailingContentColor = c.onErrorContainer,
                ),
                // Exempt already means there is nothing left to press; the text says what else to try.
                trailingContent = if (battery == StatusUi.Battery.OPTIMISED) {
                    {
                        TextButton(
                            onClick = haptics.ticking(onFix),
                            colors = ButtonDefaults.textButtonColors(contentColor = c.onErrorContainer),
                        ) { Text(stringResource(R.string.battery_allow)) }
                    }
                } else null,
            ) {
                Text(
                    text = stringResource(
                        if (battery == StatusUi.Battery.STILL_FROZEN) R.string.battery_still_frozen else R.string.battery_on,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NumberField(state: TextFieldState, label: Int, error: String?, modifier: Modifier = Modifier.fillMaxWidth()) {
    SettingsField(state, label, error, keyboardType = KeyboardType.Number, modifier = modifier)
}

@Composable
private fun SettingsField(
    state: TextFieldState,
    label: Int,
    error: String?,
    helper: String? = null,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val supporting = error ?: helper
    TextField(
        state = state,
        label = { Text(stringResource(label)) },
        isError = error != null,
        // Absent when there is nothing to say, so a form that has just been fixed closes the gap
        // where the complaint was.
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = TonalField.Shape,
        colors = TonalField.colors(),
        modifier = modifier,
    )
}

/** The key, hidden by default, with the reveal toggle in the field's trailing slot. */
@Composable
private fun PskField(state: TextFieldState, error: String?, onGenerate: () -> Unit) {
    val haptics = rememberHaptics()
    var shown by rememberSaveable { mutableStateOf(false) }
    SecureTextField(
        state = state,
        label = { Text(stringResource(R.string.hint_psk)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        // Both of the key's own actions live in the field, where the key is: a fresh random key,
        // and showing or hiding it.
        trailingIcon = {
            Row {
                IconButton(onClick = haptics.ticking(onGenerate)) {
                    Icon(painterResource(R.drawable.ic_dice), contentDescription = stringResource(R.string.psk_generate))
                }
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = stringResource(if (shown) R.string.psk_hide else R.string.psk_show),
                    )
                }
            }
        },
        shape = TonalField.Shape,
        colors = TonalField.colors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * This device's own addresses: the names by which OTHER devices reach this one, used to recognise a
 * target as this device. Collapsed by default, since most devices have no name of their own; open
 * whenever it has content or an error, because a setting the user cannot see is worse than one that
 * takes a tap. No switch: an empty list already means "no name of its own".
 *
 * The header row is the touch target, not the whole group: a tap in the gap between two fields of
 * an open group must never close it.
 */
@Composable
private fun OwnAddresses(
    settings: SettingsState,
    validation: Validation,
    saveProblem: String?,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val open = settings.ownExpanded
    val chevron by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
    val clickLabel = stringResource(if (open) R.string.own_collapse else R.string.own_expand)
    val state = stringResource(if (open) R.string.own_state_expanded else R.string.own_state_collapsed)
    Group(modifier = modifier) {
        item { shapes ->
            // A plain item made clickable, rather than the clickable item overload, because only
            // clickable takes a label for the action; the clip keeps the ripple on the item's shape.
            ContentItem(
                shapes = shapes,
                modifier = Modifier
                    .clip(shapes.shape)
                    .clickable(onClickLabel = clickLabel, role = Role.Button) {
                        haptics.tick()
                        // Refused while the group holds an error; the reason is the red line in view.
                        settings.setOwnExpanded(!open, validation)
                    }
                    .semantics { stateDescription = state },
                supportingContent = { Text(stringResource(R.string.own_help)) },
                trailingContent = {
                    // Draws the state; the row owns it, so this has no description of its own.
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).rotate(chevron),
                    )
                },
            ) { Text(stringResource(R.string.section_own)) }
        }
        if (open) item {
            FieldItem(it) {
                AddressListEditor(
                    state = settings.ownAddresses,
                    problems = validation.ownAddresses,
                    firstRowOverride = saveProblem,
                )
            }
        }
    }
}

/**
 * A slider over an index into a table of unevenly spaced steps, as a row: the label names the
 * setting and shows the value, and a screen reader hears the real value ("4000 ms"), never the
 * index. Ticks: one on grab, one per detent while dragging, one on release.
 */
@Composable
private fun StepSlider(
    shapes: ListItemShapes,
    label: String,
    valueText: String,
    positions: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    haptics: Haptics,
) {
    val interactions = remember { MutableInteractionSource() }
    SliderGrabHaptics(interactions, haptics)
    // The state runs over 0 to 1, with one detent per position between the ends; the index is the
    // position scaled back to the table.
    val last = (positions - 1).toFloat()
    val state = remember(positions) { SliderState(value = index / last, steps = positions - 2) }
    // A reload from the file moves the thumb; a drag already has it there.
    LaunchedEffect(index) {
        if ((state.value * last).roundToInt() != index) state.value = index / last
    }
    val current by rememberUpdatedState(index)
    ContentItem(shapes = shapes) {
        Column(Modifier.fillMaxWidth()) {
            // The setting's name on the left, its value on the right in the accent colour, the way
            // system settings show a slider's current value.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f))
                Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                state = state,
                onValueChange = { v ->
                    state.value = v
                    // One tick per detent rather than per drag event, so the feedback matches the steps.
                    val i = (v * last).roundToInt()
                    if (i != current) {
                        haptics.detent()
                        onIndex(i)
                    }
                },
                interactionSource = interactions,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = label
                        stateDescription = valueText
                    },
            )
        }
    }
}
