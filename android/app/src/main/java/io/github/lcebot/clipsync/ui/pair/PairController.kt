package io.github.lcebot.clipsync.ui.pair

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.lcebot.clipsync.Config
import io.github.lcebot.clipsync.Crypto
import io.github.lcebot.clipsync.Logger
import io.github.lcebot.clipsync.Mdns
import io.github.lcebot.clipsync.PairJoiner
import io.github.lcebot.clipsync.PairProvider
import io.github.lcebot.clipsync.Pairing
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.SyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Properties

/**
 * What the pairing sheet shows, one state at a time. Pairing is the same conversation seen from
 * two sides: the provider holds the key and shows a code, the joiner finds it and types the code.
 */
@Immutable
sealed interface PairUi {
    /** Which side of the conversation this device is on; it decides the title and the body. */
    val offering: Boolean

    /** Deriving the channel key, binding and advertising. The code cannot exist yet. */
    data object Opening : PairUi {
        override val offering = true
    }

    /**
     * The window is open. [given] grows by one line per device that took the key; the window does
     * not close on the first one, since setting up several devices is the ordinary case.
     */
    data class Offering(val code: String, val closesAt: Long, val given: List<String>) : PairUi {
        override val offering = true
        override fun toString() = "Offering(given=${given.size})"
    }

    data object Browsing : PairUi {
        override val offering = false
    }

    data object NoneFound : PairUi {
        override val offering = false
    }

    data class Picking(val devices: List<Mdns.Instance>) : PairUi {
        override val offering = false
    }

    data class EnteringCode(val device: Mdns.Instance, val error: String?) : PairUi {
        override val offering = false
    }

    /**
     * The typed code is being turned into a channel key ([connecting] false), then used on the
     * network ([connecting] true). Two phases because the derivation is a deliberately slow scrypt
     * and no socket opens until it is done: "Connecting" over a derivation would describe something
     * that has not started, at the moment the user is watching for a sign their typing did anything.
     */
    data class Checking(val device: Mdns.Instance, val connecting: Boolean) : PairUi {
        override val offering = false
    }

    data class Finished(override val offering: Boolean, val message: String) : PairUi
}

/** A caller proved it knows the code and now waits for a person to say yes. */
@Immutable
class PairAsk internal constructor(val device: String, val type: String, internal val answer: CompletableDeferred<Boolean>)

/**
 * One pairing session, either side, with no views.
 *
 * SECURITY: an open offering window hands the PSK to anyone who knows the code. So the window
 * never outlives what the user can see: [close] shuts the provider, answers any pending question
 * with no, and makes every late callback a no-op; and a provider that finishes opening after [close]
 * is shut the moment it exists rather than left advertising. The owner calls [close] when the sheet
 * goes away for any reason.
 *
 * Callbacks from the provider arrive on its own thread; every piece of state here is a StateFlow or
 * guarded by [lock], so no thread needs to be the main one.
 */
class PairController(
    context: Context,
    private val scope: CoroutineScope,
    private val events: Events,
) {
    interface Events {
        /** A new key is in the config file. Called before the service is started or reloaded on it. */
        fun keyChanged()
    }

    private val app = context.applicationContext
    private val lock = Any()
    private var closed = false
    private var provider: PairProvider? = null
    @Volatile
    private var job: Job? = null

    private val _ui = MutableStateFlow<PairUi>(PairUi.Opening)
    val ui: StateFlow<PairUi> = _ui.asStateFlow()

    private val _ask = MutableStateFlow<PairAsk?>(null)
    val ask: StateFlow<PairAsk?> = _ask.asStateFlow()

    // ------------------------------------------------------------------ offering

    /** Offers this device's key. A device without one has nothing to offer and is told so. */
    fun offer() {
        val p = Config.raw(app)
        val psk = p.getProperty("psk", "")
        if (Config.checkPsk(psk) != null) {
            finish(offering = true, message = app.getString(R.string.pair_no_key))
            return
        }
        startOffering(psk, p.getProperty("port", "").trim().toIntOrNull() ?: 0)
    }

    /**
     * The first device: make a key, save it with discovery on (pairing is mDNS at both ends), start
     * the service on it, then offer it like any other device would.
     */
    fun generateAndOffer() {
        set(PairUi.Opening)
        launchIo {
            try {
                val v = Properties()
                Config.freshKey(v, Crypto.randomPskHex())
                v.setProperty("discovery", "true")
                Config.save(app, v)
            } catch (e: Exception) {
                Logger.w("pairing: cannot generate a key: $e")
                finish(offering = true, message = app.getString(R.string.pair_cannot_generate))
                return@launchIo
            }
            events.keyChanged()
            SyncService.startOrReload(app)
            if (!isClosed()) offer()
        }
    }

    private fun startOffering(pskHex: String, port: Int) {
        set(PairUi.Opening)
        launchIo {
            val opened = try {
                PairProvider(app, pskHex, port, providerListener)
            } catch (e: Exception) {
                Logger.w("pairing: cannot open a window: $e")
                finish(offering = true, message = app.getString(R.string.pair_cannot_open, e.message ?: e.javaClass.simpleName))
                return@launchIo
            }
            val adopted = synchronized(lock) {
                if (closed) false else {
                    provider = opened
                    true
                }
            }
            if (!adopted) {
                // The sheet went away while the window was opening: nobody is looking at a code, so
                // nobody may be handed a key through it.
                opened.close()
                return@launchIo
            }
            set(PairUi.Offering(opened.code, opened.closesAt, emptyList()))
        }
    }

    // Parameters nullable on purpose: they are the peer's own claims, straight off the network via
    // Java, and a non-null Kotlin parameter would turn a missing name into an exception on the
    // provider's thread instead of a "?" on screen.
    private val providerListener = object : PairProvider.Listener {
        override fun onPaired(device: String?, type: String?) {
            val name = device ?: "?"
            update { ui -> if (ui is PairUi.Offering) ui.copy(given = ui.given + name) else ui }
        }

        override fun onAskUser(device: String?, type: String?): Boolean = askUser(device ?: "?", type.orEmpty())

        override fun onClosed(burned: Boolean) {
            val given = (_ui.value as? PairUi.Offering)?.given.orEmpty()
            synchronized(lock) { provider = null }
            finish(
                offering = true,
                message = app.getString(
                    when {
                        burned -> R.string.pair_burned
                        given.isEmpty() -> R.string.pair_expired
                        else -> R.string.pair_offer_over
                    },
                ),
            )
        }
    }

    /**
     * Blocks the provider's thread until the user answers, which is what the provider expects: it
     * serves one caller at a time. No answer within [PairProvider.ASK_TIMEOUT_MS] is a no, and the
     * question leaves the screen with it, so a dialog can never outlive the caller waiting on it.
     */
    private fun askUser(device: String, type: String): Boolean {
        val ask = PairAsk(device, type.ifEmpty { "?" }, CompletableDeferred())
        synchronized(lock) {
            if (closed) return false
            _ask.value = ask
        }
        val yes = runBlocking { withTimeoutOrNull(PairProvider.ASK_TIMEOUT_MS) { ask.answer.await() } } ?: false
        _ask.compareAndSet(ask, null)
        return yes
    }

    /** The user's answer to the question on screen. Dismissing the dialog is a no. */
    fun answer(ask: PairAsk, yes: Boolean) {
        ask.answer.complete(yes)
        _ask.compareAndSet(ask, null)
    }

    // ------------------------------------------------------------------ joining

    fun join() {
        browse()
    }

    fun browse() {
        set(PairUi.Browsing)
        launchIo {
            val found = PairJoiner.find(app, BROWSE_MS)
            set(if (found.isEmpty()) PairUi.NoneFound else PairUi.Picking(found))
        }
    }

    fun pick(device: Mdns.Instance) {
        set(PairUi.EnteringCode(device, error = null))
    }

    /**
     * Joins with the typed code. Non-digits are stripped here and not trusted to the field's own
     * filter: a key is not derived from what most keyboards happen to do. Anything but exactly
     * [Pairing.CODE_DIGITS] digits is refused before any work starts.
     */
    fun connect(device: Mdns.Instance, typed: String) {
        val code = Pairing.digitsOnly(typed)
        if (code.length != Pairing.CODE_DIGITS) {
            set(PairUi.EnteringCode(device, app.getString(R.string.pair_code_length)))
            return
        }
        set(PairUi.Checking(device, connecting = false))
        launchIo {
            try {
                val r = PairJoiner.join(app, device, code) { set(PairUi.Checking(device, connecting = true)) }
                PairJoiner.apply(app, r)
                // Past this line the key is in the file, so the service and the form hear about it
                // even if the sheet has gone: otherwise the next Apply would save the old key back
                // over the one just paired.
                events.keyChanged()
                SyncService.startOrReload(app)
                finish(offering = false, message = app.getString(R.string.pair_join_done, r.device ?: "?"))
            } catch (e: Exception) {
                Logger.i("pairing: $e")
                set(PairUi.EnteringCode(device, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ------------------------------------------------------------------ lifetime

    /** Ends the session. Idempotent, and safe from any thread. */
    fun close() {
        val p: PairProvider?
        synchronized(lock) {
            if (closed) return
            closed = true
            p = provider
            provider = null
        }
        job?.cancel()
        _ask.value?.answer?.complete(false)
        _ask.value = null
        p?.close()
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun finish(offering: Boolean, message: String) {
        set(PairUi.Finished(offering, message))
    }

    private fun set(ui: PairUi) {
        if (!isClosed()) _ui.value = ui
    }

    private fun update(f: (PairUi) -> PairUi) {
        if (!isClosed()) _ui.value = f(_ui.value)
    }

    private fun launchIo(block: suspend CoroutineScope.() -> Unit) {
        job = scope.launch(Dispatchers.IO, block = block)
    }

    private companion object {
        const val BROWSE_MS = 4_000L
    }
}
