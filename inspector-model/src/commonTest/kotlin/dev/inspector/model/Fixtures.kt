package dev.inspector.model

/** Baseline transaction; tests override only the fields they exercise. */
fun txn(
    id: String = "7f3a0001",
    ts: String = "2026-08-16T10:14:02.311Z",
    mono: Long = 1_000,
    method: String = "GET",
    scheme: String = "https",
    host: String = "api.example.com",
    path: String = "/v2/users/me",
    query: String? = null,
    status: Int? = 200,
    error: String? = null,
    ms: Long? = 143,
    attempt: Int = 1,
    callId: String = "c0000001",
    reqBytes: Long = 0,
    resBytes: Long = 2_841,
    reqHeaders: Map<String, List<String>> = emptyMap(),
    resHeaders: Map<String, List<String>> = emptyMap(),
    reqBodyRef: String? = null,
    resBodyRef: String? = null,
    reqBodyTruncated: Boolean = false,
    resBodyTruncated: Boolean = false,
    reqContentType: String? = null,
    resContentType: String? = "application/json",
    redacted: List<String> = emptyList(),
) = NetworkTransaction(
    id = id, ts = ts, mono = mono, method = method, scheme = scheme, host = host, path = path,
    query = query, status = status, error = error, ms = ms, attempt = attempt, callId = callId,
    reqBytes = reqBytes, resBytes = resBytes, reqHeaders = reqHeaders, resHeaders = resHeaders,
    reqBodyRef = reqBodyRef, resBodyRef = resBodyRef, reqBodyTruncated = reqBodyTruncated,
    resBodyTruncated = resBodyTruncated, reqContentType = reqContentType,
    resContentType = resContentType, redacted = redacted,
)

fun marker(
    label: String,
    mono: Long,
    ts: String = "2026-08-16T10:14:02.311Z",
    source: String = MarkerSource.APP,
) = Marker(ts = ts, mono = mono, label = label, source = source)
