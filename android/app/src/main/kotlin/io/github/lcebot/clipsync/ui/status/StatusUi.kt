package io.github.lcebot.clipsync.ui.status

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.lcebot.clipsync.R
import io.github.lcebot.clipsync.Status

/**
 * What the screen shows about the service, as immutable values.
 *
 * Immutable so Compose can skip everything that did not change between two reads of the status
 * file; the Java snapshot's lists are mutable, so nothing from it crosses this boundary as-is.
 * Strings stay resource ids here so this mapping is testable without Android resources.
 */
@Immutable
data class StatusUi(
    val link: Link,
    val chip: Chip,
    /** The service process exists and has not reported "stopped". Drives the Start or Stop pair. */
    val running: Boolean,
    val battery: Battery,
    val relayCount: Int,
    val peers: List<PeerUi>,
    val indirectPeers: List<IndirectPeerUi>,
    val targets: List<TargetUi>,
) {
    enum class Link { CONNECTED, CONNECTING, IDLE }

    /**
     * The chip's label. [count] is the number of DIRECT peers, the devices this one holds an open
     * connection to; peers known only through another device's roster are listed in the sheet under
     * their own heading, where a number that included them would need explaining.
     */
    @Immutable
    data class Chip(@StringRes val label: Int, val count: Int?, val dimmed: Boolean, val clickable: Boolean)

    enum class Battery {
        HIDDEN,
        /** Not exempt from battery optimisation: offer the fix. */
        OPTIMISED,
        /** Exempt, yet the service reports it was frozen anyway: explain, there is nothing to press. */
        STILL_FROZEN,
    }

    companion object {
        val Initial = StatusUi(
            link = Link.IDLE,
            chip = Chip(R.string.state_stopped, null, dimmed = true, clickable = false),
            running = false,
            battery = Battery.HIDDEN,
            relayCount = 0,
            peers = emptyList(),
            indirectPeers = emptyList(),
            targets = emptyList(),
        )
    }
}

@Immutable
data class PeerUi(val id: String?, val name: String?, val type: String?, val address: String?, val lan: Boolean)

@Immutable
data class IndirectPeerUi(val id: String?, val name: String?, val type: String?, val via: String?)

@Immutable
data class TargetUi(val target: String?, val reason: String?, val why: Status.Why)

/**
 * The rules from one status snapshot to the screen.
 *
 * [alive] and [exemptFromBatteryOptimisation] are passed in, not read here, so the rules run in a
 * plain JVM test: liveness is a clock comparison and the exemption is a system service call.
 */
object StatusRules {
    fun toUi(s: Status.Snapshot, alive: Boolean, exemptFromBatteryOptimisation: Boolean): StatusUi {
        // A process that is gone without writing "stopped" has been killed. The watchdog restarts
        // it, and until then the screen says it is not running and lists nothing, rather than
        // showing the last peers a dead process wrote.
        val killed = !alive && s.state != STOPPED
        val state = if (killed) STOPPED else s.state
        val connected = state == "connected" || state == "relay"
        val connecting = state == "connecting"
        val stopped = state == STOPPED

        val label = when (state) {
            "relay" -> R.string.state_relay
            "connected" -> R.string.state_connected
            "connecting" -> R.string.state_connecting
            "no network" -> R.string.state_no_network
            "idle" -> R.string.state_idle
            else -> R.string.state_stopped
        }
        val peers = if (killed) emptyList() else s.peers.map { PeerUi(it.id, it.name, it.type, it.addr, it.lan) }
        val indirect = if (killed) emptyList() else s.indirectPeers.map { IndirectPeerUi(it.id, it.name, it.type, it.via) }
        val targets = if (killed) emptyList() else s.targets.map { TargetUi(it.target, it.reason, it.why) }

        val battery = when {
            exemptFromBatteryOptimisation && !s.suspended -> StatusUi.Battery.HIDDEN
            exemptFromBatteryOptimisation -> StatusUi.Battery.STILL_FROZEN
            else -> StatusUi.Battery.OPTIMISED
        }
        return StatusUi(
            link = when {
                connected -> StatusUi.Link.CONNECTED
                connecting -> StatusUi.Link.CONNECTING
                else -> StatusUi.Link.IDLE
            },
            chip = StatusUi.Chip(
                label = label,
                count = if (connected) s.count() else null,
                dimmed = stopped,
                // Tappable whenever there is anything to list, including "nothing is connected and
                // here is why", the case the sheet is most worth opening for.
                clickable = peers.isNotEmpty() || indirect.isNotEmpty() || targets.isNotEmpty(),
            ),
            running = !stopped,
            battery = battery,
            relayCount = if (killed) 0 else s.relayCount,
            peers = peers,
            indirectPeers = indirect,
            targets = targets,
        )
    }

    private const val STOPPED = "stopped"
}
