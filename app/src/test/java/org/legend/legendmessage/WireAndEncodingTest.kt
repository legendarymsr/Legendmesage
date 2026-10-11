package org.legend.legendmessage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.legend.legendmessage.net.Wire
import org.legend.legendmessage.tor.Base32
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WireAndEncodingTest {

    @Test fun base32MatchesRfc4648Vectors() {
        // RFC 4648 section 10, padding stripped (Tor uses unpadded base32).
        val vectors = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6",
            "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        vectors.forEach { (plain, enc) -> assertEquals(enc, Base32.encode(plain.toByteArray())) }
    }

    @Test fun base32OfX25519KeyIs52Chars() {
        assertEquals(52, Base32.encode(ByteArray(32) { it.toByte() }).length)
    }

    private fun frame(len: Int): Wire.Envelope? {
        val buf = ByteArrayOutputStream()
        val out = Wire.output(buf)
        out.writeInt(len); out.write(ByteArray(len))
        out.flush()
        return runCatching { Wire.readEnvelope(Wire.input(ByteArrayInputStream(buf.toByteArray()))) }.getOrNull()
    }

    @Test fun envelopeRejectsWrongSizedSenderId() {
        assertEquals(null, frame(32))
        assertEquals(null, frame(1 shl 20))
    }

    @Test fun envelopeRoundTripsAndEnforcesBodyLimit() {
        val sender = ByteArray(33).also { it[0] = 0x05 }
        val ok = Wire.Envelope(sender, 3, ByteArray(100) { 7 })
        val buf = ByteArrayOutputStream()
        Wire.writeEnvelope(Wire.output(buf), ok)
        assertEquals(ok, Wire.readEnvelope(Wire.input(ByteArrayInputStream(buf.toByteArray()))))

        val big = ByteArrayOutputStream()
        Wire.writeEnvelope(Wire.output(big), Wire.Envelope(sender, 3, ByteArray(Wire.MAX_BODY + 1)))
        assertTrue(runCatching { Wire.readEnvelope(Wire.input(ByteArrayInputStream(big.toByteArray()))) }.isFailure)
    }

    @Test fun mailboxAuthMessageIsBoundToOnionOpAndRecipient() {
        val rid = ByteArray(33).also { it[0] = 0x05 }
        val ch = ByteArray(32) { 1 }
        val a = Wire.mailboxAuthMessage(Wire.OP_SUBSCRIBE_V2, "aaaa.onion", rid, ch)
        assertArrayEquals(a, Wire.mailboxAuthMessage(Wire.OP_SUBSCRIBE_V2, " AAAA.onion ", rid, ch))
        assertFalse(a.contentEquals(Wire.mailboxAuthMessage(Wire.OP_SUBSCRIBE_V2, "bbbb.onion", rid, ch)))
        assertFalse(a.contentEquals(Wire.mailboxAuthMessage(Wire.OP_COLLECT_V2, "aaaa.onion", rid, ch)))
        val rid2 = rid.copyOf().also { it[1] = 9 }
        assertFalse(a.contentEquals(Wire.mailboxAuthMessage(Wire.OP_SUBSCRIBE_V2, "aaaa.onion", rid2, ch)))
        assertFalse(a.contentEquals(ch))
    }
}
