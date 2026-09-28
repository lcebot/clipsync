@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.lcebot.clipsync.ui.common.Haptics
import io.github.lcebot.clipsync.ui.common.SectionTitle
import io.github.lcebot.clipsync.ui.common.SliderGrabHaptics
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.status.StatusUi
import io.github.lcebot.clipsync.ui.theme.Dimens
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * The Settings page: every field of the config file, validated on every keystroke.
 *
 * Every group of controls sits in one filled card with its section title outside it: a card marks
 * where one group ends and the next begins, which is what makes a switch and the controls it governs
 * read as one thing. A switch is its own card above the card it governs, so the governed card is the
 * hideable unit and the switch is never inside what it hides.
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

    // An edit anywhere retires a refused save: the message was about values that are gone.
    LaunchedEffect(settings) {
        snapshotFlow { settings.values() }.drop(1).collect { settings.saveProblem = null }
    }
    // An error inside a collapsed group is an error nobody can act on, so it opens the group.
    LaunchedEffect(validation.ownHasError) { settings.reveal(validation) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = Dimens.PageGutter),
    ) {
        Hideable(visible = battery != StatusUi.Battery.HIDDEN) {
            BatteryCard(battery, onBatteryFix, haptics)
        }

        SectionTitle(stringResource(R.string.section_connection))
        // Port and key first: "who we are", then "how we find each other".
        Card(Modifier.fillMaxWidth()) {
            CardBody {
                NumberField(settings.port, R.string.hint_port, validation.port ?: problem.on("port"))
                PskField(settings.psk, validation.psk ?: problem.on("psk"))
                RotateCheckbox(settings, haptics)
                // The three ways to end up with a key, in the order of how much work they are: make
                // one, take one from a device that has it, or be walked through the choice. They sit
                // at the key because the key is what they change.
                var confirmReplace by remember { mutableStateOf(false) }
                SettingsButton(
                    text = stringResource(R.string.psk_generate),
                    icon = R.drawable.ic_dice,
                    onClick = haptics.ticking {
                        // Replacing a usable key cuts off every other device at once; a mis-tap
                        // that costs re-pairing the household is asked about first.
                        if (Config.checkPsk(settings.psk.text.toString()) != null) settings.newPsk()
                        else confirmReplace = true
                    },
                    modifier = Modifier.padding(top = Dimens.SpacingSeam),
                )
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
                SettingsButton(stringResource(R.string.pair_button), R.drawable.ic_pair, haptics.ticking(onPair))
                SettingsButton(stringResource(R.string.setup_button), R.drawable.ic_setup, haptics.ticking(onSetup))
            }
        }

        OwnAddresses(settings, validation, problem.on("own_addresses"), haptics)

        // Named for HOW a peer is found, not for the route to it: a listed address is very often a
        // LAN address too. Named that way, neither switch needs a subtitle.
        SwitchCard(
            checked = settings.discovery,
            onCheckedChange = { settings.discovery = it },
            label = stringResource(R.string.switch_discovery),
            haptics = haptics,
            modifier = Modifier.padding(top = Dimens.SpacingGroup),
        )
        Hideable(visible = settings.discovery) {
            Card(Modifier.fillMaxWidth().padding(top = Dimens.SpacingSeam)) {
                CardBody {
                    val ms = SettingsRules.snap(Config.BROWSE_STEPS_MS, settings.browseIndex)
                    StepSlider(
                        label = stringResource(R.string.browse_label, ms),
                        valueText = "$ms ms",
                        positions = Config.BROWSE_STEPS_MS.size,
                        index = settings.browseIndex,
                        onIndex = { settings.browseIndex = it },
                        haptics = haptics,
                    )
                }
            }
        }

        SwitchCard(
            checked = settings.direct,
            onCheckedChange = { settings.direct = it },
            label = stringResource(R.string.switch_direct),
            haptics = haptics,
            modifier = Modifier.padding(top = Dimens.SpacingGroup),
        )
        Hideable(visible = settings.direct) {
            Card(Modifier.fillMaxWidth().padding(top = Dimens.SpacingSeam)) {
                CardBody {
                    AddressListEditor(
                        state = settings.peers,
                        problems = validation.peers,
                        firstRowOverride = problem.on("peers") ?: problem.on("discovery"),
                    )
                }
            }
        }
        // Under the pair rather than on either switch, because neither is wrong on its own; styled
        // and indented like a field's error so it reads as one.
        Hideable(visible = validation.noPath) {
            Text(
                text = stringResource(R.string.paths_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Dimens.SpacingHeading, start = Dimens.ErrorGutter, end = Dimens.ErrorGutter),
            )
        }

        SectionTitle(stringResource(R.string.section_limits))
        Card(Modifier.fillMaxWidth()) {
            CardBody {
                NumberField(settings.textKb, R.string.hint_text_kb, validation.textKb ?: problem.on("max_bytes"))
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
                val threads = SettingsRules.snap(Config.THREAD_STEPS, settings.threadsIndex)
                StepSlider(
                    label = stringResource(R.string.threads_label, threads),
                    valueText = threads.toString(),
                    positions = Config.THREAD_STEPS.size,
                    index = settings.threadsIndex,
                    onIndex = { settings.threadsIndex = it },
                    haptics = haptics,
                    modifier = Modifier.padding(top = Dimens.SpacingLabel),
                )
            }
        }
        // With the limits because relaying is bounded by the same sizes and costs the same data.
        SwitchCard(
            checked = settings.relayOptOut,
            onCheckedChange = { settings.relayOptOut = it },
            label = stringResource(R.string.switch_relay_opt_out),
            supporting = stringResource(R.string.relay_opt_out_help),
            haptics = haptics,
            modifier = Modifier.padding(top = Dimens.SpacingGroup),
        )

        SectionTitle(stringResource(R.string.section_files))
        Card(Modifier.fillMaxWidth().padding(bottom = Dimens.PageGutter)) {
            CardBody {
                SettingsField(
                    state = settings.path,
                    label = R.string.hint_path,
                    error = validation.path ?: problem.on("files_dir"),
                    helper = stringResource(R.string.helper_path),
                    keyboardType = KeyboardType.Uri,
                )
                NumberField(settings.keepHours, R.string.hint_keep_hours, validation.keepHours ?: problem.on("keep_hours"))
                NumberField(settings.keepMb, R.string.hint_keep_mb, validation.keepMb ?: problem.on("keep_max_mb"))
            }
        }
        // Clears the floating actions, so the last field can be scrolled out from under them.
        Spacer(Modifier.height(FAB_CLEARANCE))
    }
}

/** A fresh random key, straight into the field. Applied with the rest of the form, never saved here. */
private fun SettingsState.newPsk() {
    psk.setTextAndPlaceCursorAtEnd(Crypto.randomPskHex())
}

private fun SaveProblem?.on(key: String): String? = this?.takeIf { it.key == key }?.message

private val FAB_CLEARANCE = 80.dp

@Composable
private fun CardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.SpacingField),
        modifier = Modifier.fillMaxWidth().padding(Dimens.CardBodyInset),
        content = content,
    )
}

/** Shows or hides a group, folding the page around it instead of letting everything below jump. */
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

@Composable
private fun BatteryCard(battery: StatusUi.Battery, onFix: () -> Unit, haptics: Haptics) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpacingGroup),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(
                    if (battery == StatusUi.Battery.STILL_FROZEN) R.string.battery_still_frozen else R.string.battery_on,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            // Exempt already means there is nothing left to press; the text says what else to try.
            if (battery == StatusUi.Battery.OPTIMISED) {
                // Its label is onErrorContainer: a text button's default primary would be a colour
                // from a different pair on this background, reading as a stray accent rather than
                // the action that belongs to the message.
                TextButton(
                    onClick = haptics.ticking(onFix),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) { Text(stringResource(R.string.battery_allow)) }
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
    OutlinedTextField(
        state = state,
        label = { Text(stringResource(label)) },
        isError = error != null,
        // Absent when there is nothing to say, so a form that has just been fixed closes the gap
        // where the complaint was.
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = modifier,
    )
}

/** The key, hidden by default, with the reveal toggle in the field's trailing slot. */
@Composable
private fun PskField(state: TextFieldState, error: String?) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(stringResource(R.string.hint_psk)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (shown) R.string.psk_hide else R.string.psk_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Directly under the key, above the buttons: rotation is a property of the key, where the buttons
 * are ways of getting one. A checkbox and not a switch, because it is an attribute of that field,
 * not a section toggle. Ticking it explains, once, the three things the user cannot see happening.
 */
@Composable
private fun RotateCheckbox(settings: SettingsState, haptics: Haptics) {
    var explain by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = settings.pskRotate, role = Role.Checkbox) { on ->
                haptics.tick()
                settings.pskRotate = on
                if (on) explain = true
            },
    ) {
        Checkbox(checked = settings.pskRotate, onCheckedChange = null)
        Text(
            text = stringResource(R.string.psk_rotate),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            text = { Text(stringResource(R.string.psk_rotate_help)) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/**
 * This device's own addresses: the names by which OTHER devices reach this one, used to recognise a
 * target as this device. Collapsed by default, since most devices have no name of their own; open
 * whenever it has content or an error, because a setting the user cannot see is worse than one that
 * takes a tap. No switch: an empty list already means "no name of its own".
 *
 * The header is the touch target, not the whole card: a tap in the gap between two fields of an
 * open group must never close it. The card clips the header's ripple to its own rounded top.
 */
@Composable
private fun OwnAddresses(settings: SettingsState, validation: Validation, saveProblem: String?, haptics: Haptics) {
    val open = settings.ownExpanded
    val chevron by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
    val clickLabel = stringResource(if (open) R.string.own_collapse else R.string.own_expand)
    val state = stringResource(if (open) R.string.own_state_expanded else R.string.own_state_collapsed)
    Card(Modifier.fillMaxWidth().padding(top = Dimens.SpacingGroup)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = clickLabel, role = Role.Button) {
                    haptics.tick()
                    // Refused while the group holds an error; the reason is the red line in view.
                    settings.setOwnExpanded(!open, validation)
                }
                .semantics { stateDescription = state }
                .heightIn(min = 48.dp)
                .padding(Dimens.CardBodyInset),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.section_own), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.own_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Draws the state; the header owns it, so this has no description of its own.
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(24.dp).rotate(chevron),
            )
        }
        Hideable(visible = open) {
            AddressListEditor(
                state = settings.ownAddresses,
                problems = validation.ownAddresses,
                firstRowOverride = saveProblem,
                modifier = Modifier.padding(start = Dimens.CardBodyInset, end = Dimens.CardBodyInset, bottom = Dimens.CardBodyInset),
            )
        }
    }
}

/**
 * A switch alone in its card, the whole row its target. The label sits on the fields' left edge and
 * the track's end on their right edge, because the row takes the card body's inset.
 */
@Composable
private fun SwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch) {
                    haptics.tick()
                    onCheckedChange(it)
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = Dimens.CardBodyInset, vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/**
 * A slider over an index into a table of unevenly spaced steps. The label above it is the only
 * thing that names the setting, and a screen reader hears the real value ("4000 ms"), never the
 * index. Ticks: one on grab, one per detent while dragging, one on release.
 */
@Composable
private fun StepSlider(
    label: String,
    valueText: String,
    positions: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    SliderGrabHaptics(interactions, haptics)
    val state = remember(positions) {
        SliderState(value = index.toFloat(), steps = positions - 2, valueRange = 0f..(positions - 1).toFloat())
    }
    // A reload from the file moves the thumb; a drag already has it there.
    LaunchedEffect(index) {
        if (state.value.roundToInt() != index) state.value = index.toFloat()
    }
    val current by rememberUpdatedState(index)
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            state = state,
            onValueChange = { v ->
                state.value = v
                val i = v.roundToInt()
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

/**
 * An action inside a settings card. Outlined, so its border lines up with the text fields' borders
 * and the column reads outline to outline; start-aligned, because it belongs to the field above it.
 */
@Composable
internal fun SettingsButton(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
}
