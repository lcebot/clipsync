package io.github.lcebot.clipsync.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat

/**
 * Draws the window behind the system bars, and gives the bars' icons the contrast the app's own
 * surface needs: dark icons on the light scheme, light icons on the dark one.
 *
 * The icon appearance is set here because enabling edge-to-edge alone leaves it at the platform
 * default, which follows the system theme rather than what is drawn under the bars. Screens always
 * follow the system appearance, so the configuration's night mode is the right question.
 */
fun Activity.drawEdgeToEdge() {
    WindowCompat.enableEdgeToEdge(window)
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}
