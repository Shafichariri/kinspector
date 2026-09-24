// The JVM file facade is pinned, and the name is part of the ABI.
//
// `defaultDaemonHost` and `defaultClientInfo` are `expect`/`actual` here, so their actuals land
// in a facade named for *this file* — `Platform_jvmKt`, `Platform_androidKt`. The noop twin has
// no platform split and declares them outright in `StreamSink.kt`, giving `StreamSinkKt`. Same
// package, same signatures, same Kotlin call site, different JVM class: source-compatible and
// binary-incompatible, so anything compiled against one and linked against the other fails at
// runtime with a NoSuchMethodError naming a class that isn't there. Kotlin/Native has no
// facades, which is why only Android and JVM ever saw it.
//
// Pinning to the noop's name rather than the other way round is deliberate: it leaves the noop's
// published ABI untouched, and the noop is the artifact a release build links against.
//
// The rule this is an instance of: in a paired real/noop artifact, top-level declarations must
// live in identically-named files on both sides or carry a pinned `@file:JvmName`. Matching the
// *public API* is not enough — the file name is part of the ABI. `StreamApiParityTest` asserts
// the facade name now, so this cannot drift back.
@file:JvmName("StreamSinkKt")

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

/** A desktop app shares the host's loopback; there is nothing to bridge. */
internal actual fun listensForUsb(host: String): Boolean = false

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
