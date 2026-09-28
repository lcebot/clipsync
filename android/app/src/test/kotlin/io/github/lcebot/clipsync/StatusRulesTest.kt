package io.github.lcebot.clipsync

import io.github.lcebot.clipsync.ui.status.StatusRules
import io.github.lcebot.clipsync.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In this package because Status.Snapshot's constructor is package-private. */
class StatusRulesTest {
    private fun snapshot(
        state: String,
        peers: List<Status.Peer> = emptyList(),
        targets: List<Status.Target> = emptyList(),
        suspended: Boolean = false,
    ) = Status.Snapshot(state, null, 1L, suspended, peers, emptyList(), targets, 0)

    private val lanPeer = Status.peer("id1", "pc", "windows", null, "10.0.0.2", true)

    @Test
    fun connectedCountsDirectPeers() {
        val ui = StatusRules.toUi(snapshot("connected", peers = listOf(lanPeer)), alive = true, exemptFromBatteryOptimisation = true)
        assertEquals(StatusUi.Link.CONNECTED, ui.link)
        assertEquals(R.string.state_connected, ui.chip.label)
        assertEquals(1, ui.chip.count)
        assertTrue(ui.chip.clickable)
        assertTrue(ui.running)
    }

    @Test
    fun aKilledServiceReadsAsStoppedAndListsNothing() {
        val ui = StatusRules.toUi(snapshot("connected", peers = listOf(lanPeer)), alive = false, exemptFromBatteryOptimisation = true)
        assertFalse(ui.running)
        assertEquals(R.string.state_stopped, ui.chip.label)
        assertTrue(ui.chip.dimmed)
        assertTrue(ui.peers.isEmpty())
        assertFalse(ui.chip.clickable)
    }

    @Test
    fun connectingHasNoCount() {
        val ui = StatusRules.toUi(snapshot("connecting"), alive = true, exemptFromBatteryOptimisation = true)
        assertEquals(StatusUi.Link.CONNECTING, ui.link)
        assertNull(ui.chip.count)
    }

    @Test
    fun aReasonIsWorthOpeningTheSheetFor() {
        val t = Status.target("pc.local", "unreachable", Status.Why.FAULT)
        val ui = StatusRules.toUi(snapshot("idle", targets = listOf(t)), alive = true, exemptFromBatteryOptimisation = true)
        assertTrue(ui.chip.clickable)
    }

    @Test
    fun batteryCard() {
        fun battery(exempt: Boolean, suspended: Boolean) =
            StatusRules.toUi(snapshot("idle", suspended = suspended), alive = true, exemptFromBatteryOptimisation = exempt).battery
        assertEquals(StatusUi.Battery.HIDDEN, battery(exempt = true, suspended = false))
        assertEquals(StatusUi.Battery.STILL_FROZEN, battery(exempt = true, suspended = true))
        assertEquals(StatusUi.Battery.OPTIMISED, battery(exempt = false, suspended = false))
    }
}
