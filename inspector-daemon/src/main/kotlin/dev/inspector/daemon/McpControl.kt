package dev.inspector.daemon

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * How to register the MCP server, and whether the binary actually answers.
 *
 * There is deliberately no "start" here. The MCP server speaks **stdio** — `McpServer.run` blocks
 * on `input.readLine()` and stops at EOF — so a process spawned by the daemon would have nobody on
 * the other end of its pipes and would exit immediately or hang holding an empty one. The editor
 * owns that lifecycle: it spawns the server when it connects and respawns it when it reconnects.
 *
 * So the two operations that mean something are: **prove it works** ([probe]), and **kill a wedged
 * one** so the editor spawns a fresh one — which goes through the ordinary peer kill.
 */
@Serializable
data class McpInfo(
    /** The command to register, ready to paste. */
    val command: String,
    val args: List<String>,
    /** Present when the friendlier launcher script could be located on disk. */
    val launcher: String? = null,
    val dataDir: String,
)

@Serializable
data class McpProbeResult(
    val ok: Boolean,
    val serverName: String? = null,
    val toolCount: Int? = null,
    val error: String? = null,
)

interface McpControl {
    fun info(): McpInfo

    /** Spawns a short-lived server, completes a handshake, and stops it. */
    fun probe(timeoutMs: Long = 15_000): McpProbeResult
}

class ProcessMcpControl(private val config: DaemonConfig) : McpControl {

    /**
     * This process's own launch, with the subcommand swapped for `mcp`.
     *
     * Derived from the running daemon rather than reconstructed, so the probe uses exactly the
     * classpath that is actually working — a hand-built command would be a different thing from
     * the one the editor will run.
     */
    private fun commandLine(): List<String>? {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null) ?: return null
        val arguments = info.arguments().orElse(null)?.toList() ?: return null
        val mainClassAt = arguments.indexOf(PeerArgv.MAIN_CLASS)
        if (mainClassAt < 0) return null
        // Everything up to and including the main class, then our own subcommand and data dir.
        return listOf(command) + arguments.take(mainClassAt + 1) +
            listOf("mcp", "--data", config.dataDir.toString())
    }

    /**
     * `.../install/inspector/bin/inspector`, when the daemon is running from an installDist layout.
     *
     * Checked on disk before being reported: a path that only looks right is worse than none,
     * because it gets pasted into an editor config and fails there instead of here.
     */
    private fun launcher(): String? {
        val classpath = ProcessHandle.current().info().arguments().orElse(null)
            ?.firstOrNull { it.contains("inspector-daemon") } ?: return null
        val jar = classpath.split(':').firstOrNull { it.contains("inspector-daemon") } ?: return null
        val script = Path.of(jar).parent?.parent?.resolve("bin")?.resolve("inspector") ?: return null
        return script.takeIf { it.exists() && Files.isExecutable(it) }?.toString()
    }

    override fun info(): McpInfo {
        val line = commandLine()
        return McpInfo(
            command = line?.firstOrNull() ?: "inspector",
            args = line?.drop(1) ?: listOf("mcp"),
            launcher = launcher(),
            dataDir = config.dataDir.toString(),
        )
    }

    override fun probe(timeoutMs: Long): McpProbeResult {
        val line = commandLine()
            ?: return McpProbeResult(false, error = "this process cannot report its own command line")

        val process = try {
            ProcessBuilder(line).redirectErrorStream(false).start()
        } catch (cause: Exception) {
            return McpProbeResult(false, error = "could not start it: ${cause.message}")
        }

        return try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(INITIALIZE); writer.newLine()
                writer.write(LIST_TOOLS); writer.newLine()
                writer.flush()
                // Closing stdin is the stop signal: readLine returns null at EOF and run() ends.
            }

            val reader = process.inputStream.bufferedReader()
            var serverName: String? = null
            var toolCount: Int? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val frame = reader.readLine() ?: break
                val parsed = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(frame)
                }.getOrNull() ?: continue
                val result = runCatching {
                    parsed.let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("result") as? kotlinx.serialization.json.JsonObject
                }.getOrNull() ?: continue

                (result["serverInfo"] as? kotlinx.serialization.json.JsonObject)?.let { server ->
                    serverName = (server["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
                (result["tools"] as? kotlinx.serialization.json.JsonArray)?.let { toolCount = it.size }
                if (toolCount != null) break
            }

            process.waitFor(2, TimeUnit.SECONDS)
            if (toolCount == null) {
                McpProbeResult(false, error = "it started but did not answer a tools/list within ${timeoutMs}ms")
            } else {
                McpProbeResult(true, serverName = serverName, toolCount = toolCount)
            }
        } catch (cause: Exception) {
            McpProbeResult(false, error = "${cause::class.simpleName}: ${cause.message}")
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":""" +
                """"2024-11-05","capabilities":{},"clientInfo":{"name":"inspector-web","version":"1"}}}"""
        const val LIST_TOOLS = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    }
}
