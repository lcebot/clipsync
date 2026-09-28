package io.github.lcebot.clipsync.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lcebot.clipsync.ui.theme.Dimens

/**
 * The small header above each block of settings.
 *
 * A heading, and said so: without it a screen reader walks every field of a section to reach the
 * next one; with it the sections are navigable the way they look.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.SpacingSection, bottom = Dimens.SpacingHeading)
            .semantics { heading() },
    )
}

/**
 * The connection dot. It pulses only while connected, so a steady dot is itself information: the
 * link is being worked on, or there is none.
 */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val pulse = rememberInfiniteTransition(label = "status dot")
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "status dot alpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier
            .size(DOT_SIZE)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

private val DOT_SIZE = 10.dp

// A slow breath, not a blink: the dot sits in the top bar the whole time the app is open.
private const val PULSE_MS = 1400
private const val PULSE_LOW = 60f / 255f

/**
 * M3's transition between navigation bar destinations: the outgoing page fades, the incoming one
 * fades in while settling from a slight scale. No lateral motion, which the spec reserves for peers
 * in a sequence.
 *
 * Specs are passed in because the motion scheme is only readable inside composition, and the
 * transition lambda runs outside it.
 */
fun fadeThrough(effects: FiniteAnimationSpec<Float>, spatial: FiniteAnimationSpec<Float>): ContentTransform =
    (fadeIn(effects) + scaleIn(spatial, initialScale = FADE_THROUGH_SCALE)) togetherWith fadeOut(effects)

private const val FADE_THROUGH_SCALE = 0.92f

/**
 * M3's shared X axis, for steps in a sequence such as the welcome pages. [forward] decides which
 * way the pages travel; layout direction is applied by the slide itself.
 */
fun sharedAxisX(
    forward: Boolean,
    effects: FiniteAnimationSpec<Float>,
    spatial: FiniteAnimationSpec<IntOffset>,
): ContentTransform {
    val sign = if (forward) 1 else -1
    val enter = slideInHorizontally(spatial) { width -> sign * width / SHARED_AXIS_TRAVEL } + fadeIn(effects)
    val exit = slideOutHorizontally(spatial) { width -> -sign * width / SHARED_AXIS_TRAVEL } + fadeOut(effects)
    return enter togetherWith exit
}

// A fraction of the width, not all of it: shared axis moves content a short way and lets the fade
// do the rest, so the eye follows the direction without watching a whole page slide past.
private const val SHARED_AXIS_TRAVEL = 5

/** Convenience for `AnimatedContent(transitionSpec = ...)` call sites. */
typealias TransitionSpec<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform
