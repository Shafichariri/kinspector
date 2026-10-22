package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.model.NetworkTransaction
import java.nio.file.Path

// Shared by the findings tests: a row as capture hands it to the writer, and a session of them.

const val FINDINGS_HOST = "api.example.invalid"

/** A row and the request body capture kept for it, as `SessionWriter.append` takes them. */
data class Captured(val row: NetworkTransaction, val body: ByteArray?)

/**
 * A row for the payment rules. [body] is the request body as sent (null is none); [key] the value of
 * [keyHeader] (null is no header). A [truncated] body keeps its first half, as a capped capture keeps
 * a prefix; an [omitted] one keeps nothing. [redacted] is the row's `redacted[]`.
 */
fun mutation(
    id: String,
    mono: Long,
    callId: String,
    path: String = "/v1/payments",
    method: String = "POST",
    body: String? = """{"amount":"10.00","to":"acct-1"}""",
    key: String? = null,
    keyHeader: String = "Idempotency-Key",
    status: Int? = 201,
    error: String? = null,
    ms: Long? = 120,
    attempt: Int = 1,
    host: String = FINDINGS_HOST,
    truncated: Boolean = false,
    omitted: String? = null,
    redacted: List<String> = emptyList(),
    contentType: String = "application/json",
    seq: Long? = null,
): Captured {
    val bytes = body?.toByteArray()
    val row = NetworkTransaction(
        id = id,
        ts = "2025-12-31T08:00:00.000Z",
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
        reqBytes = bytes?.size?.toLong() ?: 0,
        reqHeaders = buildMap {
            if (bytes != null) put("Content-Type", listOf(contentType))
            if (key != null) put(keyHeader, listOf(key))
        },
        reqBodyTruncated = truncated,
        reqBodyOmitted = omitted,
        reqContentType = if (bytes != null) contentType else null,
        redacted = redacted,
        seq = seq,
    )
    val kept = when {
        bytes == null || bytes.isEmpty() || omitted != null -> null
        truncated -> bytes.copyOf(bytes.size / 2)
        else -> bytes
    }
    return Captured(row, kept)
}

/** A GET with no body. */
fun getCall(id: String, mono: Long, callId: String = "c-$id", path: String = "/v1/payments/7", status: Int = 200): Captured =
    Captured(txn(id, path = path, mono = mono, host = FINDINGS_HOST, callId = callId, status = status), null)

/** Writes [calls] as session [id] through the real writer, bodies before rows. */
fun writeFindingsSession(config: DaemonConfig, id: String, calls: List<Captured>): Path {
    val dir = config.sessionsDir.resolve(id)
    val writer = SessionWriter(dir, clientInfo().toSessionMeta(id, "2025-12-31T08:00:00.000Z"))
    calls.forEach { writer.append(it.row, it.body, null) }
    writer.close()
    return dir
}
