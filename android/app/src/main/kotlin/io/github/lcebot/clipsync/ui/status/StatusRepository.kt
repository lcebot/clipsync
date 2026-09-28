package io.github.lcebot.clipsync.ui.status

import android.content.Context
import android.os.PowerManager
import io.github.lcebot.clipsync.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The service's status as a stream of screen states.
 *
 * Two sources, and neither is enough alone:
 *
 * - The file watch is the normal path, and it is debounced because one event on the service side
 *   (a network change, a reload) makes several threads rewrite the file within milliseconds;
 *   rendering each would start an animation and cancel it with the next.
 * - The poll is the backstop, and it stays however good the watch is, because two things the screen
 *   decides are timeouts: liveness ([Status.Snapshot.alive]), which is how a killed service is
 *   noticed, and the action wait in the view model, which hands the buttons back when a start never
 *   arrives. A timeout has no event, so something has to look. Every 5 s is fine against a 120 s
 *   liveness window and a 12 s wait.
 *
 * Every poll emits, even when nothing changed, for the same reason: the consumer's clock has to
 * advance. Deduplication for rendering belongs to the StateFlow the view model exposes.
 *
 * Collection is the lifecycle: the watch starts when collection starts and stops when it stops, so a
 * collector tied to the screen's STARTED state watches exactly while the screen can show it.
 */
class StatusRepository(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)

    @OptIn(FlowPreview::class)
    fun updates(): Flow<StatusUi> =
        merge(fileChanges().debounce(DEBOUNCE_MS), ticks())
            .map { read() }
            .flowOn(Dispatchers.IO)

    /** One read and one mapping, for callers that need an answer now rather than a stream. */
    fun read(): StatusUi {
        val snapshot = Status.read(app)
        val exempt = power?.isIgnoringBatteryOptimizations(app.packageName) == true
        return StatusRules.toUi(snapshot, snapshot.alive(), exempt)
    }

    private fun fileChanges(): Flow<Unit> = callbackFlow {
        // Held by this scope until awaitClose: an unreferenced FileObserver is collected and stops
        // delivering without any error.
        val watch = Status.watch(app) { trySend(Unit) }
        watch.startWatching()
        awaitClose { watch.stopWatching() }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(POLL_MS)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 60L
        const val POLL_MS = 5_000L
    }
}
