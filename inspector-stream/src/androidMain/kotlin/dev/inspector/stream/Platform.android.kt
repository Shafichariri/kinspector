package dev.inspector.stream

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The Android emulator reaches the host machine's loopback through this alias. */
actual fun defaultDaemonHost(): String = "10.0.2.2"

actual fun defaultStreamClient(): HttpClient = HttpClient(OkHttp) {
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
