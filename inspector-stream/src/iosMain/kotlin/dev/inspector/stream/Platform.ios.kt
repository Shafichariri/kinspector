package dev.inspector.stream

import dev.inspector.model.ClientInfo
import dev.inspector.model.Platforms
import io.ktor.client.HttpClient
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/**
 * True on the simulator, false on an iPhone — and known at link time, not guessed at runtime.
 *
 * The check this replaces asked whether `SIMULATOR_DEVICE_NAME` was in the environment. That is a
 * sound tell, but it is a tell: nothing proves its absence means hardware, and nobody has ever run
 * this on an iPhone to find out. The target does prove it. `iosArm64` and `iosSimulatorArm64` are
 * separate slices and neither binary will load on the other's host, so the constant cannot
 * disagree with where the process is.
 */
internal expect val IS_IOS_SIMULATOR: Boolean

actual fun defaultClientInfo(appId: String, appVersion: String, buildType: String): ClientInfo {
    // Still read for the name it gives — "iPhone 17 Pro" beats UIDevice's name. On hardware there
    // is no such variable and UIDevice is all there is, and since iOS 16 it answers the bare
    // "iPhone" unless the app holds the user-assigned-device-name entitlement — which is what the
    // first sessions recorded from a physical iPhone were labelled.
    val environment = NSProcessInfo.processInfo.environment
    val simulatorName = environment["SIMULATOR_DEVICE_NAME"] as? String
    return ClientInfo(
        appId = appId,
        appVersion = appVersion,
        platform = if (IS_IOS_SIMULATOR) Platforms.IOS_SIMULATOR else Platforms.IOS_DEVICE,
        device = simulatorName ?: UIDevice.currentDevice.name,
        osVersion = UIDevice.currentDevice.systemVersion,
        buildType = buildType,
    )
}

/**
 * Loopback on both kinds of iOS target, meaning different things. The simulator shares the host's
 * network stack, so it reaches the daemon directly. A physical iPhone's loopback is its own, and
 * there it is where the app *listens* for the daemon's USB bridge — see [listensForUsb].
 */
actual fun defaultDaemonHost(): String = "127.0.0.1"

/**
 * A physical iPhone waits for the host over USB; the simulator dials, like a desktop app. Decided
 * by [IS_IOS_SIMULATOR], which is fixed at link time, so the two cannot be confused at runtime.
 */
internal actual fun listensForUsb(host: String): Boolean = !IS_IOS_SIMULATOR && isLoopback(host)

/**
 * There is no `adb reverse` here and no cleartext exemption to forget, so neither belongs in this
 * message. On a physical iPhone the failure is the listener's, not a dial's: nothing reaches this
 * code merely because the cable is out — the app just waits — so what does arrive is a port
 * another app on the phone already holds, or a link that broke mid-frame.
 */
internal actual fun connectionHelp(host: String, port: Int): String =
    if (IS_IOS_SIMULATOR) {
        "is `inspector serve` running? The simulator shares the host's loopback, so $host:$port " +
            "is the same address you would open in a browser."
    } else if (isLoopback(host)) {
        "this is a physical iPhone, so the app listens on its own $host:$port and the host dials " +
            "in over USB: attach it by cable to a Mac running `inspector serve --port $port`. If " +
            "the port is taken, another app on this phone is probably running Inspector on it — " +
            "give each app its own port, and run the daemon on the same one."
    } else {
        "this is a physical iPhone dialling $host:$port directly, which only works if something " +
            "you set up forwards that address to the daemon. Leave the host at its default to use " +
            "the USB route instead."
    }

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
