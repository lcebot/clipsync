package io.github.lcebot.clipsync.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import io.github.lcebot.clipsync.R

/**
 * The accent the notification shade tints the small icon with.
 *
 * Read straight from the platform palette rather than from a theme, because the notification is
 * built in `:sync` from a Service context, which never passes through an Activity theme, so a
 * themed read would give the brand fallback even on a device whose whole UI follows the wallpaper.
 * The roles are the same ones Compose's dynamic scheme uses for primary on API 34+, so the shade and
 * the app agree.
 */
object NotificationColor {
    @JvmStatic
    @ColorInt
    fun of(context: Context): Int {
        if (!DynamicPalette.available()) return context.getColor(R.color.brand_primary)
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return context.getColor(if (night) android.R.color.system_primary_dark else android.R.color.system_primary_light)
    }
}
