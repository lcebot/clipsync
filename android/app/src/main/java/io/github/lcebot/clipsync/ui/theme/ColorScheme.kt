package io.github.lcebot.clipsync.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.lcebot.clipsync.R

/**
 * Whether the platform hands this app a wallpaper-derived palette.
 *
 * One function, so the Compose theme and the notification in `:sync` always make the same choice:
 * an accent that differs between the shade and the app reads as a bug.
 */
object DynamicPalette {
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/**
 * The colour scheme for one appearance.
 *
 * The wallpaper palette decides what this app's accent looks like, but not what an error looks
 * like: red separates "a target you need to fix" from "a target that is deferring to a better
 * route", and a wallpaper that turned the error container green would draw that distinction in a
 * colour that means the opposite. So the error family is always the brand's, on top of whichever
 * palette won. Tertiary, the "asleep" and "connecting" accent, is an accent doing an accent's job and
 * follows the wallpaper with everything else.
 */
fun clipSyncColorScheme(context: Context, dark: Boolean): ColorScheme {
    val brand = BrandPalette(context)
    val base = when {
        DynamicPalette.available() && dark -> dynamicDarkColorScheme(context)
        DynamicPalette.available() -> dynamicLightColorScheme(context)
        else -> brand.scheme(dark)
    }
    val own = if (dark) brand.dark else brand.light
    return base.copy(
        error = own.color(R.color.brand_error),
        onError = own.color(R.color.brand_on_error),
        errorContainer = own.color(R.color.brand_error_container),
        onErrorContainer = own.color(R.color.brand_on_error_container),
    )
}

/**
 * ClipSync's own palette, read from `res/values/colors.xml` and `res/values-night/colors.xml`.
 *
 * Read from resources rather than restated here so the palette has one source: the launcher
 * background and the notification fallback read the same file.
 *
 * Both appearances are resolved through their own configuration, whatever the device is showing,
 * because the "fixed" roles of each scheme are built from tones of the other one (below), and
 * because a preview may ask for the appearance the device is not in.
 */
internal class BrandPalette(context: Context) {
    val light = Tones(context.withNight(false))
    val dark = Tones(context.withNight(true))

    class Tones(private val context: Context) {
        fun color(@ColorRes id: Int): Color = Color(context.getColor(id))
    }

    /**
     * Every role bound, not a chosen few: components pick their own roles, and a partial scheme
     * shows Material's baseline through wherever the list stops.
     *
     * The fixed roles keep the same tone in both appearances. The M3 tone assignments give them
     * directly from roles this palette already has: fixed is tone 90 (the light container), fixed
     * dim is tone 80 (the dark main colour), on-fixed is tone 10 (the light on-container), and
     * on-fixed-variant is tone 30 (the dark container).
     */
    fun scheme(isDark: Boolean): ColorScheme {
        val t = if (isDark) dark else light
        val primaryFixed = light.color(R.color.brand_primary_container)
        val primaryFixedDim = dark.color(R.color.brand_primary)
        val onPrimaryFixed = light.color(R.color.brand_on_primary_container)
        val onPrimaryFixedVariant = dark.color(R.color.brand_primary_container)
        val secondaryFixed = light.color(R.color.brand_secondary_container)
        val secondaryFixedDim = dark.color(R.color.brand_secondary)
        val onSecondaryFixed = light.color(R.color.brand_on_secondary_container)
        val onSecondaryFixedVariant = dark.color(R.color.brand_secondary_container)
        val tertiaryFixed = light.color(R.color.brand_tertiary_container)
        val tertiaryFixedDim = dark.color(R.color.brand_tertiary)
        val onTertiaryFixed = light.color(R.color.brand_on_tertiary_container)
        val onTertiaryFixedVariant = dark.color(R.color.brand_tertiary_container)

        val primary = t.color(R.color.brand_primary)
        return if (isDark) darkColorScheme(
            primary = primary,
            onPrimary = t.color(R.color.brand_on_primary),
            primaryContainer = t.color(R.color.brand_primary_container),
            onPrimaryContainer = t.color(R.color.brand_on_primary_container),
            inversePrimary = t.color(R.color.brand_inverse_primary),
            secondary = t.color(R.color.brand_secondary),
            onSecondary = t.color(R.color.brand_on_secondary),
            secondaryContainer = t.color(R.color.brand_secondary_container),
            onSecondaryContainer = t.color(R.color.brand_on_secondary_container),
            tertiary = t.color(R.color.brand_tertiary),
            onTertiary = t.color(R.color.brand_on_tertiary),
            tertiaryContainer = t.color(R.color.brand_tertiary_container),
            onTertiaryContainer = t.color(R.color.brand_on_tertiary_container),
            background = t.color(R.color.brand_surface),
            onBackground = t.color(R.color.brand_on_surface),
            surface = t.color(R.color.brand_surface),
            onSurface = t.color(R.color.brand_on_surface),
            surfaceVariant = t.color(R.color.brand_surface_variant),
            onSurfaceVariant = t.color(R.color.brand_on_surface_variant),
            surfaceTint = primary,
            inverseSurface = t.color(R.color.brand_inverse_surface),
            inverseOnSurface = t.color(R.color.brand_inverse_on_surface),
            error = t.color(R.color.brand_error),
            onError = t.color(R.color.brand_on_error),
            errorContainer = t.color(R.color.brand_error_container),
            onErrorContainer = t.color(R.color.brand_on_error_container),
            outline = t.color(R.color.brand_outline),
            outlineVariant = t.color(R.color.brand_outline_variant),
            surfaceBright = t.color(R.color.brand_surface_bright),
            surfaceDim = t.color(R.color.brand_surface_dim),
            surfaceContainer = t.color(R.color.brand_surface_container),
            surfaceContainerHigh = t.color(R.color.brand_surface_container_high),
            surfaceContainerHighest = t.color(R.color.brand_surface_container_highest),
            surfaceContainerLow = t.color(R.color.brand_surface_container_low),
            surfaceContainerLowest = t.color(R.color.brand_surface_container_lowest),
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant,
        ) else lightColorScheme(
            primary = primary,
            onPrimary = t.color(R.color.brand_on_primary),
            primaryContainer = t.color(R.color.brand_primary_container),
            onPrimaryContainer = t.color(R.color.brand_on_primary_container),
            inversePrimary = t.color(R.color.brand_inverse_primary),
            secondary = t.color(R.color.brand_secondary),
            onSecondary = t.color(R.color.brand_on_secondary),
            secondaryContainer = t.color(R.color.brand_secondary_container),
            onSecondaryContainer = t.color(R.color.brand_on_secondary_container),
            tertiary = t.color(R.color.brand_tertiary),
            onTertiary = t.color(R.color.brand_on_tertiary),
            tertiaryContainer = t.color(R.color.brand_tertiary_container),
            onTertiaryContainer = t.color(R.color.brand_on_tertiary_container),
            background = t.color(R.color.brand_surface),
            onBackground = t.color(R.color.brand_on_surface),
            surface = t.color(R.color.brand_surface),
            onSurface = t.color(R.color.brand_on_surface),
            surfaceVariant = t.color(R.color.brand_surface_variant),
            onSurfaceVariant = t.color(R.color.brand_on_surface_variant),
            surfaceTint = primary,
            inverseSurface = t.color(R.color.brand_inverse_surface),
            inverseOnSurface = t.color(R.color.brand_inverse_on_surface),
            error = t.color(R.color.brand_error),
            onError = t.color(R.color.brand_on_error),
            errorContainer = t.color(R.color.brand_error_container),
            onErrorContainer = t.color(R.color.brand_on_error_container),
            outline = t.color(R.color.brand_outline),
            outlineVariant = t.color(R.color.brand_outline_variant),
            surfaceBright = t.color(R.color.brand_surface_bright),
            surfaceDim = t.color(R.color.brand_surface_dim),
            surfaceContainer = t.color(R.color.brand_surface_container),
            surfaceContainerHigh = t.color(R.color.brand_surface_container_high),
            surfaceContainerHighest = t.color(R.color.brand_surface_container_highest),
            surfaceContainerLow = t.color(R.color.brand_surface_container_low),
            surfaceContainerLowest = t.color(R.color.brand_surface_container_lowest),
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant,
        )
    }
}

private fun Context.withNight(night: Boolean): Context {
    val config = Configuration(resources.configuration)
    val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
    return createConfigurationContext(config)
}
