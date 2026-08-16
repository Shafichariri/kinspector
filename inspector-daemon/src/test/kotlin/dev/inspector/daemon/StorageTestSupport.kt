package dev.inspector.daemon

import dev.inspector.model.ClientInfo
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Platforms
import java.nio.file.Path

fun clientInfo(
    appId: String = "com.example.projectx",
    device: String = "iPhone 16 Pro",
    buildType: String = "debug",
) = ClientInfo(
    appId = appId,
    appVersion = "1.4.2",
    platform = Platforms.IOS_SIMULATOR,
    device = device,
    osVersion = "26.0",
    buildType = buildType,
)

fun txn(
    id: String,
    path: String = "/v2/users/me",
    status: Int? = 200,
    ms: Long? = 143,
    mono: Long = 1_000,
    host: String = "api.example.com",
    method: String = "GET",
    attempt: Int = 1,
    callId: String = "c-$id",
    error: String? = null,
    resBytes: Long = 0,
) = NetworkTransaction(
    id = id,
    ts = "2026-08-16T10:14:02.311Z",
    mono = mono,
    method = method,
    scheme = "https",
    host = host,
    path = path,
    status = status,
    error = error,
    ms = ms,
    attempt = attempt,
    callId = callId,
    resBytes = resBytes,
)

fun marker(label: String, mono: Long = 500) =
    Marker(ts = "2026-08-16T10:14:02.311Z", mono = mono, label = label, source = MarkerSource.APP)

/** Writes a complete session folder without going through the server. */
fun writeSession(
    config: DaemonConfig,
    sessionId: String,
    startedAt: String,
    transactions: List<NetworkTransaction> = emptyList(),
    markers: List<Marker> = emptyList(),
    padBytes: Int = 0,
): Path {
    val dir = config.sessionsDir.resolve(sessionId)
    val meta = clientInfo().toSessionMeta(sessionId, startedAt)
    val writer = SessionWriter(dir, meta)
    transactions.forEach { writer.append(it, null, if (padBytes > 0) ByteArray(padBytes) else null) }
    markers.forEach { writer.append(it) }
    writer.close()
    return dir
}
