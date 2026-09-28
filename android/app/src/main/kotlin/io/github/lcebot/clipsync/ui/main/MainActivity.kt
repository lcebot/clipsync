package io.github.lcebot.clipsync.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.clipsync.Logger
import io.github.lcebot.clipsync.ui.pair.PairEvent
import io.github.lcebot.clipsync.ui.pair.PairSheetHost
import io.github.lcebot.clipsync.ui.pair.PairViewModel
import io.github.lcebot.clipsync.ui.theme.ClipSyncTheme
import io.github.lcebot.clipsync.ui.welcome.WelcomeActivity
import kotlinx.coroutines.launch

/**
 * The launcher activity. It does only what an Activity alone can do: host the screen, launch the
 * welcome screen, reach system settings and the clipboard. State lives in [MainViewModel] and
 * [PairViewModel], so a rotation changes nothing the user can see.
 */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val pairing: PairViewModel by viewModels()

    /**
     * Whatever happened on the welcome screen, the key may have changed, because pairing runs
     * there; so this is a refresh, not a dispatch on the result. Registered as a field because
     * registration must happen before the activity is started.
     */
    private val welcome = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.data?.getBooleanExtra(WelcomeActivity.EXTRA_KEY_CHANGED, false) == true) vm.onKeyChanged()
        else vm.reloadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Logger.init(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pairing.events.collect { if (it == PairEvent.KEY_CHANGED) vm.onKeyChanged() }
            }
        }

        setContent {
            ClipSyncTheme {
                MainScreen(
                    vm = vm,
                    onPair = {
                        vm.prepareToPair()
                        pairing.offer()
                    },
                    onSetup = ::openWelcome,
                    onBatteryFix = ::openBatterySettings,
                    onCopy = { text, message ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("clipsync", text))
                        vm.say(UiMessage(message))
                    },
                )
                PairSheetHost(pairing)
            }
        }

        // Only on a cold start: a recreate runs onCreate again while the welcome screen may already
        // be on top, and offering again would stack a second copy of it.
        if (savedInstanceState == null && vm.needsFirstRun()) openWelcome()
    }

    private fun openWelcome() {
        welcome.launch(Intent(this, WelcomeActivity::class.java))
    }

    /**
     * The system's own exemption dialog when it can be shown, the full list otherwise: some ROMs
     * remove the direct dialog, and a button that does nothing is worse than one more tap.
     */
    private fun openBatterySettings() {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        try {
            startActivity(ask)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Logger.w("no battery optimisation settings on this ROM", e2)
            }
        }
    }
}
