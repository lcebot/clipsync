package io.github.lcebot.clipsync.ui.settings

import io.github.lcebot.clipsync.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class SettingsRulesTest {
    /**
     * Config's own peer and path checks call Android, which a JVM test cannot. These stand in with
     * the shape of the real answers: null when fine, a sentence when not.
     */
    private val checks = object : SettingsChecks {
        override fun peer(s: String): String? = if (s.contains(" ")) "not a valid host name" else null
        override fun port(s: String): String? = Config.checkPort(s)
        override fun psk(s: String): String? = Config.checkPsk(s)
        override fun path(s: String): String? = if (s.isBlank()) "required" else null
        override fun range(s: String, min: Long, max: Long, unit: String): String? = Config.checkRange(s, min, max, unit)
    }

    private val psk = "ab".repeat(32)

    private fun valid() = SettingsRules.load(Properties().apply { setProperty("psk", psk) })

    @Test
    fun defaultsLoadAsTheFormShowsThem() {
        val v = SettingsRules.load(Properties())
        assertTrue(v.discovery)
        assertFalse(v.direct)
        assertEquals("47521", v.port)
        assertEquals("1024", v.textKb)
        assertEquals("10", v.fileMb)
        assertEquals("100", v.fileMbLocal)
        assertEquals("2", v.keepHours)
        assertEquals("256", v.keepMb)
        assertEquals(8, SettingsRules.snap(Config.THREAD_STEPS, v.threadsIndex))
        assertEquals(4000, SettingsRules.snap(Config.BROWSE_STEPS_MS, v.browseIndex))
    }

    @Test
    fun unevenValuesSnapToTheNearestStep() {
        val v = SettingsRules.load(Properties().apply {
            setProperty("threads", "7")
            setProperty("mdns_timeout_ms", "5100")
        })
        assertEquals(8, SettingsRules.snap(Config.THREAD_STEPS, v.threadsIndex))
        assertEquals(6000, SettingsRules.snap(Config.BROWSE_STEPS_MS, v.browseIndex))
    }

    @Test
    fun snapClampsOutOfRangeIndices() {
        assertEquals(1, SettingsRules.snap(Config.THREAD_STEPS, -3))
        assertEquals(16, SettingsRules.snap(Config.THREAD_STEPS, 99))
    }

    @Test
    fun unitsRoundTrip() {
        val stored = Properties().apply {
            setProperty("psk", psk)
            setProperty("max_bytes", "2097152")
            setProperty("max_file_bytes", "5242880")
        }
        val p = SettingsRules.toProperties(SettingsRules.load(stored), psk, now = 42)
        assertEquals("2097152", p.getProperty("max_bytes"))
        assertEquals("5242880", p.getProperty("max_file_bytes"))
    }

    @Test
    fun aNonNumberIsPassedThroughForTheCheckToName() {
        val p = SettingsRules.toProperties(valid().copy(textKb = "lots"), psk, now = 42)
        assertEquals("lots", p.getProperty("max_bytes"))
    }

    @Test
    fun anUnchangedKeyKeepsItsAge() {
        val p = SettingsRules.toProperties(valid(), psk.uppercase(), now = 42)
        assertNull(p.getProperty("psk_since"))
    }

    @Test
    fun aNewKeyStartsItsClockNow() {
        val p = SettingsRules.toProperties(valid().copy(psk = "cd".repeat(32)), psk, now = 42)
        assertEquals("42", p.getProperty("psk_since"))
        assertEquals("", p.getProperty("psk_next"))
        assertEquals("0", p.getProperty("psk_agreed"))
    }

    @Test
    fun blankRowsAreNotStored() {
        val p = SettingsRules.toProperties(valid().copy(peers = listOf(" a.example ", "", "b.example")), psk, now = 42)
        assertEquals("a.example,b.example", p.getProperty("peers"))
    }

    @Test
    fun theDefaultConfigWithAKeyIsValid() {
        assertTrue(SettingsRules.validate(valid(), checks).ok)
    }

    @Test
    fun bothPathsOffIsRefused() {
        val r = SettingsRules.validate(valid().copy(discovery = false, direct = false), checks)
        assertTrue(r.noPath)
        assertFalse(r.ok)
    }

    @Test
    fun peersAreOnlyCheckedWhileDirectIsOn() {
        val off = SettingsRules.validate(valid().copy(direct = false, peers = listOf("")), checks)
        assertTrue(off.ok)
        val on = SettingsRules.validate(valid().copy(direct = true, peers = listOf("")), checks)
        assertEquals(listOf(RowProblem.Required(lastRow = true)), on.peers)
    }

    @Test
    fun rowProblemsNameTheRow() {
        val r = SettingsRules.validate(
            valid().copy(
                direct = true,
                peers = listOf("pc.local", "PC.local", "", "me.local", "bad name"),
                ownAddresses = listOf("[ME.local]"),
            ),
            checks,
        )
        assertEquals(
            listOf(null, RowProblem.Duplicate, RowProblem.Required(lastRow = false), RowProblem.IsSelf, RowProblem.Invalid("not a valid host name")),
            r.peers,
        )
        assertFalse(r.ownHasError)
    }

    @Test
    fun ownListMayBeEmptyButNotRepeat() {
        val r = SettingsRules.validate(valid().copy(ownAddresses = listOf("a.local", "", "A.local")), checks)
        assertEquals(listOf(null, null, RowProblem.Duplicate), r.ownAddresses)
        assertTrue(r.ownHasError)
    }

    @Test
    fun theKeyNeverReachesToString() {
        assertFalse(valid().toString().contains(psk))
    }
}
