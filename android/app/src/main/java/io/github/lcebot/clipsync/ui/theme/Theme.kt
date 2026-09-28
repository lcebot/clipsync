@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.clipsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The one theme every screen and sheet is drawn under.
 *
 * The colour scheme is passed explicitly because MaterialExpressiveTheme's own default is a light
 * scheme only. Typography and shapes are Material's expressive defaults: nothing in this app has a
 * reason to differ from them, and a partial override would be one more thing to keep in step with
 * the library.
 */
@Composable
fun ClipSyncTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Keyed on the configuration's context and the appearance, because a wallpaper change
    // recreates the activity and with it this context; nothing else changes the palette.
    val scheme = remember(context, dark) { clipSyncColorScheme(context, dark) }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
