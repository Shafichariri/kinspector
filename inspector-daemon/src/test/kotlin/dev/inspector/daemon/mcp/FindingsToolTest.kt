package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.getCall
import dev.inspector.daemon.mcpCallTool
import dev.inspector.daemon.mcpExchange
import dev.inspector.daemon.mutation
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.daemon.writeFindingsSession
import dev.inspector.daemon.writeProbeFixture
import dev.inspector.model.InspectorJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `list_findings`: the service's report through real JSON-RPC frames, the same one REST serves, and
 * its failures as results an agent can correct itself from.
 */
class FindingsToolTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var tools: McpTools

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-tool")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = 8099, dataDir = tmp)
        writeFindingsSession(
            config, "payments",
            listOf(
                mutation("b0000001", 1_000, "c-dup-a"),
                mutation("b0000002", 2_200, "c-dup-b"),
                getCall("b0000003", 3_000),
            ),
        )
        tools = McpTools(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun report(args: String): FindingsReport =
        InspectorJson.decodeFromString(FindingsReport.serializer(), mcpCallTool(tools, "list_findings", args))

    private fun failure(args: String): String {
        val result = mcpExchange(
            tools,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_findings","arguments":$args}}""",
        ).single().getValue("result").jsonObject
        assertTrue(result.getValue("isError").jsonPrimitive.content.toBoolean(), result.toString())
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    private fun direct(session: String, category: String?, limit: Int): FindingsReport {
        val repository = SessionRepository(config)
        val service = FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates))
        return service.report(repository.resolve(session)!!, category, limit)
    }

    @Test
    fun `it returns the report REST returns`() {
        val viaTool = report("""{"session":"payments"}""")
        assertEquals(direct("payments", null, 50), viaTool)
        assertEquals(listOf("payment.duplicate-mutation", "payment.key-missing-payment-path", "payment.key-missing-payment-path"), viaTool.findings.map { it.ruleId })
        assertEquals(3, viaTool.total)
    }

    @Test
    fun `category and limit are honoured, and latest is the default session`() {
        assertEquals("payment", report("""{"session":"payments","category":"payment"}""").category)
        val one = report("""{"session":"payments","limit":"1"}""")
        assertEquals(1, one.findings.size)
        assertEquals(3, one.findingCount)
        assertEquals(dev.inspector.daemon.PROBE_SESSION_ID, report("{}").sessionId)
    }

    @Test
    fun `an edit to the findings key reaches the next call with no restart`() {
        assertEquals(3, report("""{"session":"payments"}""").findingCount)
        tmp.resolve("config.json").writeText("""{"findings":{"rules":{"payment.key-missing-payment-path":{"enabled":false}}}}""")
        assertEquals(1, report("""{"session":"payments"}""").findingCount)
    }

    @Test
    fun `an unknown category, an unknown argument and an unknown session are results, not errors`() {
        assertEquals("unknown category 'pay'. Use one of: payment, sensitive.", failure("""{"session":"payments","category":"pay"}"""))
        assertContains(failure("""{"session":"payments","filter":"status:200"}"""), "unknown argument for 'list_findings': filter")
        assertContains(failure("""{"session":"nope"}"""), "no session 'nope'")
    }
}
