package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.mcpCallTool
import dev.inspector.daemon.mcpSession
import dev.inspector.daemon.txn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `explain` tool's own cases, driven through real JSON-RPC frames like [TransactionToolsTest].
 *
 * Each case writes the one session it is about, so this file needs none of [McpToolsFixture]'s
 * shared session — only its frame plumbing, which lives in `McpTestSupport.kt` rather than being copied.
 */
class ExplainToolTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var tools: McpTools

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-mcp-explain")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        // Refusing fakes rather than the HTTP defaults: explain only reads, and a test that did
        // reach for the daemon must fail here rather than talk to whatever is on 8099.
        val noPoster = object : MarkerPoster {
            override fun post(session: String, label: String): ToolOutcome = error("explain never posts a marker")
        }
        val noPuller = object : SignalPuller {
            override fun pull(session: String, tag: String, name: String): ToolOutcome = error("explain never pulls")
        }
        tools = McpTools(config, SessionRepository(config), noPoster, noPuller)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun callTool(name: String, args: String = "{}"): String = mcpCallTool(tools, name, args)

    private fun session(id: String, build: (SessionWriter) -> Unit): String = mcpSession(config, id, build)

    @Test
    fun explain_reports_the_full_url_with_port_and_query() {
        // A hand-built scheme://host/path dropped both, which is the port defect capture already
        // fixed once: an agent handed that URL would call a different address.
        val row = txn("port0001", path = "/v2/users/me").copy(port = 8080, query = "a=1")
        val id = session("2026-08-17T09-00-09_port_dev_debug") { writer -> writer.append(row, null, null) }

        val text = callTool("explain", """{"session":"$id","id":"port0001"}""")
        val url = Json.parseToJsonElement(text).jsonObject
            .getValue("call").jsonObject.getValue("url").jsonPrimitive.content

        assertEquals("https://api.example.com:8080/v2/users/me?a=1", row.url)
        assertEquals(row.url, url)
    }
}
