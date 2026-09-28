package io.github.lcebot.clipsync.ui.welcome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.clipsync.ui.pair.PairEvent
import io.github.lcebot.clipsync.ui.pair.PairSheetHost
import io.github.lcebot.clipsync.ui.pair.PairViewModel
import io.github.lcebot.clipsync.ui.theme.ClipSyncTheme
import io.github.lcebot.clipsync.ui.theme.drawEdgeToEdge
import kotlinx.coroutines.launch

/**
 * The first-run choice for a device with no key: take one from a device that has it, make one here,
 * or set everything up by hand.
 *
 * Pairing runs on this screen rather than handing the job back to Settings, so the screen closes
 * itself once a session that changed the key is over; the settings page re-reads the file whatever
 * this returns. [EXTRA_KEY_CHANGED] says pairing wrote a key; [MANUAL] needs nothing done, because
 * the page behind this one IS the manual setup.
 */
class WelcomeActivity : ComponentActivity() {
    private val pairing: PairViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pairing.events.collect { e ->
                    if (e == PairEvent.CLOSED && pairing.keyChanged) {
                        // The settings page behind treats this as pairing: auto-start back on, and
                        // its actions wait for the service to come up.
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_KEY_CHANGED, true))
                        finish()
                    }
                }
            }
        }

        setContent {
            ClipSyncTheme {
                WelcomeScreen(
                    onJoin = pairing::join,
                    onGenerate = pairing::generateAndOffer,
                    onManual = {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CHOICE, MANUAL))
                        finish()
                    },
                )
                PairSheetHost(pairing)
            }
        }
    }

    companion object {
        const val EXTRA_CHOICE = "choice"
        const val MANUAL = "manual"
        const val EXTRA_KEY_CHANGED = "key_changed"
    }
}
