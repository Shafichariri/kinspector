package dev.inspector.daemon.service

import dev.inspector.daemon.archive.DaemonConfig
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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

class ProcessMcpControl internal constructor(
    private val config: DaemonConfig,
    /**
     * Starts the child in place of [commandLine], for tests only.
     *
     * Under Gradle the argv carries no `MainKt`, so without this the probe never spawns anything
     * and nothing can show that it stops what it spawns. It takes a launch rather than a command
     * because a test of the write failing needs the child to have closed its stdin before the
     * probe writes, and only the launch can wait for that. Kept off the public constructor, and so
     * off `InspectorDaemon`'s, which builds this from the config alone.
     */
    private val launch: (() -> Process)?,
) : McpControl {

    constructor(config: DaemonConfig) : this(config, launch = null)

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
        val start: () -> Process = launch ?: commandLine()
            ?.let { line -> { ProcessBuilder(line).redirectErrorStream(false).start() } }
            ?: return McpProbeResult(false, error = "this process cannot report its own command line")

        val process = try {
            start()
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

            val frames = readFrames(process)
            var serverName: String? = null
            var toolCount: Int? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val frame = frames.poll(left, TimeUnit.MILLISECONDS)?.orElse(null) ?: break
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
            // The only thing that stops the child. Closing its stdin is a request, and a wedged
            // server ignores it; one left running shows in the peer list as an `mcp` the user is
            // told not to kill. McpProbeCleanupTest holds this with a child that ignores EOF.
            process.destroyForcibly()
        }
    }

    /**
     * The child's stdout, line by line, read on a thread of its own.
     *
     * A `readLine` on the probe's thread cannot be bounded: a child that holds stdout open and
     * writes nothing blocks it for good, the deadline is never checked again, and the `finally`
     * that stops the child never runs. Polling this queue with the time left keeps the deadline
     * in the probe's hands. The reader is a daemon thread, and killing the child closes the pipe,
     * so it ends with the child rather than outliving the probe. An empty `Optional` is EOF.
     */
    private fun readFrames(process: Process): LinkedBlockingQueue<Optional<String>> {
        val frames = LinkedBlockingQueue<Optional<String>>()
        thread(isDaemon = true, name = "mcp-probe-reader-${process.pid()}") {
            try {
                process.inputStream.bufferedReader().forEachLine { frames.put(Optional.of(it)) }
            } catch (_: IOException) {
                // The pipe closed under the read because the probe killed the child.
            } finally {
                frames.put(Optional.empty())
            }
        }
        return frames
    }

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":""" +
                """"2024-11-05","capabilities":{},"clientInfo":{"name":"inspector-web","version":"1"}}}"""
        const val LIST_TOOLS = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    }
}
