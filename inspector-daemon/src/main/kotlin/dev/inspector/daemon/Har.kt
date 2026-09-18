package dev.inspector.daemon

import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SessionMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * HAR 1.2 export of an archived session.
 *
 * HAR is the one interchange format every network tool already reads — Chrome and Firefox
 * DevTools, Charles, Proxyman, Postman, `har-analyzer` — so this is what makes a session
 * shareable with somebody who does not have Inspector. It is an export and nothing more: the
 * archive stays the source of truth, and nothing reads a HAR back in.
 *
 * ## What is honestly unknown, and stays unknown
 *
 * Inspector is not wire-level (`AGENTS.md`, "What it is not"), and HAR was designed by and for
 * tools that are. Several required fields describe things capture never saw, and **every one of
 * them is emitted as `-1`**, which is exactly what the specification reserves for "no
 * information available":
 *
 * - `timings.send`, `timings.wait`, `timings.receive`. There is one duration, measured around
 *   the call. Splitting it into a plausible-looking breakdown would draw a waterfall in DevTools
 *   out of numbers nobody measured, and a waterfall is read as evidence. `time` carries the real
 *   total, which is the one timing that is real.
 * - `headersSize`. Capture holds parsed headers, never the raw block, so the byte count of the
 *   block as sent is not recoverable. `bodySize` is *not* -1: `reqBytes`/`resBytes` are true
 *   sizes, counted even when the body itself was not captured.
 * - `httpVersion`. Ktor's client does not surface the negotiated version at this layer. An empty
 *   string is the spec's answer; writing `HTTP/1.1` would be a guess that reads as a measurement.
 *
 * Cookies are emitted as empty arrays. The `Cookie` and `Set-Cookie` headers are present and
 * complete in `headers`, so nothing is lost — a partially parsed cookie list would be a second,
 * worse copy of data that is already there in full.
 *
 * ## Redaction is disclosed per entry
 *
 * A session recorded under `Redaction.On` produces a HAR with values already replaced. Handing
 * that to somebody as a complete capture would be the export lying by omission, so each entry's
 * `comment` names what was removed, from the row's own `redacted` list. Redaction is off by
 * default, so this is usually absent.
 *
 * ## One entry per attempt
 *
 * The archive records a row per redirect hop and per retry, and the HAR keeps them: they are the
 * rows a "one row per call" view would hide, and HAR's own model for a redirect chain is separate
 * entries anyway. `redirectURL` is filled from the `Location` header where there is one, so a
 * viewer that chains them can.
 */
object Har {

    /** HAR requires the fields Inspector's own config drops; this is an external format. */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    const val VERSION = "1.2"
    const val CREATOR = "Inspector"

    /** Reserved by the HAR spec for "this information is not available". */
    private const val UNKNOWN = -1L

    fun export(
        meta: SessionMeta?,
        transactions: List<NetworkTransaction>,
        bodyOf: (txnId: String, side: String) -> ByteArray?,
        creatorVersion: String,
    ): String {
        val entries = transactions
            // Chronological, by the device's monotonic clock — the same ordering authority the
            // rest of the archive uses, and the order a HAR viewer expects to be handed.
            .sortedBy { it.mono }
            .map { entryFor(it, bodyOf) }

        return json.encodeToString(
            HarFile.serializer(),
            HarFile(
                log = HarLog(
                    version = VERSION,
                    creator = HarCreator(CREATOR, creatorVersion),
                    comment = meta?.let {
                        "Inspector session ${it.sessionId} — ${it.appId} ${it.appVersion} " +
                            "on ${it.platform} (${it.device}, ${it.osVersion}), ${it.buildType} build"
                    },
                    pages = emptyList(),
                    entries = entries,
                ),
            ),
        )
    }

    private fun entryFor(
        txn: NetworkTransaction,
        bodyOf: (txnId: String, side: String) -> ByteArray?,
    ): HarEntry {
        val reqBody = bodyOf(txn.id, "req")
        val resBody = bodyOf(txn.id, "res")

        val notes = buildList {
            if (txn.attempt > 1) add("attempt ${txn.attempt} of call ${txn.callId}")
            txn.error?.let { add("transport failure: $it") }
            if (txn.reqBodyTruncated) add("request body truncated by the capture cap")
            if (txn.resBodyTruncated) add("response body truncated by the capture cap")
            txn.reqBodyOmitted?.let { add("request body not captured: $it") }
            txn.resBodyOmitted?.let { add("response body not captured: $it") }
            if (txn.redacted.isNotEmpty()) add("redacted at capture: ${txn.redacted.joinToString(", ")}")
        }

        return HarEntry(
            startedDateTime = txn.ts,
            // The one timing that was measured. A call still in flight has none.
            time = txn.ms ?: UNKNOWN,
            request = HarRequest(
                method = txn.method,
                url = txn.url,
                httpVersion = "",
                headers = headersOf(txn.reqHeaders),
                queryString = queryOf(txn.query),
                postData = postDataOf(txn, reqBody),
                headersSize = UNKNOWN,
                bodySize = txn.reqBytes,
            ),
            response = HarResponse(
                // A transport failure never produced a status. HAR uses 0 for "no response",
                // which is what a viewer renders as a failed request rather than as a 200.
                status = txn.status ?: 0,
                statusText = txn.error?.let { "(no response)" } ?: "",
                httpVersion = "",
                headers = headersOf(txn.resHeaders),
                content = contentOf(txn, resBody),
                redirectURL = txn.resHeaders.entries
                    .firstOrNull { it.key.equals("location", ignoreCase = true) }
                    ?.value?.firstOrNull()
                    .orEmpty(),
                headersSize = UNKNOWN,
                bodySize = txn.resBytes,
            ),
            cache = HarCache(),
            timings = HarTimings(send = UNKNOWN, wait = UNKNOWN, receive = UNKNOWN),
            comment = notes.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun headersOf(headers: Map<String, List<String>>): List<HarNameValue> =
        headers.flatMap { (name, values) -> values.map { HarNameValue(name, it) } }

    /**
     * Query parameters, split but not decoded.
     *
     * `query` is stored post-redaction, so a redacted value arrives here as its placeholder and
     * travels into the export as one — which is the point. A parameter with no `=` keeps an empty
     * value rather than being dropped, because `?debug` is a parameter.
     */
    private fun queryOf(query: String?): List<HarNameValue> =
        query.orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val at = pair.indexOf('=')
                if (at < 0) HarNameValue(pair, "") else HarNameValue(pair.take(at), pair.substring(at + 1))
            }

    /**
     * HAR's `postData.text` is a string and the format offers no base64 escape hatch for it,
     * unlike `response.content`. A binary request body therefore cannot be represented, so it is
     * reported as absent with the reason in the entry comment rather than mangled into replacement
     * characters that would look like a captured body.
     */
    private fun postDataOf(txn: NetworkTransaction, body: ByteArray?): HarPostData? {
        if (body == null || body.isEmpty()) return null
        val text = body.decodeUtf8OrNull() ?: return HarPostData(
            mimeType = txn.reqContentType.orEmpty(),
            text = "",
            comment = "binary request body of ${body.size} bytes omitted — HAR postData has no base64 encoding",
        )
        return HarPostData(mimeType = txn.reqContentType.orEmpty(), text = text)
    }

    private fun contentOf(txn: NetworkTransaction, body: ByteArray?): HarContent {
        val mime = txn.resContentType.orEmpty()
        if (body == null) {
            return HarContent(
                size = txn.resBytes,
                mimeType = mime,
                comment = txn.resBodyOmitted?.let { "not captured: $it" },
            )
        }
        val text = body.decodeUtf8OrNull()
        return if (text != null) {
            HarContent(size = txn.resBytes, mimeType = mime, text = text)
        } else {
            // The spec's own mechanism for a body that is not text. `size` stays the true byte
            // count, not the length of the base64.
            HarContent(
                size = txn.resBytes,
                mimeType = mime,
                text = Base64.getEncoder().encodeToString(body),
                encoding = "base64",
            )
        }
    }

    /**
     * Decodes strictly, returning null for anything that is not valid UTF-8.
     *
     * `String(bytes)` would never fail: it substitutes U+FFFD, so a PNG would arrive as a page of
     * replacement characters presented as the response body. The point of failing is to reach the
     * base64 branch instead.
     */
    private fun ByteArray.decodeUtf8OrNull(): String? = runCatching {
        java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()
}

@Serializable
data class HarFile(val log: HarLog)

@Serializable
data class HarLog(
    val version: String,
    val creator: HarCreator,
    val pages: List<HarPage> = emptyList(),
    val entries: List<HarEntry>,
    val comment: String? = null,
)

@Serializable
data class HarCreator(val name: String, val version: String)

@Serializable
data class HarPage(
    val startedDateTime: String,
    val id: String,
    val title: String,
    val pageTimings: Map<String, Long> = emptyMap(),
)

@Serializable
data class HarEntry(
    val startedDateTime: String,
    val time: Long,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache,
    val timings: HarTimings,
    val comment: String? = null,
)

@Serializable
data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String,
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val queryString: List<HarNameValue>,
    val postData: HarPostData? = null,
    val headersSize: Long,
    val bodySize: Long,
)

@Serializable
data class HarResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String,
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val content: HarContent,
    val redirectURL: String,
    val headersSize: Long,
    val bodySize: Long,
)

@Serializable
data class HarNameValue(val name: String, val value: String)

@Serializable
data class HarPostData(
    val mimeType: String,
    val text: String,
    val params: List<HarNameValue> = emptyList(),
    val comment: String? = null,
)

@Serializable
data class HarContent(
    val size: Long,
    val mimeType: String,
    val text: String? = null,
    val encoding: String? = null,
    val comment: String? = null,
)

@Serializable
class HarCache

@Serializable
data class HarTimings(
    val send: Long,
    val wait: Long,
    val receive: Long,
    @SerialName("blocked") val blocked: Long = -1,
    @SerialName("dns") val dns: Long = -1,
    @SerialName("connect") val connect: Long = -1,
    @SerialName("ssl") val ssl: Long = -1,
)
