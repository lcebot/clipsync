@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.pair

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.clipsync.Mdns
import io.github.lcebot.clipsync.Pairing
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * The pairing sheet, both sides of it. Shown while [PairViewModel.session] holds a session;
 * dismissing it in any way ends the session.
 *
 * A bottom sheet, like the peer sheet, so the two read as one app and predictive back comes with
 * the surface.
 */
@Composable
fun PairSheetHost(vm: PairViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Leaving the screen ends the session: an offering window must not keep handing out the key
    // behind the home screen or a locked phone. A rotation also stops the activity, and is the one
    // stop that does not count, because the same sheet comes straight back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.dismiss()
    }
    val c = session ?: return
    // Keyed on the session so a new one starts from a fresh sheet, code field included.
    key(c) { PairSheet(c, onDismiss = vm::dismiss) }
}

@Composable
private fun PairSheet(c: PairController, onDismiss: () -> Unit) {
    val ui by c.ui.collectAsStateWithLifecycle()
    val ask by c.ask.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        PairContent(
            ui = ui,
            onClose = onDismiss,
            onBrowse = c::browse,
            onPick = c::pick,
            onConnect = c::connect,
        )
    }

    ask?.let { a ->
        // The device's own name and kind are the whole content: knowing the code got the caller
        // this far, and the one question a person can still answer is "is that one of mine?".
        AlertDialog(
            onDismissRequest = { c.answer(a, yes = false) },
            title = { Text(stringResource(R.string.pair_ask_title)) },
            text = { Text(stringResource(R.string.pair_ask_body, a.device, a.type)) },
            confirmButton = { TextButton(onClick = { c.answer(a, yes = true) }) { Text(stringResource(R.string.pair_ask_yes)) } },
            dismissButton = { TextButton(onClick = { c.answer(a, yes = false) }) { Text(stringResource(R.string.pair_ask_no)) } },
        )
    }
}

@Composable
private fun PairContent(
    ui: PairUi,
    onClose: () -> Unit,
    onBrowse: () -> Unit,
    onPick: (Mdns.Instance) -> Unit,
    onConnect: (Mdns.Instance, String) -> Unit,
) {
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme
    val codeField = rememberTextFieldState()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            // Every part below appears and disappears as the state moves on; the sheet's height
            // follows with the scheme's own spring rather than jumping.
            .animateContentSize(motion.defaultSpatialSpec()),
    ) {
        Text(
            text = stringResource(if (ui.offering) R.string.pair_offer_title else R.string.pair_join_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Dimens.SheetGutter),
        )
        // Two lines reserved: this line carries eight different strings over the sheet's life and
        // they are not the same length, so without the reservation the sheet grows and shrinks by a
        // line each time one replaces another, three times in the few seconds of joining. A
        // reservation, not a cap: anything longer still wraps.
        Text(
            text = body(ui),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            modifier = Modifier.padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 4.dp),
        )

        Part(visible = ui is PairUi.Opening || ui is PairUi.Offering) {
            Code(ui as? PairUi.Offering)
        }

        Part(visible = progressText(ui) != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 16.dp),
            ) {
                // M3 Expressive's own waiting shape. Indeterminate because nothing here knows how
                // long a multicast answer or a derivation takes.
                LoadingIndicator()
                Text(
                    text = progressText(ui).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
            }
        }

        Part(visible = ui is PairUi.Offering && ui.given.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp)) {
                (ui as? PairUi.Offering)?.given?.forEach { device ->
                    Text(
                        text = stringResource(R.string.pair_offer_gave, device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = Dimens.SheetGutter, vertical = 2.dp),
                    )
                }
            }
        }

        Part(visible = ui is PairUi.Picking) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpacingGroup),
                modifier = Modifier.padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 12.dp),
            ) {
                (ui as? PairUi.Picking)?.devices?.forEach { device ->
                    Card(
                        onClick = haptics.ticking { onPick(device) },
                        colors = CardDefaults.cardColors(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(Dimens.CardPadding),
                        )
                    }
                }
            }
        }

        val entering = ui as? PairUi.EnteringCode
        val checking = ui as? PairUi.Checking
        // A code typed for one device means nothing to another.
        val codeDevice = entering?.device ?: checking?.device
        LaunchedEffect(codeDevice) { if (codeDevice != null) codeField.clearText() }
        Part(visible = entering != null || checking != null) {
            val device = entering?.device ?: checking?.device
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(device) {
                focus.requestFocus()
                keyboard?.show()
            }
            OutlinedTextField(
                state = codeField,
                label = { Text(stringResource(R.string.pair_code_hint)) },
                readOnly = checking != null,
                isError = entering?.error != null,
                // Always present, so the line is reserved once: a wrong code then adds a message
                // instead of a message and a row of height, while the user is watching for a verdict.
                supportingText = { Text(entering?.error.orEmpty()) },
                inputTransformation = CodeInput,
                outputTransformation = CodeGrouping,
                // A digits keyboard: the code is not a secret from the person holding the phone.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                onKeyboardAction = { if (device != null && checking == null) onConnect(device, codeField.text.toString()) },
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.2.em),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 16.dp)
                    .focusRequester(focus),
            )
        }

        val action = action(ui)
        Part(visible = action != null) {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 16.dp),
            ) {
                if (action != null) {
                    val onClick = haptics.ticking {
                        when (action) {
                            Action.CANCEL, Action.DONE, Action.CLOSE -> onClose()
                            Action.SEARCH_AGAIN -> onBrowse()
                            Action.CONNECT -> entering?.let { onConnect(it.device, codeField.text.toString()) }
                        }
                    }
                    val label = stringResource(action.label)
                    // "Cancel" leaves before anything came of the window and is the quiet button;
                    // everything else finishes or moves the conversation on.
                    if (action == Action.CANCEL) FilledTonalButton(onClick = onClick) { Text(label) }
                    else Button(onClick = onClick) { Text(label) }
                }
            }
        }
    }
}

/**
 * The code, grouped "123 456 789" for reading aloud and typing on another phone; only the nine
 * digits are ever compared or derived from.
 *
 * Before the code exists this shows a grey placeholder of the same eleven characters, so the sheet
 * measures itself with this line from the start and does not grow when the real code arrives.
 * headlineLarge and no tracking: eleven monospace characters at that size fit a narrow phone inside
 * the sheet gutters, and it is still display type that follows the user's font scale.
 */
@Composable
private fun Code(offering: PairUi.Offering?) {
    val text = offering?.let { Pairing.grouped(it.code) } ?: stringResource(R.string.pair_code_placeholder)
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp),
        color = if (offering != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, top = 16.dp),
    )
}

/** Each optional part fades and folds in place, so the sheet reads as one surface changing. */
@Composable
private fun Part(visible: Boolean, content: @Composable () -> Unit) {
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
private fun body(ui: PairUi): String = when (ui) {
    is PairUi.Opening, is PairUi.Offering -> stringResource(R.string.pair_offer_body)
    is PairUi.Browsing -> stringResource(R.string.pair_join_body)
    is PairUi.NoneFound -> stringResource(R.string.pair_none_found)
    is PairUi.Picking -> stringResource(R.string.pair_pick)
    is PairUi.EnteringCode -> stringResource(R.string.pair_enter_code, ui.device.name)
    is PairUi.Checking -> stringResource(R.string.pair_enter_code, ui.device.name)
    is PairUi.Finished -> ui.message
}

/** The one line beside the loading indicator, or null when nothing is being waited on. */
@Composable
private fun progressText(ui: PairUi): String? = when (ui) {
    is PairUi.Opening -> stringResource(R.string.pair_opening)
    is PairUi.Offering -> stringResource(R.string.pair_waiting_for, secondsLeft(ui.closesAt))
    is PairUi.Browsing -> stringResource(R.string.pair_searching)
    is PairUi.Checking -> stringResource(if (ui.connecting) R.string.pair_connecting else R.string.pair_checking)
    else -> null
}

/**
 * Whole seconds until [closesAt], rounded up, and redrawn exactly when the displayed number
 * changes rather than on a fixed beat that would drift against it.
 */
@Composable
private fun secondsLeft(closesAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(closesAt) {
        while (true) {
            now = System.currentTimeMillis()
            val left = (closesAt - now).coerceAtLeast(0)
            val secs = (left + 999) / 1000
            if (secs <= 0) break
            delay(left - (secs - 1) * 1000)
        }
    }
    return (((closesAt - now).coerceAtLeast(0)) + 999) / 1000
}

private enum class Action(val label: Int) {
    CANCEL(R.string.pair_cancel),
    DONE(R.string.pair_done),
    CLOSE(R.string.pair_close),
    SEARCH_AGAIN(R.string.pair_search_again),
    CONNECT(R.string.pair_connect),
}

private fun action(ui: PairUi): Action? = when (ui) {
    is PairUi.Opening -> Action.CANCEL
    is PairUi.Offering -> if (ui.given.isEmpty()) Action.CANCEL else Action.DONE
    is PairUi.NoneFound -> Action.SEARCH_AGAIN
    is PairUi.EnteringCode -> Action.CONNECT
    is PairUi.Finished -> Action.CLOSE
    is PairUi.Browsing, is PairUi.Picking, is PairUi.Checking -> null
}

/**
 * Digits only, at most eleven. The other device shows the code grouped, so eleven is the bound of
 * what a person copies down, not the definition of the code; the controller requires exactly nine
 * digits. Filtering here is a convenience; the controller strips again before deriving anything.
 */
private val CodeInput: InputTransformation = DigitsOnly.then(InputTransformation.maxLength(Pairing.CODE_DIGITS + 2))

private object DigitsOnly : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val digits = asCharSequence().filter { it in '0'..'9' }
        if (digits.length != length) replace(0, length, digits)
    }
}

/** Shows typed digits in the same three groups the other screen uses, without storing the spaces. */
private object CodeGrouping : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        if (length > 6) insert(6, " ")
        if (length > 3) insert(3, " ")
    }
}
