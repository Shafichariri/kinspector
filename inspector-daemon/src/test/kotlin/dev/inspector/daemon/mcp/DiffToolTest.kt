package dev.inspector.daemon.mcp

import dev.inspector.daemon.BASE_SESSION_ID
import dev.inspector.daemon.LONG_SESSION_ID
import dev.inspector.daemon.PROBE_SESSION_ID
import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.mcpCallTool
import dev.inspector.daemon.mcpExchange
import dev.inspector.daemon.service.DiffOutcome
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRequest
import dev.inspector.daemon.service.SessionDiffService
import dev.inspector.daemon.writeProbeFixture
import dev.inspector.model.InspectorJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `diff_sessions`: the service's report through real JSON-RPC frames, its defaults, and its failures
 * as results with the words REST uses.
 */
class DiffToolTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var tools: McpTools

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-diff-tool")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = 8099, dataDir = tmp)
        tools = McpTools(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun report(args: String): DiffReport =
        InspectorJson.decodeFromString(DiffReport.serializer(), mcpCallTool(tools, "diff_sessions", args))

    private fun direct(request: DiffRequest): DiffReport {
        val repository = SessionRepository(config)
        return assertIs<DiffOutcome.Ok>(SessionDiffService(repository, config.routeTemplates).diff(request)).report
    }

    /** The `isError` text of a call that must fail. */
    private fun failure(args: String): String {
        val result = mcpExchange(
            tools,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"diff_sessions","arguments":$args}}""",
        ).single().getValue("result").jsonObject
        assertEquals("true", result.getValue("isError").jsonPrimitive.content, result.toString())
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    @Test
    fun `diff_sessions is listed after route_stats`() {
        val names = tools.descriptors().map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(names.indexOf("route_stats") + 1, names.indexOf("diff_sessions"), names.toString())
    }

    @Test
    fun `it returns the service's report, changes only and 50 routes by default`() {
        val args = """{"a":"$BASE_SESSION_ID","b":"$PROBE_SESSION_ID"}"""
        assertEquals(direct(DiffRequest(a = BASE_SESSION_ID, b = PROBE_SESSION_ID, limit = 50, unchanged = false)), report(args))
        assertTrue(report(args).routes.all { it.changes.isNotEmpty() }, "an unchanged route by default")

        val all = report("""{"a":"$BASE_SESSION_ID","unchanged":"true","limit":"2"}""")
        assertEquals(2, all.routes.size)
        assertEquals(all.routeCount, direct(DiffRequest(a = BASE_SESSION_ID)).routeCount)
        assertTrue(report("""{"a":"$BASE_SESSION_ID","unchanged":true}""").routes.any { it.changes.isEmpty() })
    }

    @Test
    fun `the long session is read whole`() {
        val bulk = report("""{"a":"$LONG_SESSION_ID","b":"$PROBE_SESSION_ID"}""").routes.single { it.route == "/v3/long/bulk" }
        assertEquals(1201 to 1201, bulk.a!!.calls to bulk.a!!.attempts)
        assertEquals("previousRun", report("{}").aChosen)
    }

    @Test
    fun `each failure is a result with the words REST uses`() {
        assertEquals("no session 'nope' (a)", failure("""{"a":"nope"}"""))
        assertEquals("no session 'nope' (b)", failure("""{"b":"nope"}"""))
        assertEquals(
            "no earlier session of app 'com.example.projectx' before '$BASE_SESSION_ID'; name one with a=",
            failure("""{"b":"$BASE_SESSION_ID"}"""),
        )
        assertEquals("a and b are the same session '$PROBE_SESSION_ID'", failure("""{"a":"latest"}"""))
        assertEquals("session B has no marker 'nope'. Its markers: launch, tapped checkout.", failure("""{"sinceB":"nope"}"""))
        assertTrue(failure("""{"filter":"status>>"}""").isNotBlank())
    }

    @Test
    fun `session and sessionId are refused with the arguments it takes`() {
        for (name in listOf("session", "sessionId")) {
            val text = failure("""{"$name":"$PROBE_SESSION_ID"}""")
            assertContains(text, "unknown argument for 'diff_sessions': $name.")
            assertContains(text, "Accepted: a, b, filter, limit, sinceA, sinceB, unchanged.")
        }
    }
}
