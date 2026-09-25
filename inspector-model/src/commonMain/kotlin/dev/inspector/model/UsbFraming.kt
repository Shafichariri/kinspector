package dev.inspector.model

/**
 * Framing for the USB leg between a physical iPhone and the host's usbmuxd bridge.
 *
 * The messages are the same [WireMsg] JSON that `WS /ingest` carries as text frames. Only the
 * envelope differs: a 4-byte big-endian byte count, then that many bytes of UTF-8. WebSocket is not
 * used on this leg because the connection runs the other way round — usbmuxd can only dial *into*
 * the phone, so the app accepts where everywhere else it connects, and no Ktor client engine can
 * run a WebSocket over a socket it accepted. The bridge unwraps each frame and hands it to the
 * daemon's ordinary `/ingest`, so the daemon never learns the leg existed.
 *
 * Lives here so the phone and the bridge run one codec rather than two copies that agree today.
 */
object UsbFraming {

    /** Bytes in the length prefix. */
    const val HEADER_BYTES: Int = 4

    /**
     * Refuse anything claiming to be larger. Far above any real frame — bodies are capped at
     * capture — and far below what a stray peer's first four bytes decode to: an HTTP client that
     * dialled the port by mistake sends `GET `, which reads as a 1.2 GB frame. Allocating that on a
     * phone because a stranger said so is the failure this exists to prevent.
     */
    const val MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

    /** [text] as one frame: header and payload in a single array, ready to write. */
    fun encode(text: String): ByteArray {
        val payload = text.encodeToByteArray()
        require(payload.size <= MAX_FRAME_BYTES) {
            "frame of ${payload.size} bytes exceeds the $MAX_FRAME_BYTES-byte limit"
        }
        val frame = ByteArray(HEADER_BYTES + payload.size)
        writeLength(payload.size, frame)
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /**
     * The payload length a header announces. Throws rather than clamping: a frame boundary that is
     * wrong once is wrong for every byte after it, so the only safe response is to drop the link.
     */
    fun payloadLength(header: ByteArray): Int {
        require(header.size == HEADER_BYTES) { "header must be $HEADER_BYTES bytes, got ${header.size}" }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        return checkLength(length)
    }

    /**
     * Validates a length read some other way — a channel's own `readInt`, say. Negative covers a
     * top bit set, which the signed read reports as below zero.
     */
    fun checkLength(length: Int): Int {
        require(length in 0..MAX_FRAME_BYTES) {
            "frame header announces $length bytes; the limit is $MAX_FRAME_BYTES — " +
                "the peer is not speaking this protocol"
        }
        return length
    }

    private fun writeLength(length: Int, into: ByteArray) {
        into[0] = (length ushr 24).toByte()
        into[1] = (length ushr 16).toByte()
        into[2] = (length ushr 8).toByte()
        into[3] = length.toByte()
    }
}
