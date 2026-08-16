package dev.inspector.internal

import dev.inspector.Redaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Applies a [Redaction] policy at capture time.
 *
 * With [Redaction.Off] every entry point returns its input unchanged and allocates nothing —
 * redaction being the default-off case, it must not cost anything when disabled.
 *
 * Every removal is recorded so consumers can report "this was redacted" rather than "this was
 * absent"; an agent told nothing will otherwise state the request carried no credentials.
 */
internal class Redactor(private val redaction: Redaction) {

    val enabled: Boolean get() = redaction is Redaction.On
    private val on: Redaction.On? = redaction as? Redaction.On

    private val json = Json { prettyPrint = false; isLenient = true }

    fun headers(headers: Map<String, List<String>>, hits: MutableList<String>): Map<String, List<String>> {
        val policy = on ?: return headers
        if (headers.isEmpty()) return headers

        var changed = false
        val result = LinkedHashMap<String, List<String>>(headers.size)
        for ((name, values) in headers) {
            if (policy.headers.any { it.equals(name, ignoreCase = true) }) {
                result[name] = values.map { Redaction.PLACEHOLDER }
                hits += "header:${name.lowercase()}"
                changed = true
            } else {
                result[name] = values
            }
        }
        return if (changed) result else headers
    }

    /** Rewrites matching query parameter values in place, preserving order and encoding. */
    fun query(query: String?, hits: MutableList<String>): String? {
        val policy = on ?: return query
        if (query.isNullOrEmpty()) return query

        var changed = false
        val rebuilt = query.split('&').joinToString("&") { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) return@joinToString pair
            val key = pair.substring(0, index)
            if (policy.queryKeys.containsMatchIn(key)) {
                changed = true
                hits += "query:${key.lowercase()}"
                "$key=${Redaction.PLACEHOLDER}"
            } else {
                pair
            }
        }
        return if (changed) rebuilt else query
    }

    /**
     * Redacts matching keys in a JSON body.
     *
     * Structured parsing when the body is valid JSON, so nested objects and arrays are covered;
     * a regex pass otherwise. Redaction failing must never fail capture — a body we cannot parse
     * is still a body worth showing.
     */
    fun body(bytes: ByteArray?, contentType: String?, side: String, hits: MutableList<String>): ByteArray? {
        val policy = on ?: return bytes
        if (bytes == null || bytes.isEmpty()) return bytes
        if (contentType?.contains("json", ignoreCase = true) != true) return bytes

        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return bytes

        val structured = runCatching {
            val element = json.parseToJsonElement(text)
            val found = mutableListOf<String>()
            val redacted = redactElement(element, policy.bodyKeys, "$", found)
            if (found.isEmpty()) null else json.encodeToString(JsonElement.serializer(), redacted) to found
        }.getOrNull()

        if (structured != null) {
            hits += structured.second.map { "body:$side:$it" }
            return structured.first.encodeToByteArray()
        }

        // Fallback for malformed JSON: only touch clearly delimited "key": "value" pairs.
        val found = mutableListOf<String>()
        val rewritten = FALLBACK_PAIR.replace(text) { match ->
            val key = match.groupValues[1]
            if (policy.bodyKeys.containsMatchIn(key)) {
                found += "$.$key"
                "\"$key\":\"${Redaction.PLACEHOLDER}\""
            } else {
                match.value
            }
        }
        if (found.isEmpty()) return bytes
        hits += found.map { "body:$side:$it" }
        return rewritten.encodeToByteArray()
    }

    private fun redactElement(
        element: JsonElement,
        keys: Regex,
        path: String,
        found: MutableList<String>,
    ): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((key, value) in element) {
                val childPath = "$path.$key"
                if (keys.containsMatchIn(key)) {
                    found += childPath
                    put(key, JsonPrimitive(Redaction.PLACEHOLDER))
                } else {
                    put(key, redactElement(value, keys, childPath, found))
                }
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEachIndexed { index, value ->
                add(redactElement(value, keys, "$path[$index]", found))
            }
        }

        else -> element
    }

    private companion object {
        val FALLBACK_PAIR = Regex("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*\"[^\"]*\"")
    }
}
