package dev.inspector.internal

import dev.inspector.Recorder
import dev.inspector.model.NetworkTransaction
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SetupRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.call.replaceResponse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.launch

/**
 * Shared per-logical-call state: one id for the call, and a counter the attempts increment.
 *
 * Mutable and shared **by reference** on purpose. `HttpRequestBuilder.takeFrom` copies attribute
 * entries, so every rebuilt request points at this same instance. That reference sharing is the
 * only thing that survives `HttpRequestRetry`, which reconstructs each attempt from the
 * *original* builder — a plain `Int` attribute would reset to 1 on every retry, which is exactly
 * what `PerAttemptTest` caught.
 */
internal class CallState(val callId: String) {
    var attempt: Int = 0
}

internal val CallStateKey = AttributeKey<CallState>("InspectorCallState")

/**
 * Marks a client as belonging to the inspector itself, so its own traffic is never captured.
 * Guards against a feedback loop if the stream sink's client is ever mis-wired.
 */
internal val SelfTrafficKey = AttributeKey<Boolean>("InspectorSelfTraffic")

/**
 * Installs capture on a client.
 *
 * Uses the `Send` hook rather than `onRequest`/`onResponse`, because those fire once per
 * *logical call* while `Send` sits inside the redirect and retry loops and therefore fires once
 * per *attempt* — which is the contract `PerAttemptTest` pins down.
 */
internal fun HttpClientConfig<*>.installInspector(recorder: Recorder) {
    install(inspectorPlugin(recorder))
}

// `HttpResponse.rawContent` is public but @InternalAPI. Ktor's own Logging plugin reads it the
// same way; there is no public accessor for the undecoded body channel. Pinned to Ktor 3.5.0 —
// revisit on upgrade.
@OptIn(InternalAPI::class)
internal fun inspectorPlugin(recorder: Recorder) = createClientPlugin("Inspector") {
    // Runs once per logical call, on the original builder, before HttpSend and therefore before
    // the redirect and retry loops that rebuild it.
    on(SetupRequest) { request ->
        if (request.attributes.getOrNull(SelfTrafficKey) != true) {
            request.attributes.put(CallStateKey, CallState(newId()))
        }
    }

    on(Send) { request ->
        if (request.attributes.getOrNull(SelfTrafficKey) == true) {
            return@on proceed(request)
        }

        // Absent only if a request bypassed the request pipeline; fall back to a solo call.
        val state = request.attributes.getOrNull(CallStateKey)
        val callId = state?.callId ?: newId()
        val attempt = state?.let { ++it.attempt } ?: 1

        val id = newId()
        val ts = nowIso()
        val startMono = monoMs()

        val config = recorder.config
        val outgoing = request.body as? OutgoingContent
        val snapshot = request.snapshotRequest(outgoing)
        val redactor = Redactor(config.redaction)
        val reqCapture = captureRequestBody(outgoing, config)

        try {
            val call: HttpClientCall = proceed(request)
            val response = call.response
            val resContentType = response.headers[HttpHeaders.ContentType]
            val captureResBody = config.capturesBody(resContentType)

            var emitted = false
            fun emit(captured: CapturedBody) {
                if (emitted) return
                emitted = true
                val hits = mutableListOf<String>()
                recorder.submit(
                    snapshot.toTransaction(
                        id = id,
                        ts = ts,
                        mono = startMono,
                        callId = callId,
                        attempt = attempt,
                        ms = monoMs() - startMono,
                        status = response.status.value,
                        resHeaders = redactor.headers(response.headers.toMultimap(), hits),
                        resContentType = resContentType,
                        resBytes = captured.totalBytes,
                        reqBytes = reqCapture.totalBytes,
                        reqBodyTruncated = reqCapture.truncated,
                        resBodyTruncated = captured.truncated,
                        reqBodyOmitted = reqCapture.omitted,
                        resBodyOmitted = captured.omitted,
                        reqHeaders = redactor.headers(snapshot.headers, hits),
                        query = redactor.query(snapshot.query, hits),
                        redacted = hits,
                    ),
                    reqBody = redactor.body(reqCapture.bytes, snapshot.contentType, "req", hits),
                    resBody = redactor.body(captured.bytes, resContentType, "res", hits),
                )
            }

            // The app reads the teed channel; every byte is forwarded unchanged.
            val appChannel = response.teeBody(
                source = response.rawContent,
                capture = captureResBody,
                maxBytes = config.bodyCaptureMaxBytes,
                onComplete = ::emit,
            )

            call.replaceResponse { appChannel }
        } catch (cause: Throwable) {
            val hits = mutableListOf<String>()
            recorder.submit(
                snapshot.toTransaction(
                    id = id,
                    ts = ts,
                    mono = startMono,
                    callId = callId,
                    attempt = attempt,
                    ms = monoMs() - startMono,
                    status = null,
                    error = cause.describe(),
                    reqBytes = reqCapture.totalBytes,
                    reqBodyTruncated = reqCapture.truncated,
                    reqBodyOmitted = reqCapture.omitted,
                    reqHeaders = redactor.headers(snapshot.headers, hits),
                    query = redactor.query(snapshot.query, hits),
                    redacted = hits,
                ),
                reqBody = redactor.body(reqCapture.bytes, snapshot.contentType, "req", hits),
                resBody = null,
            )
            throw cause
        }
    }
}

/**
 * Immutable copy of the outgoing request taken before `proceed`, because redirect and retry
 * mutate the builder in place — reading it afterwards would describe the wrong hop.
 */
internal class RequestSnapshot(
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val contentLength: Long,
)

/**
 * [content] is the rendered outgoing body. It is needed because Ktor carries a request's content
 * type on the body rather than in the builder's headers: reading headers alone reported
 * `reqContentType = null` for every request that had one, which in turn silently disabled
 * request-body redaction, since that bails on anything not declared JSON.
 */
internal fun HttpRequestBuilder.snapshotRequest(content: OutgoingContent? = null): RequestSnapshot {
    val url = url.build()
    val built = headers.build()
    return RequestSnapshot(
        method = method.value.uppercase(),
        scheme = url.protocol.name,
        host = url.host,
        path = url.encodedPath,
        query = url.encodedQuery.takeIf { it.isNotEmpty() },
        headers = built.toMultimap(),
        contentType = built[HttpHeaders.ContentType] ?: content?.contentType?.toString(),
        contentLength = built[HttpHeaders.ContentLength]?.toLongOrNull()
            ?: content?.contentLength
            ?: 0L,
    )
}

internal fun RequestSnapshot.toTransaction(
    id: String,
    ts: String,
    mono: Long,
    callId: String,
    attempt: Int,
    ms: Long,
    status: Int?,
    error: String? = null,
    resHeaders: Map<String, List<String>> = emptyMap(),
    resContentType: String? = null,
    resBytes: Long = 0,
    reqBytes: Long = contentLength,
    reqBodyTruncated: Boolean = false,
    resBodyTruncated: Boolean = false,
    reqBodyOmitted: String? = null,
    resBodyOmitted: String? = null,
    reqHeaders: Map<String, List<String>> = headers,
    query: String? = this.query,
    redacted: List<String> = emptyList(),
): NetworkTransaction = NetworkTransaction(
    id = id,
    ts = ts,
    mono = mono,
    method = method,
    scheme = scheme,
    host = host,
    path = path,
    query = query,
    status = status,
    error = error,
    ms = ms,
    attempt = attempt,
    callId = callId,
    reqBytes = reqBytes,
    resBytes = resBytes,
    reqHeaders = reqHeaders,
    resHeaders = resHeaders,
    reqBodyTruncated = reqBodyTruncated,
    resBodyTruncated = resBodyTruncated,
    reqBodyOmitted = reqBodyOmitted,
    resBodyOmitted = resBodyOmitted,
    reqContentType = contentType,
    resContentType = resContentType,
    redacted = redacted,
)

/** `SocketTimeoutException: after 10000ms` — class plus message, which is what a reader needs. */
internal fun Throwable.describe(): String {
    val name = this::class.simpleName ?: "Throwable"
    val msg = message
    return if (msg.isNullOrBlank()) name else "$name: $msg"
}

private fun io.ktor.http.Headers.toMultimap(): Map<String, List<String>> =
    entries().associate { it.key to it.value }
