package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsbFramingTest {

    @Test
    fun `a frame is a big-endian byte count then the UTF-8 payload`() {
        val frame = UsbFraming.encode("hi")
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte()), frame)
    }

    @Test
    fun `the length counts bytes not characters`() {
        // Three characters, six bytes. A length taken from String.length would cut the frame
        // short and misread every frame after it.
        val text = "é→x"
        val frame = UsbFraming.encode(text)
        val length = UsbFraming.payloadLength(frame.copyOfRange(0, 4))
        assertEquals(text.encodeToByteArray().size, length)
        assertEquals(text, frame.copyOfRange(4, 4 + length).decodeToString())
    }

    @Test
    fun `an empty payload is a valid frame`() {
        assertEquals(0, UsbFraming.payloadLength(UsbFraming.encode("").copyOfRange(0, 4)))
    }

    @Test
    fun `a length above the limit is refused`() {
        val header = byteArrayOf(0x04, 0, 0, 1) // 64 MiB + 1
        assertFailsWith<IllegalArgumentException> { UsbFraming.payloadLength(header) }
    }

    @Test
    fun `an HTTP client dialling the port by mistake is refused on its first four bytes`() {
        assertFailsWith<IllegalArgumentException> {
            UsbFraming.payloadLength("GET ".encodeToByteArray())
        }
    }

    @Test
    fun `a length with the top bit set is refused rather than read as negative`() {
        assertFailsWith<IllegalArgumentException> {
            UsbFraming.payloadLength(byteArrayOf(0x80.toByte(), 0, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> { UsbFraming.checkLength(-1) }
    }

    @Test
    fun `the largest permitted length round trips`() {
        val max = UsbFraming.MAX_FRAME_BYTES
        val header = byteArrayOf((max ushr 24).toByte(), (max ushr 16).toByte(), (max ushr 8).toByte(), max.toByte())
        assertEquals(max, UsbFraming.payloadLength(header))
    }
}
