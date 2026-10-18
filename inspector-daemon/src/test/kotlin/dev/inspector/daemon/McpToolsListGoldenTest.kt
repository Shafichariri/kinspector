package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.mcp.McpServer
import dev.inspector.daemon.mcp.McpTools
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `tools/list` reply, byte for byte, against `src/test/resources/mcp-tools-list.json`.
 *
 * Tool names, descriptions and schemas are what an agent reads to decide which tool to call and
 * how, so a reworded description is a behaviour change to the agent even when every test of what
 * the tools *do* still passes. Splitting `McpTools.kt` moves every descriptor; this is what makes
 * "no description changed" a check rather than a promise. The order is part of the reply and is
 * held too.
 *
 * A deliberate change regenerates the file, and the diff of the golden file is the review:
 *
 *   ./gradlew :inspector-daemon:test --tests '*McpToolsListGoldenTest' -Dinspector.mcp.regenerate=true
 */
class McpToolsListGoldenTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-mcp-golden")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** One `tools/list` exchange through a real [McpServer]; the reply frame exactly as printed. */
    private fun toolsListReply(): String {
        val config = DaemonConfig(port = 8099, dataDir = tmp)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        McpServer(
            tools = McpTools(config),
            input = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".reader().buffered(),
            output = PrintStream(out, true, Charsets.UTF_8),
            log = PrintStream(err, true, Charsets.UTF_8),
        ).run()
        val frames = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        assertEquals(1, frames.size, "expected one reply frame, got ${frames.size}; stderr: $err")
        return frames.single() + "\n"
    }

    @Test
    fun `the tools list reply is byte identical to the golden file`() {
        val actual = toolsListReply()
        if (System.getProperty("inspector.mcp.regenerate") == "true") {
            // Relative to the module, which is the test JVM's working directory under Gradle.
            File(GOLDEN_SOURCE).writeText(actual, Charsets.UTF_8)
            println("Regenerated $GOLDEN_SOURCE")
            return
        }
        val golden = assertNotNull(
            McpToolsListGoldenTest::class.java.getResource("/$GOLDEN_RESOURCE"),
            "missing $GOLDEN_SOURCE; generate it with -Dinspector.mcp.regenerate=true",
        ).readText(Charsets.UTF_8)
        // A golden file holding no tools would hold every later reply to nothing.
        assertTrue(golden.contains("\"list_sessions\""), "$GOLDEN_SOURCE lists no tools")
        assertEquals(golden, actual, "tools/list changed; if that was intended, regenerate $GOLDEN_SOURCE")
    }

    private companion object {
        const val GOLDEN_RESOURCE = "mcp-tools-list.json"
        const val GOLDEN_SOURCE = "src/test/resources/$GOLDEN_RESOURCE"
    }
}
