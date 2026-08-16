package dev.inspector.stream

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/** The iOS simulator shares the host's network stack, so loopback reaches the daemon directly. */
actual fun defaultDaemonHost(): String = "127.0.0.1"

actual fun defaultStreamClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}

internal actual fun isUtf8(bytes: ByteArray): Boolean {
    // decodeToString replaces malformed input rather than throwing, so a round trip is the
    // portable way to ask "were these bytes actually UTF-8?" on Native.
    val decoded = bytes.decodeToString()
    return decoded.encodeToByteArray().contentEquals(bytes)
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal actual fun base64Encode(bytes: ByteArray): String = buildString {
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        append(BASE64_ALPHABET[(n shr 18) and 63])
        append(BASE64_ALPHABET[(n shr 12) and 63])
        append(BASE64_ALPHABET[(n shr 6) and 63])
        append(BASE64_ALPHABET[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            append(BASE64_ALPHABET[(n shr 18) and 63])
            append(BASE64_ALPHABET[(n shr 12) and 63])
            append("==")
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            append(BASE64_ALPHABET[(n shr 18) and 63])
            append(BASE64_ALPHABET[(n shr 12) and 63])
            append(BASE64_ALPHABET[(n shr 6) and 63])
            append('=')
        }
    }
}
