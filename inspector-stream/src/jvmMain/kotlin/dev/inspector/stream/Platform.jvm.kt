package dev.inspector.stream

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.charset.CodingErrorAction
import java.util.Base64

actual fun defaultDaemonHost(): String = "127.0.0.1"

actual fun defaultStreamClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}

internal actual fun isUtf8(bytes: ByteArray): Boolean = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
    true
}.getOrDefault(false)

internal actual fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
