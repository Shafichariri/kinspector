package dev.inspector

import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction

/**
 * Mirror of `:inspector-core`'s config so consuming code that constructs one keeps compiling
 * when the noop artifact is swapped in. Nothing here is read — the defaults exist only to keep
 * the constructor signature identical.
 */
data class InspectorConfig(
    val ringBufferMaxBytes: Long = 8L * 1024 * 1024,
    val bodyCaptureMaxBytes: Int = 256 * 1024,
    val captureContentTypes: List<String> = listOf(
        "application/json",
        "text/",
        "application/xml",
        "application/x-www-form-urlencoded",
        "application/problem+json",
        "application/graphql",
        "application/x-ndjson",
        "application/javascript",
        "application/jwt",
    ),
    val captureAllBodies: Boolean = false,
    val redaction: Redaction = Redaction.Off,
)

/** Mirror of `:inspector-core`'s redaction policy. Nothing is ever captured, so nothing is stripped. */
sealed interface Redaction {

    data object Off : Redaction

    data class On(
        val headers: List<String> = DEFAULT_HEADERS,
        val bodyKeyPattern: String = DEFAULT_BODY_KEY_PATTERN,
        val queryKeyPattern: String = DEFAULT_QUERY_KEY_PATTERN,
    ) : Redaction {
        val bodyKeys: Regex by lazy { Regex(bodyKeyPattern, RegexOption.IGNORE_CASE) }
        val queryKeys: Regex by lazy { Regex(queryKeyPattern, RegexOption.IGNORE_CASE) }
    }

    companion object {
        val DEFAULT_HEADERS: List<String> = listOf(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "proxy-authorization",
        )

        const val DEFAULT_BODY_KEY_PATTERN: String =
            "(token|secret|password|passwd|api[-_]?key|authorization|bearer|session[-_]?id)"

        const val DEFAULT_QUERY_KEY_PATTERN: String = "(token|key|secret|password)"

        const val PLACEHOLDER: String = "‹redacted›"
    }
}

/** Mirror of `:inspector-core`'s sink interface. Nothing is ever delivered to it. */
interface InspectorSink {
    fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?)
    fun onMarker(marker: Marker)
}
