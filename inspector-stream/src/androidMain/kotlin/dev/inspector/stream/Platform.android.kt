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

import android.os.Build
import dev.inspector.model.ClientInfo
import dev.inspector.model.Platforms
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.charset.CodingErrorAction
import java.util.Base64

actual fun defaultClientInfo(appId: String, appVersion: String, buildType: String): ClientInfo {
    // See EmulatorDetection.kt for why this is not a string match on the fingerprint any more.
    return ClientInfo(
        appId = appId,
        appVersion = appVersion,
        platform = if (isAndroidEmulator()) Platforms.ANDROID_EMULATOR else Platforms.ANDROID_DEVICE,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        buildType = buildType,
    )
}

/**
 * Where the daemon is, from inside this process.
 *
 * `10.0.2.2` is the emulator's alias for the host machine's loopback and resolves to nothing on a
 * phone. A USB-attached phone gets there through its *own* loopback instead, because
 * `adb reverse tcp:8099 tcp:8099` forwards the device's `127.0.0.1:8099` to the same port on the
 * machine running adb. That is the whole of Android hardware support, and it costs the daemon
 * nothing: it keeps binding loopback only, so the security boundary in `Server.kt` is untouched.
 *
 * This is the first thing that reads the emulator detection for a decision rather than for a
 * label, which is why fixing that detection came first. While it was wrong, every emulator
 * answered "physical device", and this function would have sent every emulator to `127.0.0.1`.
 */
actual fun defaultDaemonHost(): String = daemonHostFor(isAndroidEmulator())

/** Android dials out on every route — `adb reverse` points the phone's loopback at the host. */
internal actual fun listensForUsb(host: String): Boolean = false

/** Split out from [defaultDaemonHost] so the choice can be tested without an Android runtime. */
internal fun daemonHostFor(emulator: Boolean): String =
    if (emulator) EMULATOR_HOST_ALIAS else DEVICE_LOOPBACK

/** The emulator's alias for the host machine's loopback. Fixed by the emulator, not by us. */
internal const val EMULATOR_HOST_ALIAS = "10.0.2.2"

/** The device's own loopback, which `adb reverse` points at the host machine. */
internal const val DEVICE_LOOPBACK = "127.0.0.1"

/**
 * One line of prose after a failed connection, naming the remedy for the address actually tried.
 *
 * Both wrong answers are silent in the same way — the socket simply never connects — so the
 * address is the only thing that distinguishes them, and guessing wrong sends someone to edit a
 * manifest when what they needed was one adb command.
 */
internal actual fun connectionHelp(host: String, port: Int): String = buildString {
    append("is `inspector serve` running on the host machine? ")
    when {
        host == EMULATOR_HOST_ALIAS ->
            append("$host is the emulator's alias for the host's loopback and reaches nothing ")
                .append("on a physical device — on hardware, use `adb reverse tcp:$port ")
                .append("tcp:$port` and connect to $DEVICE_LOOPBACK instead. ")
        host == DEVICE_LOOPBACK || host == "localhost" ->
            append("$host is this device's own loopback, which only reaches the host machine ")
                .append("while `adb reverse tcp:$port tcp:$port` is in place — it does not ")
                .append("survive a replug or an adb restart, so run it again. ")
        else -> Unit
    }
    append("The app must also permit cleartext traffic to $host — see docs/INTEGRATION.md 6d.")
}

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
