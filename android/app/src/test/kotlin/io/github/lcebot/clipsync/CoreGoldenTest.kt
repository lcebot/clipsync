package io.github.lcebot.clipsync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the core whose output another device must reproduce byte for byte.
 *
 * The expected values come from an independent implementation (Python `cryptography`, the library
 * the Windows side uses), not from this code, so a port that changes a byte order, a sign extension
 * or a nonce layout fails here instead of failing a handshake with every peer.
 */
class CoreGoldenTest {
    private fun hex(s: String): ByteArray = Crypto.fromHex(s) ?: error("bad test hex")

    @Test
    fun hexRoundTripsAndRejectsGarbage() {
        val b = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("00017f80ff", Crypto.toHex(b))
        assertArrayEquals(b, Crypto.fromHex("00017F80FF"))
        assertNull(Crypto.fromHex("abc"))
        assertNull(Crypto.fromHex("zz"))
        assertNull(Crypto.fromHex(""))
        assertNull(Crypto.fromHex(null))
    }

    @Test
    fun hkdfMatchesRfc5869AsImplementedElsewhere() {
        val ikm = ByteArray(32) { it.toByte() }
        val out = Crypto.hkdfSha256(ikm, "clipsync-salt".toByteArray(), "clipsync test info".toByteArray(), 80)
        assertEquals(
            "c8c8de2e46fc7e9d8eaf6800c16533e081b2b2c40cc5c01645c5934ba1145dc098b285e6ad559a7573ba91366132b29b" +
                "cd82912f9b11bf0a53e4e369aa7ed6b7c131125589e2a278dbc4ef5a20fdec0d",
            Crypto.toHex(out),
        )
    }

    @Test
    fun sessionKeysMatchTheWindowsSide() {
        val psk = hex("ab".repeat(32))
        val salt = ByteArray(32) { it.toByte() }
        assertEquals(
            "0bd0c26060bafe56f27e6f5335a59bc354ba01f71f3f8195528f8b9b78db16f5",
            Crypto.toHex(Crypto.hkdfSha256(psk, salt, "clipsync c2s".toByteArray(Charsets.US_ASCII), 32)),
        )
        assertEquals(
            "60e0cfbe5b45deb9ede26e63a837ec831f2c8871a95b7f0ab202ae2a48d34b97",
            Crypto.toHex(Crypto.hkdfSha256(psk, salt, "clipsync s2c".toByteArray(Charsets.US_ASCII), 32)),
        )
    }

    /** Nonces are 4 zero bytes then a big-endian 64-bit counter, starting at 0 and never reused. */
    @Test
    fun sealerCountsNoncesLikeThePeer() {
        val key = ByteArray(32) { (100 + it).toByte() }
        val sealer = Crypto.Sealer(key)
        assertEquals("7036073b1cc8682e070c80fd4deab1cd40c734d013791f0861dcb0758f1bb6", Crypto.toHex(sealer.seal("hello clipboard".toByteArray())))
        assertEquals("1cedbd4c1e40b3734156aa283c20e60a", Crypto.toHex(sealer.seal(ByteArray(0))))
        val out = ByteArray(40 + 16)
        val n = sealer.sealInto(ByteArray(40), 0, 40, out, 0)
        assertEquals(56, n)
        assertEquals(
            "993d1d4416f69e5acbb1426b64f7c6a57361abb977481625705b238e75e2e8e421fc0807fc210f3b2f10402415a6fcd659222543f7d4e2dc",
            Crypto.toHex(out),
        )
    }

    @Test
    fun openerFollowsTheSameCounter() {
        val key = ByteArray(32) { (100 + it).toByte() }
        val opener = Crypto.Opener(key)
        assertEquals("hello clipboard", String(opener.open(hex("7036073b1cc8682e070c80fd4deab1cd40c734d013791f0861dcb0758f1bb6"))))
        assertEquals(0, opener.open(hex("1cedbd4c1e40b3734156aa283c20e60a")).size)
    }

    @Test
    fun sha256HexIsUtf8() {
        assertEquals("3c48591d8d098a4538f5e013dfcf406e948eac4d3277b10bf614e295d6068179", Crypto.sha256Hex("héllo"))
    }

    /** The key set of a HELLO is what the Windows side parses. */
    @Test
    fun controlHelloKeepsItsKeys() {
        val h = Hello.control("id1", "Pixel", "android", true, "high", 42L, "abc", true, 47521)
        val o = h.toJson()
        assertEquals(Connection.PROTOCOL_VERSION, o.getInt("v"))
        assertEquals(
            setOf("v", "id", "device", "type", "persistent", "battery", "clip_ts", "clip_sha", "lan", "port", "data_out"),
            o.keys().asSequence().toSet(),
        )
        val back = Hello.parse(JSONObject(o.toString()))
        assertEquals("id1", back.id)
        assertEquals("Pixel", back.device)
        assertEquals(42L, back.clipTs)
        assertEquals(47521, back.port)
        assertTrue(back.lan)
        assertTrue(back.dataOut)
        assertTrue(back.has("clip_sha"))
    }

    @Test
    fun missingKeysParseToTheDocumentedDefaults() {
        val h = Hello.parse(JSONObject("{}"))
        assertEquals(-1, h.v)
        assertNull(h.id)
        assertEquals("", h.device)
        assertEquals("?", h.type)
        assertEquals("medium", h.battery)
        assertFalse(h.persistent)
        assertFalse(h.has("v"))
    }

    @Test
    fun protocolVersionIsUnchanged() {
        assertEquals(5, Connection.PROTOCOL_VERSION)
    }
}
