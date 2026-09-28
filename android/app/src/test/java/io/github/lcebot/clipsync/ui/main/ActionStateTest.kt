package io.github.lcebot.clipsync.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionStateTest {
    private val t0 = 1_000_000L

    @Test
    fun stoppedOffersStartOnly() {
        val a = ActionState().settingsActions(configValid = true)
        assertFalse(a.showStop)
        assertEquals(SettingsActions.Primary.START, a.primary)
        assertTrue(a.primaryEnabled)
    }

    @Test
    fun invalidConfigDisablesThePrimaryAction() {
        assertFalse(ActionState(running = true).settingsActions(configValid = false).primaryEnabled)
    }

    @Test
    fun startWaitsUntilTheServiceArrives() {
        var s = ActionState().onStartRequested(t0)
        assertFalse(s.settingsActions(true).primaryEnabled)
        s = s.onStatus(running = false, now = t0 + 1_000)
        assertFalse(s.settingsActions(true).primaryEnabled)
        s = s.onStatus(running = true, now = t0 + 2_000)
        val a = s.settingsActions(true)
        assertTrue(a.primaryEnabled)
        assertTrue(a.showStop)
        assertEquals(SettingsActions.Primary.APPLY, a.primary)
    }

    @Test
    fun stopWaitsUntilTheServiceIsGone() {
        var s = ActionState(running = true).onStopRequested(t0)
        assertFalse(s.settingsActions(true).stopEnabled)
        s = s.onStatus(running = false, now = t0 + 500)
        assertFalse(s.waitingForStop)
        assertFalse(s.settingsActions(true).showStop)
    }

    @Test
    fun aWaitThatNeverEndsTimesOut() {
        var s = ActionState().onStartRequested(t0)
        s = s.onStatus(running = false, now = t0 + ActionState.WAIT_TIMEOUT_MS)
        assertTrue("exactly the timeout is still waiting", s.waitingForStart)
        s = s.onStatus(running = false, now = t0 + ActionState.WAIT_TIMEOUT_MS + 1)
        assertFalse(s.waitingForStart)
        assertTrue(s.settingsActions(true).primaryEnabled)
    }

    @Test
    fun aChangeMadeElsewhereMovesTheActions() {
        val s = ActionState().onStatus(running = true, now = t0)
        assertTrue(s.settingsActions(true).showStop)
    }

    @Test
    fun keyChangeWaitsOnlyForADeadService() {
        assertTrue(ActionState().onKeyChanged(alive = false, now = t0).waitingForStart)
        assertFalse(ActionState(running = true).onKeyChanged(alive = true, now = t0).waitingForStart)
    }
}
