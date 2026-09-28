@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.common.StatusDot
import io.github.lcebot.clipsync.ui.common.fadeThrough
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.log.LogPage
import io.github.lcebot.clipsync.ui.settings.SettingsPage
import io.github.lcebot.clipsync.ui.status.StatusSheet
import io.github.lcebot.clipsync.ui.status.StatusUi
import io.github.lcebot.clipsync.ui.theme.Dimens
import kotlinx.coroutines.flow.first

enum class Tab(val label: Int, val icon: Int) {
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
    LOG(R.string.nav_log, R.drawable.ic_log),
}

/**
 * Two pages behind a navigation bar, under one collapsing top app bar whose only action is the
 * connection status. The floating actions are a different pair per page.
 *
 * The pages cross-fade and never slide: M3 reserves lateral motion for steps in a sequence, and
 * navigation bar destinations are peers.
 */
@Composable
fun MainScreen(
    vm: MainViewModel,
    onPair: () -> Unit,
    onSetup: () -> Unit,
    onBatteryFix: () -> Unit,
    onCopy: (text: String, message: Int) -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val actionState by vm.actions.collectAsStateWithLifecycle()
    val settings = vm.settings
    // One pass per change of any field: it gates Apply, colours every field and opens the
    // own-addresses group when it holds an error.
    val validation by remember(settings) { derivedStateOf { settings.validate() } }

    var tab by rememberSaveable { mutableStateOf(Tab.SETTINGS) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme
    val context = LocalContext.current

    val settingsScroll = rememberScrollState()
    val logList = rememberLazyListState()
    val topBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(vm) {
        vm.messages.collect { m -> snackbar.showSnackbar(context.getString(m.text, *m.args)) }
    }

    // Back from the Log returns to Settings, the start destination, rather than leaving the app.
    // Only while it means something, so the system shows its own leaving-the-app preview otherwise.
    BackHandler(enabled = tab == Tab.LOG) {
        tab = Tab.SETTINGS
    }

    // The Log wants every pixel, so it arrives with the bar collapsed and, with no nested scroll
    // attached below, it stays that way. Settings reopens the bar when it is at the top, since a
    // collapsed bar over content that has not scrolled reads as broken.
    LaunchedEffect(tab) {
        val bar = topBar.state
        // The limit is only known once the bar has been measured; before that it is a sentinel, and
        // animating to it would fling the bar out of existence.
        if (tab == Tab.LOG) snapshotFlow { bar.heightOffsetLimit }.first { it > -Float.MAX_VALUE }
        val target = when {
            tab == Tab.LOG -> bar.heightOffsetLimit
            settingsScroll.value == 0 -> 0f
            else -> return@LaunchedEffect
        }
        animate(bar.heightOffset, target, animationSpec = motion.defaultSpatialSpec()) { v, _ -> bar.heightOffset = v }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(if (tab == Tab.SETTINGS) Modifier.nestedScroll(topBar.nestedScrollConnection) else Modifier),
        // Each bar takes its own inset and nothing else consumes any, so the bars' containers run to
        // the screen edges and nothing between them is displaced.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1) },
                actions = {
                    StatusChip(status, onClick = { sheetOpen = true })
                },
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                scrollBehavior = topBar,
            )
        },
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { t ->
                    ShortNavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (tab != t) haptics.tick()
                            tab = t
                        },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            ActionBar(
                tab = tab,
                actions = actionState.settingsActions(validation.ok),
                settingsScroll = settingsScroll,
                logList = logList,
                onApply = haptics.ticking(vm::apply),
                onStop = haptics.ticking(vm::stop),
                onCopyLog = haptics.ticking { onCopy(vm.log.text(), R.string.snack_log_copied) },
                onClearLog = haptics.ticking { vm.log.clear() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        // Readable content steps aside from a display cutout; the surfaces behind it do not. Compose
        // resolves start and end against the layout direction, so this holds in RTL as well.
        val cutout = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal).asPaddingValues()
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeThrough(motion.defaultEffectsSpec(), motion.defaultSpatialSpec()) },
            label = "page",
        ) { page ->
            when (page) {
                Tab.SETTINGS -> SettingsPage(
                    settings = settings,
                    validation = validation,
                    battery = status.battery,
                    scroll = settingsScroll,
                    contentPadding = combine(inner, cutout, extra = 0.dp, bottom = 0.dp),
                    onBatteryFix = onBatteryFix,
                    onPair = onPair,
                    onSetup = onSetup,
                )
                Tab.LOG -> LogPage(
                    state = vm.log,
                    listState = logList,
                    contentPadding = combine(inner, cutout, extra = Dimens.LogGutter, bottom = Dimens.LogGutter + FAB_CLEARANCE),
                )
            }
        }
    }

    if (sheetOpen) {
        StatusSheet(
            status = status,
            onDismiss = { sheetOpen = false },
            onCopy = { onCopy(it, R.string.snack_copied) },
        )
    }
}

/** What each scrolling page leaves at its foot, so its last line can scroll clear of the actions. */
private val FAB_CLEARANCE = 80.dp

@Composable
private fun combine(inner: PaddingValues, cutout: PaddingValues, extra: Dp, bottom: Dp): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = inner.calculateStartPadding(dir) + cutout.calculateStartPadding(dir) + extra,
        top = inner.calculateTopPadding() + extra,
        end = inner.calculateEndPadding(dir) + cutout.calculateEndPadding(dir) + extra,
        bottom = inner.calculateBottomPadding() + bottom,
    )
}

/**
 * The connection status: a dot and a count. A status readout that happens to open the details,
 * so it keeps a chip's size in the bar while M3 gives it a full-size touch target around it.
 * Tappable only when there is something to list; it keeps its looks when it is not.
 */
@Composable
private fun StatusChip(status: StatusUi, onClick: () -> Unit) {
    val chip = status.chip
    val colors = MaterialTheme.colorScheme
    val label = if (chip.count != null) stringResource(chip.label, chip.count) else stringResource(chip.label)
    val labelColor = if (chip.dimmed) colors.onSurfaceVariant else colors.onSurface
    val dot = when (status.link) {
        StatusUi.Link.CONNECTED -> colors.primary
        StatusUi.Link.CONNECTING -> colors.tertiary
        StatusUi.Link.IDLE -> colors.outline
    }
    val haptics = rememberHaptics()
    AssistChip(
        onClick = haptics.ticking(onClick),
        enabled = chip.clickable,
        label = { Text(label) },
        leadingIcon = { StatusDot(dot, pulsing = status.link == StatusUi.Link.CONNECTED) },
        colors = AssistChipDefaults.assistChipColors(labelColor = labelColor, disabledLabelColor = labelColor),
        border = AssistChipDefaults.assistChipBorder(
            enabled = chip.clickable,
            borderColor = colors.outlineVariant,
            disabledBorderColor = colors.outlineVariant,
        ),
        modifier = Modifier.padding(end = 12.dp),
    )
}

/**
 * The page's two actions, side by side and both labelled: on Settings, Start alone while the
 * service is stopped, Stop and Apply while it runs; on Log, Clear and Copy. Deliberately not a FAB
 * menu, a floating toolbar or a split button: each of those hides one of two peer actions behind a
 * press or a collapse, and both of these must be reachable at a glance. They shrink to their icons
 * while the page scrolls down and extend again on the way up or at the top.
 */
@Composable
private fun ActionBar(
    tab: Tab,
    actions: SettingsActions,
    settingsScroll: ScrollState,
    logList: LazyListState,
    onApply: () -> Unit,
    onStop: () -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
) {
    val settingsExpanded by remember {
        derivedStateOf { !settingsScroll.canScrollBackward || !settingsScroll.lastScrolledForward }
    }
    val logExpanded by remember {
        derivedStateOf { !logList.canScrollBackward || !logList.lastScrolledForward }
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f))
        },
        contentAlignment = Alignment.BottomEnd,
        label = "actions",
    ) { page ->
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ActionGap), verticalAlignment = Alignment.Bottom) {
            when (page) {
                Tab.SETTINGS -> {
                    AnimatedVisibility(visible = actions.showStop) {
                        ActionFab(R.string.action_stop, R.drawable.ic_stop, primary = false, actions.stopEnabled, settingsExpanded, onStop)
                    }
                    val start = actions.primary == SettingsActions.Primary.START
                    ActionFab(
                        text = if (start) R.string.action_start else R.string.action_apply,
                        icon = if (start) R.drawable.ic_play else R.drawable.ic_restart,
                        primary = true,
                        enabled = actions.primaryEnabled,
                        expanded = settingsExpanded,
                        onClick = onApply,
                    )
                }
                Tab.LOG -> {
                    ActionFab(R.string.log_clear, R.drawable.ic_clear, primary = false, enabled = true, logExpanded, onClearLog)
                    ActionFab(R.string.log_copy, R.drawable.ic_copy, primary = true, enabled = true, logExpanded, onCopyLog)
                }
            }
        }
    }
}

/**
 * An extended FAB that can be disabled, which the component itself cannot: a pressed Stop or Start
 * waits greyed out for the service to arrive. M3's disabled treatment (onSurface at 12 % and 38 %),
 * no elevation, no click, and announced as disabled.
 */
@Composable
private fun ActionFab(text: Int, icon: Int, primary: Boolean, enabled: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        primary -> colors.primaryContainer
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        primary -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        expanded = expanded,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        text = { Text(stringResource(text)) },
        containerColor = container,
        contentColor = content,
        elevation = if (enabled) FloatingActionButtonDefaults.elevation() else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = Modifier.semantics { if (!enabled) disabled() },
    )
}
