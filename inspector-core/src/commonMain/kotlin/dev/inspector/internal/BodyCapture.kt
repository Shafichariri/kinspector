package dev.inspector.internal

import dev.inspector.InspectorConfig
import dev.inspector.model.BodyOmission
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Result of teeing one body. */
internal class CapturedBody(
    /** Captured prefix, or null when nothing was captured — [omitted] says why. */
    val bytes: ByteArray?,
    /** True size in bytes, counted past the cap. */
    val totalBytes: Long,
    val truncated: Boolean,
    /** A [BodyOmission] constant when [bytes] is null despite there being a body. */
    val omitted: String? = null,
)

/**
 * True when bodies of this content type should be buffered.
 *
 * Prefix match, so `text/` covers `text/plain` and `text/html`, plus RFC 6838 structured-syntax
 * suffixes so `application/vnd.api+json` and `application/hal+json` are treated as the JSON they
 * are. Real APIs serve those and the first version silently dropped their bodies.
 *
 * A null content type is capturable only under [InspectorConfig.captureAllBodies]; otherwise
 * guessing wrong here means buffering binary.
 */
internal fun InspectorConfig.capturesBody(contentType: String?): Boolean {
    if (captureAllBodies) return true
    if (contentType == null) return false
    val normalized = contentType.substringBefore(';').trim().lowercase()
    if (captureContentTypes.any { normalized.startsWith(it.lowercase()) }) return true
    val subtype = normalized.substringAfter('/', "")
    return TEXT_LIKE_SUFFIXES.any { subtype.endsWith(it) }
}

/** Structured-syntax suffixes whose payload is text worth rendering. */
private val TEXT_LIKE_SUFFIXES = listOf("+json", "+xml")

/**
 * Returns a channel carrying exactly the bytes of [source], while capturing a capped copy.
 *
 * Ktor's own `split` is internal API, so this is a hand-rolled tee: one coroutine reads the
 * source, forwards every byte to the returned channel, and copies at most [maxBytes] aside.
 * Forwarding is unconditional — capture never alters, reorders, or truncates what the app reads,
 * which is the guarantee `BodyCaptureTest` exists to defend.
 *
 * [onComplete] fires exactly once: on normal completion, on error, or on the app abandoning the
 * body. A call must produce a row in all three cases.
 */
internal fun CoroutineScope.teeBody(
    source: ByteReadChannel,
    capture: Boolean,
    maxBytes: Int,
    onComplete: (CapturedBody) -> Unit,
): ByteReadChannel {
    val forwarded = ByteChannel(autoFlush = true)

    launch {
        val chunk = ByteArray(CHUNK)
        val accumulator = if (capture) ByteAccumulator(maxBytes) else null
        var total = 0L
        var truncated = false
        try {
            while (true) {
                val read = source.readAvailable(chunk, 0, chunk.size)
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (accumulator != null && accumulator.append(chunk, read)) truncated = true
                forwarded.writeFully(chunk, 0, read)
            }
            forwarded.flushAndClose()
        } catch (cause: Throwable) {
            // Propagate the failure to the app rather than handing it a silently short body.
            forwarded.cancel(cause)
        } finally {
            onComplete(
                CapturedBody(
                    bytes = accumulator?.toByteArray(),
                    totalBytes = total,
                    truncated = truncated,
                    omitted = if (accumulator == null && total > 0) BodyOmission.CONTENT_TYPE else null,
                )
            )
        }
    }

    return forwarded
}

/**
 * Captures an outgoing body without disturbing it.
 *
 * Only in-memory content is captured. Channel-backed content — streamed uploads — is reported by
 * its declared length and never buffered, because buffering a file upload in order to inspect it
 * is exactly the cost the efficiency contract forbids.
 */
internal fun captureRequestBody(
    content: OutgoingContent?,
    config: InspectorConfig,
): CapturedBody {
    if (content == null || content is OutgoingContent.NoContent) {
        return CapturedBody(null, 0, false)
    }

    val allowed = config.capturesBody(content.contentType?.toString())
    val cap = config.bodyCaptureMaxBytes

    return when (content) {
        is OutgoingContent.ByteArrayContent -> {
            val bytes = content.bytes()
            CapturedBody(
                bytes = if (allowed) bytes.copyOf(minOf(bytes.size, cap)) else null,
                totalBytes = bytes.size.toLong(),
                truncated = allowed && bytes.size > cap,
                omitted = if (allowed || bytes.isEmpty()) null else BodyOmission.CONTENT_TYPE,
            )
        }

        else -> {
            val length = content.contentLength ?: 0L
            CapturedBody(
                bytes = null,
                totalBytes = length,
                truncated = false,
                // Not a content-type decision: this body was never in memory to begin with.
                omitted = if (length > 0) BodyOmission.STREAMING else null,
            )
        }
    }
}

/**
 * Growable byte buffer with a hard ceiling.
 *
 * A plain `ArrayList<Byte>` would box every byte — 256 KB of body becoming 256 K boxed objects
 * on the capture path is precisely the overhead this tool promises not to impose.
 */
private class ByteAccumulator(private val cap: Int) {
    private var buffer = ByteArray(0)
    private var size = 0

    /** Returns true when the input had to be trimmed. */
    fun append(source: ByteArray, length: Int): Boolean {
        if (size >= cap) return true
        val take = minOf(cap - size, length)
        if (size + take > buffer.size) grow(size + take)
        source.copyInto(buffer, size, 0, take)
        size += take
        return take < length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun grow(required: Int) {
        var next = if (buffer.isEmpty()) minOf(cap, CHUNK) else buffer.size
        while (next < required) next = minOf(cap, next * 2)
        buffer = buffer.copyOf(next)
    }
}

private const val CHUNK = 8 * 1024
