package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.mcp.McpServer
import dev.inspector.daemon.mcp.McpTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse

// Shared by the MCP test files, so a tool's tests can live beside its own concerns without each
// file growing a second copy of the frame plumbing — a copy is how two harnesses stop agreeing.

/** Feeds lines through a real [McpServer] and returns the response frames. */
internal fun mcpExchange(tools: McpTools, vararg requests: String, version: String? = null): List<JsonObject> {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val input = requests.joinToString("\n").reader().buffered()
    val server = if (version == null) {
        McpServer(tools = tools, input = input, output = PrintStream(out, true), log = PrintStream(err, true))
    } else {
        McpServer(tools = tools, input = input, output = PrintStream(out, true), log = PrintStream(err, true), version = version)
    }
    server.run()
    return out.toString().lineSequence()
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }
        .toList()
}

/** Calls one tool through real JSON-RPC frames and returns its text; fails if the tool did. */
internal fun mcpCallTool(tools: McpTools, name: String, args: String = "{}"): String {
    val frames = mcpExchange(
        tools,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""",
    )
    val result = frames.single().getValue("result").jsonObject
    assertFalse(
        result.getValue("isError").jsonPrimitive.content.toBoolean(),
        "tool $name failed: ${result.getValue("content").jsonArray}",
    )
    return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
}

/** Writes a session shaped by [build], so a test can set up the exact case it is about. */
internal fun mcpSession(config: DaemonConfig, id: String, build: (SessionWriter) -> Unit): String {
    val dir = config.sessionsDir.resolve(id)
    val writer = SessionWriter(dir, clientInfo().toSessionMeta(id, "2026-08-17T09:00:00.000Z"))
    build(writer)
    writer.close()
    return id
}
