package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One MCP tool: what `tools/list` says about it, and what `tools/call` runs. */
internal class Tool(val descriptor: JsonObject, val run: (JsonObject) -> ToolOutcome)

/** A file's worth of tools, listed in the order `tools/list` reports them. */
internal interface ToolGroup {
    val tools: List<Tool>
}

/**
 * What every archive-reading tool shares: the archive, and how a `session` argument resolves.
 *
 * A base class rather than a field, so the tool functions read `repository` and `resolve(args)`
 * exactly as they did when every tool lived in [McpTools].
 */
internal abstract class ToolContext(val config: DaemonConfig, val repository: SessionRepository) {

    fun resolve(args: JsonObject) = repository.resolve(args.string("session") ?: "latest")

    fun noSuchSession(args: JsonObject): ToolOutcome {
        val requested = args.string("session") ?: "latest"
        val known = repository.listSessions().take(5).joinToString(", ") { it.sessionId }
        return ToolOutcome.Failed(
            if (known.isEmpty()) "no session '$requested'; the archive is empty"
            else "no session '$requested'. Known sessions: $known"
        )
    }
}

// --- schema and argument plumbing ------------------------------------------------------------

internal fun tool(
    name: String,
    description: String,
    required: List<String> = emptyList(),
    properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put(
        "inputSchema",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject(properties))
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        },
    )
}

internal fun sessionProperty() = stringProperty(
    "Session id, or 'latest' for the most recent. Defaults to 'latest'."
)

internal fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it !is kotlinx.serialization.json.JsonNull }
        ?.content?.takeIf { it.isNotBlank() && it != "null" }

internal fun booleanProperty(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/** `true` or the string `"true"`, as agents send both; anything else is false. */
internal fun JsonObject.flag(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    return primitive.content.equals("true", ignoreCase = true)
}

/**
 * `true` or `false`, as a boolean or as a string, since agents send both, as they do numbers (see
 * [int]); absent, or anything else, is [default]. Unlike [flag], an absent argument can default to true.
 */
internal fun JsonObject.boolean(key: String, default: Boolean): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return default
    return when (primitive.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }
}

/** Agents routinely send numbers as strings, so accept both rather than failing the call. */
internal fun JsonObject.int(key: String, default: Int, min: Int, max: Int): Int {
    val primitive = this[key] as? JsonPrimitive ?: return default
    val value = primitive.intOrNull ?: primitive.content.toIntOrNull() ?: return default
    return value.coerceIn(min, max)
}

/** Absent means "no bound", so this is nullable rather than defaulted. Same string tolerance. */
internal fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}
