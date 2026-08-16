package dev.inspector.internal

import dev.inspector.InspectorConfig
import dev.inspector.Recorder
import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException

/**
 * Records OkHttp calls into the same [Recorder] the Ktor plugin feeds.
 *
 * The load-bearing guarantee is the same as the Ktor path's: **the app must receive byte-identical
 * bodies whether or not this is installed.** Response bodies are therefore teed lazily as the app
 * reads them, never pre-read. `Response.peekBody` would have been shorter, but it blocks until the
 * requested count arrives — which on a `text/event-stream` or any long-lived response means
 * stalling the app until the cap fills. Observing a stream must not consume or delay it.
 */
internal class InspectorOkHttpInterceptor(private val recorder: Recorder) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val config = recorder.config
        val redactor = Redactor(config.redaction)

        val id = newId()
        val callId = newId()
        val ts = nowIso()
        val startMono = monoMs()

        val reqCapture = captureRequestBody(request.body, config)

        val response = try {
            chain.proceed(request)
        } catch (cause: IOException) {
            val hits = mutableListOf<String>()
            recorder.submit(
                request.toTransaction(
                    id = id, ts = ts, mono = startMono, callId = callId,
                    ms = monoMs() - startMono,
                    status = null,
                    error = cause.describe(),
                    reqCapture = reqCapture,
                    redactor = redactor,
                    hits = hits,
                ),
                reqBody = redactor.body(reqCapture.bytes, request.contentTypeOrNull(), "req", hits),
                resBody = null,
            )
            throw cause
        }

        val resContentType = response.header("Content-Type")
        val captureResBody = config.capturesBody(resContentType)

        fun emit(captured: CapturedBody) {
            val hits = mutableListOf<String>()
            recorder.submit(
                request.toTransaction(
                    id = id, ts = ts, mono = startMono, callId = callId,
                    ms = monoMs() - startMono,
                    status = response.code,
                    reqCapture = reqCapture,
                    redactor = redactor,
                    hits = hits,
                ).copy(
                    resHeaders = redactor.headers(response.headers.asMultimap(), hits),
                    resContentType = resContentType,
                    resBytes = captured.totalBytes,
                    resBodyTruncated = captured.truncated,
                    resBodyOmitted = captured.omitted,
                    redacted = hits,
                ),
                reqBody = redactor.body(reqCapture.bytes, request.contentTypeOrNull(), "req", hits),
                resBody = redactor.body(captured.bytes, resContentType, "res", hits),
            )
        }

        val body = response.body
        if (body == null) {
            emit(CapturedBody(null, 0, false))
            return response
        }

        return response.newBuilder()
            .body(
                TeeingResponseBody(
                    delegate = body,
                    cap = if (captureResBody) config.bodyCaptureMaxBytes else 0,
                    onComplete = ::emit,
                )
            )
            .build()
    }
}

/**
 * A [ResponseBody] that forwards every byte to the reader while copying a capped prefix aside.
 *
 * [onComplete] fires exactly once: at end of stream, or on close if the caller abandons the body
 * partway. A call must produce a row either way — a body nobody read is still a call that
 * happened.
 */
private class TeeingResponseBody(
    private val delegate: ResponseBody,
    /** Bytes to retain. Zero means count only, which is how a non-allowlisted type is handled. */
    private val cap: Int,
    private val onComplete: (CapturedBody) -> Unit,
) : ResponseBody() {

    private val captured = Buffer()
    private var total = 0L
    private var truncated = false
    private var completed = false

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    private val teed: BufferedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read == -1L) {
                complete()
                return -1L
            }
            total += read
            if (cap > 0) {
                val room = (cap - captured.size).coerceAtLeast(0)
                val take = minOf(read, room)
                // The bytes just delivered are the last `read` bytes of sink; copy, never move.
                if (take > 0) sink.copyTo(captured, sink.size - read, take)
                if (take < read) truncated = true
            }
            return read
        }

        override fun close() {
            complete()
            super.close()
        }
    }.buffer()

    override fun source(): BufferedSource = teed

    private fun complete() {
        if (completed) return
        completed = true
        onComplete(
            CapturedBody(
                bytes = if (cap > 0) captured.readByteArray() else null,
                totalBytes = total,
                truncated = truncated,
                omitted = if (cap == 0 && total > 0) BodyOmission.CONTENT_TYPE else null,
            )
        )
    }
}

/**
 * Buffers an outgoing body without letting a large upload cost memory.
 *
 * Never touches a duplex or one-shot body: those can be written exactly once, and reading one to
 * inspect it would take it away from the request.
 */
private fun captureRequestBody(body: RequestBody?, config: InspectorConfig): CapturedBody {
    if (body == null) return CapturedBody(null, 0, false)

    val declared = runCatching { body.contentLength() }.getOrDefault(-1L)
    val known = declared.coerceAtLeast(0)

    if (body.isDuplex() || body.isOneShot()) {
        return CapturedBody(null, known, false, BodyOmission.STREAMING)
    }
    if (!config.capturesBody(body.contentType()?.toString())) {
        return CapturedBody(null, known, false, if (known > 0) BodyOmission.CONTENT_TYPE else null)
    }

    val sink = CappedSink(config.bodyCaptureMaxBytes)
    val wrote = runCatching { sink.buffer().use { body.writeTo(it) } }.isSuccess
    if (!wrote) {
        // Capture failing must never fail the request; report the size and move on.
        return CapturedBody(null, known, false, BodyOmission.STREAMING)
    }

    return CapturedBody(
        bytes = sink.captured.readByteArray(),
        totalBytes = sink.total,
        truncated = sink.total > config.bodyCaptureMaxBytes,
    )
}

/** Retains at most [cap] bytes while counting all of them, so a 100 MB upload costs [cap]. */
private class CappedSink(private val cap: Int) : Sink {
    val captured = Buffer()
    var total = 0L

    override fun write(source: Buffer, byteCount: Long) {
        val room = (cap - captured.size).coerceAtLeast(0)
        val take = minOf(byteCount, room)
        if (take > 0) source.read(captured, take)
        // The contract is to consume exactly byteCount; anything past the cap is dropped, not left.
        if (byteCount > take) source.skip(byteCount - take)
        total += byteCount
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private fun Request.contentTypeOrNull(): String? =
    header("Content-Type") ?: body?.contentType()?.toString()

/**
 * Header names as they were actually sent.
 *
 * OkHttp's own `toMultimap()` lowercases every name, which would make an OkHttp row display
 * `authorization` where the Ktor row for the same header displays `Authorization`. Two capture
 * paths feeding one list should not disagree about what a header is called.
 */
private fun okhttp3.Headers.asMultimap(): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>(size)
    for (i in 0 until size) out.getOrPut(name(i)) { mutableListOf() } += value(i)
    return out
}

private fun Request.toTransaction(
    id: String,
    ts: String,
    mono: Long,
    callId: String,
    ms: Long,
    status: Int?,
    error: String? = null,
    reqCapture: CapturedBody,
    redactor: Redactor,
    hits: MutableList<String>,
): NetworkTransaction = NetworkTransaction(
    id = id,
    ts = ts,
    mono = mono,
    method = method.uppercase(),
    scheme = url.scheme,
    host = url.host,
    path = url.encodedPath,
    query = redactor.query(url.encodedQuery?.takeIf { it.isNotEmpty() }, hits),
    status = status,
    error = error,
    ms = ms,
    // OkHttp retries and redirects below an application interceptor, so one call is one row.
    attempt = 1,
    callId = callId,
    reqBytes = reqCapture.totalBytes,
    reqHeaders = redactor.headers(headers.asMultimap(), hits),
    reqBodyTruncated = reqCapture.truncated,
    reqBodyOmitted = reqCapture.omitted,
    reqContentType = contentTypeOrNull(),
)

private fun BufferedSink.use(block: (BufferedSink) -> Unit) {
    try {
        block(this)
    } finally {
        // Flushing is what pushes the last segment into the capped sink.
        runCatching { close() }
    }
}
