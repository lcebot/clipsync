@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.ui.common.rememberHaptics
import io.github.lcebot.clipsync.ui.common.sharedAxisX
import io.github.lcebot.clipsync.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Setting up ClipSync, as a screen rather than a dialog: a dialog's padding is not symmetric and
 * everything inside inherits that, while here one gutter ([Dimens.WelcomeGutter]) is the only
 * horizontal measurement on both pages.
 */
@Composable
fun WelcomeScreen(
    onJoin: () -> Unit,
    onGenerate: () -> Unit,
    onManual: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // The entrance plays once per visit, not again after a rotation.
    var entered by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme

    // Only the second page consumes back. On the first, back is the system's, so its predictive
    // animation shows the user where they are going.
    BackHandler(enabled = page == 1) {
        page = 0
        haptics.tick()
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            // Shared X axis: the two pages are steps in a sequence, which is what lateral motion means.
            transitionSpec = {
                sharedAxisX(targetState > initialState, motion.defaultEffectsSpec(), motion.defaultSpatialSpec())
            },
            label = "welcome page",
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        ) { p ->
            if (p == 0) {
                IntroPage(animateEntrance = !entered, onEntered = { entered = true }) {
                    page = 1
                    haptics.tick()
                }
            } else {
                ChoosePage(
                    onJoin = haptics.ticking(onJoin),
                    onGenerate = haptics.ticking(onGenerate),
                    onManual = haptics.ticking(onManual),
                )
            }
        }
    }
}

@Composable
private fun IntroPage(animateEntrance: Boolean, onEntered: () -> Unit, onNext: () -> Unit) {
    val rise = rememberRise(count = 4, animate = animateEntrance, onDone = onEntered)
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.WelcomeGutter, vertical = 32.dp)) {
        // The hero, in a tonal container, because a bare glyph on a surface reads as an icon that
        // lost its button. One of M3 Expressive's own shapes rather than a circle: this is the first
        // thing a new install shows and the one place the app can afford a bit of character. The
        // launcher glyph, because it should look like what the user just tapped. It fills the
        // container: the adaptive-icon canvas already keeps the glyph in its middle 48 of 108 units,
        // so any size picked here would be fighting that margin.
        val heroShape = MaterialShapes.Cookie9Sided.toShape()
        Box(
            Modifier
                .padding(top = 24.dp)
                .size(120.dp)
                .align(Alignment.CenterHorizontally)
                .then(rise.modifier(0, scaleFrom = HERO_SCALE))
                .background(MaterialTheme.colorScheme.primaryContainer, heroShape),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 40.dp).fillMaxWidth().then(rise.modifier(1)),
        )
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth().then(rise.modifier(2)),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON).then(rise.modifier(3)),
        ) {
            Text(stringResource(R.string.welcome_next), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ChoosePage(onJoin: () -> Unit, onGenerate: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.WelcomeGutter, vertical = 32.dp)) {
        // One part of space above the text for two below it: text hung slightly above the middle is
        // the shape the eye expects to read from, and dead centre looks like it fell.
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.welcome_choose_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.welcome_choose_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Art()
        // Filled, then tonal, then text: the emphasis ladder IS the recommendation. Taking the key
        // from a device that has it is what almost everyone should do, making one is what the first
        // device does once, and typing 64 hex characters is what pairing exists to avoid.
        Button(onClick = onJoin, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = BIG_BUTTON)) {
            Text(stringResource(R.string.first_run_join), style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = onGenerate,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = BIG_BUTTON),
        ) {
            Text(stringResource(R.string.first_run_generate), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onManual, modifier = Modifier.padding(top = 4.dp).fillMaxWidth()) {
            Text(stringResource(R.string.first_run_manual))
        }
    }
}

/**
 * The illustration takes whatever height is left between the text and the buttons, so it grows on
 * a tall screen and shrinks on a short one; the padding, not a maximum, stops it becoming enormous,
 * because fitting keeps whichever dimension runs out first. art_pair and not the 24 dp ic_pair,
 * whose strokes would come out about 20 dp thick at this size.
 */
@Composable
private fun ColumnScope.Art() {
    Image(
        painter = painterResource(R.drawable.art_pair),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .weight(2f)
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    )
}

/**
 * The staggered entrance: each item rises a short way while it fades in, a sixth of the motion
 * after the one before, so the page assembles top to bottom instead of appearing at once. Springs
 * from the motion scheme, so it moves like every other part of the app.
 */
private class Rise(private val items: List<Animatable<Float, AnimationVector1D>>, private val risePx: Float) {
    fun modifier(index: Int, scaleFrom: Float = 1f): Modifier = Modifier.graphicsLayer {
        val t = items[index].value
        alpha = t
        translationY = (1f - t) * risePx
        val s = scaleFrom + (1f - scaleFrom) * t
        scaleX = s
        scaleY = s
    }
}

@Composable
private fun rememberRise(count: Int, animate: Boolean, onDone: () -> Unit): Rise {
    val items = remember { List(count) { Animatable(if (animate) 0f else 1f) } }
    val risePx = with(LocalDensity.current) { RISE.toPx() }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        items.forEachIndexed { i, a ->
            launch {
                delay(i * STAGGER_MS)
                a.animateTo(1f, spec)
            }
        }
        onDone()
    }
    return remember(items, risePx) { Rise(items, risePx) }
}

private val RISE = 32.dp

/** The expressive medium button height: the choices on this screen are the whole screen's point. */
private val BIG_BUTTON = 56.dp
private const val STAGGER_MS = 75L
private const val HERO_SCALE = 0.8f

