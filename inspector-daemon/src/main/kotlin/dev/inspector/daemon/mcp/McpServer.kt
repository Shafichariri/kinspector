package dev.inspector.daemon.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.PrintStream

/**
 * Model Context Protocol server over stdio, exposing the session archive to coding agents.
 *
 * Hand-rolled JSON-RPC rather than an SDK dependency: the wire format is a few dozen lines, and
 * this project is offline-only and deliberately thin on dependencies.
 *
 * **stdout carries protocol frames and nothing else.** Anything printed there that is not a
 * JSON-RPC message desynchronises the client, which is why every diagnostic goes to stderr.
 */
class McpServer(
    private val tools: McpTools,
    private val input: BufferedReader,
    private val output: PrintStream,
    private val log: PrintStream,
) {

    /** Reads until stdin closes. One JSON-RPC message per line. */
    fun run() {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue

            val request = runCatching { McpJson.parseToJsonElement(line).jsonObject }.getOrNull()
            if (request == null) {
                // No id is recoverable from unparseable input, so this is the one case where the
                // spec has us answer with a null id rather than stay silent.
                respond(errorFrame(JsonNull, PARSE_ERROR, "invalid JSON"))
                continue
            }

            val id = request["id"]
            val method = request["method"]?.jsonPrimitive?.contentOrNullSafe

            if (method == null) {
                id?.let { respond(errorFrame(it, INVALID_REQUEST, "missing method")) }
                continue
            }

            // Notifications carry no id and must never be answered — a response to one is a
            // protocol violation that some clients treat as fatal.
            val isNotification = id == null
            val result = runCatching { dispatch(method, request["params"]?.asObject()) }

            if (isNotification) {
                result.exceptionOrNull()?.let { log.println("inspector-mcp: $method failed: ${it.message}") }
                continue
            }

            result.fold(
                onSuccess = { payload ->
                    if (payload == null) {
                        respond(errorFrame(id, METHOD_NOT_FOUND, "unknown method '$method'"))
                    } else {
                        respond(
                            buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", id)
                                put("result", payload)
                            }
                        )
                    }
                },
                onFailure = { cause ->
                    log.println("inspector-mcp: $method failed: ${cause.message}")
                    respond(errorFrame(id, INTERNAL_ERROR, cause.message ?: cause::class.simpleName ?: "error"))
                },
            )
        }
    }

    /** Returns null for an unknown method; an empty object for handled notifications. */
    private fun dispatch(method: String, params: JsonObject?): JsonElement? = when (method) {
        "initialize" -> initialize(params)
        "notifications/initialized", "notifications/cancelled" -> JsonObject(emptyMap())
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> buildJsonObject { put("tools", tools.descriptors()) }
        "tools/call" -> callTool(params)
        else -> null
    }

    private fun initialize(params: JsonObject?): JsonElement {
        // Echo the client's protocol version when we know it, so a client pinned to an older
        // revision is not forced to renegotiate; otherwise state the newest we speak.
        val requested = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNullSafe
        val version = if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested!! else PROTOCOL_VERSION

        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "inspector")
                    put("version", SERVER_VERSION)
                },
            )
            put(
                "instructions",
                "Network traffic captured from debug builds of Compose Multiplatform apps. " +
                    "Start with session_summary — it answers most questions in about a kilobyte, " +
                    "without reading any rows. Narrow with list_transactions using the filter " +
                    "grammar, then get_body only for the specific call you care about. " +
                    "The session id 'latest' always resolves to the most recent session.",
            )
        }
    }

    private fun callTool(params: JsonObject?): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNullSafe
            ?: return toolResult("no tool name given", isError = true)
        val arguments = params["arguments"]?.asObject() ?: JsonObject(emptyMap())

        return when (val outcome = tools.call(name, arguments)) {
            is ToolOutcome.Ok -> toolResult(outcome.text)
            // Tool failures come back as results, not JSON-RPC errors: an agent can read and act
            // on "no such session", whereas a transport error just aborts its turn.
            is ToolOutcome.Failed -> toolResult(outcome.message, isError = true)
        }
    }

    private fun toolResult(text: String, isError: Boolean = false): JsonElement = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                )
            },
        )
        put("isError", isError)
    }

    private fun errorFrame(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    private fun respond(frame: JsonObject) {
        output.println(McpJson.encodeToString(JsonObject.serializer(), frame))
        output.flush()
    }

    private companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
    }
}

internal const val PROTOCOL_VERSION = "2025-06-18"
internal const val SERVER_VERSION = "1.0.0"

internal val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")

/** `jsonPrimitive.content` throws on JSON null; this yields null instead. */
private val JsonPrimitive.contentOrNullSafe: String?
    get() = if (this is JsonNull) null else content

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
