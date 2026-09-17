package dev.inspector.stream

import dev.inspector.model.ClientInfo
import dev.inspector.model.Platforms
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.charset.CodingErrorAction
import java.util.Base64

actual fun defaultClientInfo(appId: String, appVersion: String, buildType: String) = ClientInfo(
    appId = appId,
    appVersion = appVersion,
    platform = Platforms.DESKTOP,
    device = System.getProperty("os.name") ?: "desktop",
    osVersion = System.getProperty("os.version") ?: "unknown",
    buildType = buildType,
)

actual fun defaultDaemonHost(): String = "127.0.0.1"

/** Same machine, so there is exactly one thing it can be. */
internal actual fun connectionHelp(host: String, port: Int): String =
    "is `inspector serve` running? Nothing is listening on $host:$port."

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
