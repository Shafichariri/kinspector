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
    // The usual emulator tell. v1 only supports emulators, but recording the truth means the
    // archive is still accurate the day physical devices are added.
    val emulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true)
    return ClientInfo(
        appId = appId,
        appVersion = appVersion,
        platform = if (emulator) Platforms.ANDROID_EMULATOR else "android-device",
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        buildType = buildType,
    )
}

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
