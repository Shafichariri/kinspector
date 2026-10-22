package dev.inspector.daemon.mcp

import dev.inspector.daemon.LONG_SESSION_CALLS
import dev.inspector.daemon.LONG_SESSION_ID
import dev.inspector.daemon.PROBE_SESSION_ID
import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.ipv6BothClients
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.mcpCallTool
import dev.inspector.daemon.mcpExchange
import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.daemon.service.StatsSort
import dev.inspector.daemon.txn
import dev.inspector.daemon.writeProbeFixture
import dev.inspector.daemon.writeSession
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
import kotlin.test.assertTrue

/**
 * `route_stats`: the service's report through real JSON-RPC frames, and its failures as results an
 * agent can correct itself from. In its own file, as each tool group's tests are.
 */
class RouteStatsToolTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var tools: McpTools

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-route-stats-tool")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = 8099, dataDir = tmp)
        tools = McpTools(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun report(args: String): RouteStatsReport =
        InspectorJson.decodeFromString(RouteStatsReport.serializer(), mcpCallTool(tools, "route_stats", args))

    /** The `isError` text of a call that must fail. */
    private fun failure(args: String): String {
        val result = mcpExchange(
            tools,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"route_stats","arguments":$args}}""",
        ).single().getValue("result").jsonObject
        assertEquals("true", result.getValue("isError").jsonPrimitive.content, result.toString())
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    @Test
    fun `route_stats is listed after the transaction tools`() {
        val names = tools.descriptors().map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(names.indexOf("get_body") + 1, names.indexOf("route_stats"), names.toString())
    }

    @Test
    fun `it returns the service's report for the same arguments`() {
        val service = RouteStatsService(SessionRepository(config), config.routeTemplates)
        val dir = SessionRepository(config).resolve(PROBE_SESSION_ID)!!
        assertEquals(
            service.report(dir, "status>=500", StatsSort.P90, 1),
            report("""{"session":"$PROBE_SESSION_ID","filter":"status>=500","sort":"p90","limit":1}"""),
        )
        assertEquals(service.report(dir, "", StatsSort.ATTEMPTS, 25), report("{}"), "latest, unfiltered, by attempts, 25")
    }

    @Test
    fun `the default limit is 25 and routeCount says how many there are`() {
        val id = "2026-03-03T10-00-00_wide_ci_debug"
        writeSession(config, id, "2026-03-03T10:00:00.000Z", (0 until 30).map { txn("%08x".format(it), path = "/route$it") })
        val wide = report("""{"session":"$id"}""")
        assertEquals(25, wide.routes.size)
        assertEquals(30, wide.routeCount)
        assertEquals(30, report("""{"session":"$id","limit":"500"}""").routes.size, "capped at 200, not refused")
    }

    @Test
    fun `the long session is counted whole`() {
        val long = report("""{"session":"$LONG_SESSION_ID"}""")
        assertEquals(LONG_SESSION_CALLS, long.matched)
        assertEquals(LONG_SESSION_CALLS - 2, long.routes.single { it.route == "/v3/long/bulk" }.attempts)
    }

    // An agent asking how one IPv6 server behaves gets one answer, not two halves, whichever
    // client each call went through.
    @Test
    fun `one IPv6 server reached by both clients is one route`() {
        val id = "2026-03-04T10-00-00_ipv6_ci_debug"
        writeSession(config, id, "2026-03-04T10:00:00.000Z", ipv6BothClients())
        val routes = report("""{"session":"$id"}""").routes
        assertEquals(listOf(Triple("::1", 8080, 6), Triple("::1", 9090, 1)), routes.map { Triple(it.host, it.port, it.attempts) })
    }

    @Test
    fun `a bad filter, an unknown sort and a misnamed session are results with the REST messages`() {
        assertEquals("'status' expects a number, got 'x'. Try 'status>=400'.", failure("""{"filter":"status>=x"}"""))
        assertEquals("unknown sort 'p75'. Use one of: attempts, p50, p90, p95, p99, max, errors.", failure("""{"sort":"p75"}"""))
        val misnamed = failure("""{"sessionId":"$PROBE_SESSION_ID"}""")
        assertContains(misnamed, "Did you mean 'session'?")
        assertTrue(failure("""{"session":"nope"}""").startsWith("no session 'nope'"))
    }
}
