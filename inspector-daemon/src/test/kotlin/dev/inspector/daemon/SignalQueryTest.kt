package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.mcp.MarkerPoster
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.mcp.SignalPuller
import dev.inspector.daemon.mcp.ToolOutcome
import dev.inspector.model.FilterParseException
import dev.inspector.model.FilterParser
import dev.inspector.model.SignalTags
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET …/signals` and MCP `list_signals` are two adapters over one rule,
 * `SessionRepository.querySignals`, and this file is what holds them to it.
 *
 * The route used to filter by `tag` with its own comparison, sort and clamp in its own body, and
 * ignore `filter` entirely — so a person and an agent asking about one session got different rows.
 * The comparison below asks both for the same filters and wants the same ids in the same order. Its
 * session is built to make a second copy of the rule show: `mono` ties written out of archive
 * order, so only a stable sort on `mono` agrees, and `ts` running against `mono`, so a sort by wall
 * clock disagrees everywhere.
 */
class SignalQueryTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient
    private lateinit var tools: McpTools

    private val mixed = "2026-08-16T10-14-02_mixed_dev_debug"
    private val large = "2026-08-16T11-00-00_large_dev_debug"

    @BeforeTest
    fun setUp() {
        runBlocking {
            tmp = Files.createTempDirectory("inspector-signal-query")
            config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
            Files.createDirectories(config.sessionsDir)
            writeMixedSession()
            writeLargeSession()

            // Refusing fakes: list_signals only reads, and a test that reached for a daemon on
            // 8099 must fail here rather than talk to whatever is running there.
            val noPoster = object : MarkerPoster {
                override fun post(session: String, label: String): ToolOutcome = error("never posts")
            }
            val noPuller = object : SignalPuller {
                override fun pull(session: String, tag: String, name: String): ToolOutcome = error("never pulls")
            }
            tools = McpTools(config, SessionRepository(config), noPoster, noPuller)

            daemon = InspectorDaemon(config)
            daemon.start(wait = false)
            http = HttpClient(CIO)
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get(url("/api/sessions")).status.value }.getOrNull() != 200) delay(50)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        daemon.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /**
     * Archive order is deliberately not `mono` order, two pairs tie on `mono` with other rows
     * written between them, and every `ts` runs backwards against `mono`.
     */
    private fun writeMixedSession() {
        // ts falls as mono rises, so ordering by wall clock reverses every comparison.
        fun ts(mono: Long) = "2026-08-16T10:%02d:00.000Z".format(59 - mono / 100)
        val rows = listOf(
            signal(id = "m300a", tag = SignalTags.SCREEN, name = "Route", mono = 300, ts = ts(300)),
            signal(id = "m100", tag = SignalTags.SCREEN, name = "Home", mono = 100, ts = ts(100)),
            signal(id = "m500", tag = SignalTags.CACHE, name = "portfolio", mono = 500, ts = ts(500)),
            signal(id = "m300b", tag = "bluetooth", name = "pairing", mono = 300, ts = ts(300)),
            signal(id = "m700a", tag = SignalTags.SCREEN, name = "Route", mono = 700, ts = ts(700)),
            signal(id = "m200", tag = SignalTags.CACHE, name = "response", mono = 200, ts = ts(200)),
            signal(id = "m700b", tag = SignalTags.CACHE, name = "pairings", mono = 700, ts = ts(700)),
            signal(id = "m900", tag = SignalTags.SCREEN, name = "Settings", mono = 900, ts = ts(900)),
            signal(id = "m600", tag = "vm", name = "CheckoutRoute", mono = 600, ts = ts(600)),
        )
        writeSession(
            config, mixed, "2026-08-16T10:00:00.000Z",
            transactions = listOf(
                txn("t1", path = "/v2/orders", status = 500, mono = 250),
                txn("t2", path = "/v2/users/me", status = 200, mono = 650),
            ),
            markers = listOf(marker("checkout", mono = 450)),
            signals = rows.map { it to null },
        )
    }

    /** 1100 screen and 105 cache signals, every `mono` distinct so "the newest 1000" is one set. */
    private fun writeLargeSession() {
        val rows = (1..1205).map { n ->
            val cache = n % 11 == 0 && n / 11 <= 105
            signal(
                id = "L%04d".format(n),
                tag = if (cache) SignalTags.CACHE else SignalTags.SCREEN,
                name = if (cache) "entry" else "Screen$n",
                mono = n.toLong() * 10,
            )
        }
        check(rows.count { it.tag == SignalTags.CACHE } == 105)
        writeSession(config, large, "2026-08-16T11:00:00.000Z", signals = rows.map { it to null })
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    private suspend fun route(session: String, vararg params: Pair<String, Any>): HttpResponse =
        http.get(url("/api/sessions/$session/signals")) {
            params.forEach { (k, v) -> parameter(k, v) }
        }

    private suspend fun routeIds(session: String, vararg params: Pair<String, Any>): List<String> {
        val response = route(session, *params)
        assertEquals(200, response.status.value, "route ${params.toList()}: ${response.bodyAsText()}")
        // The reply stays a bare array: the web UI and the export's archiveSource both read that shape.
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
    }

    private fun toolIds(session: String, filter: String): List<String> {
        // Built, not interpolated: two of the filters carry quotes.
        val args = buildJsonObject {
            put("session", session)
            put("filter", filter)
            put("limit", 200)
        }.toString()
        val page = Json.parseToJsonElement(mcpCallTool(tools, "list_signals", args)).jsonObject
        return page.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
    }

    @Test
    fun the_route_and_list_signals_return_the_same_ids_in_the_same_order() = runBlocking {
        val filters = listOf(
            "",
            "tag:screen",
            "name:Route",
            "text:pair",
            "since:marker(\"checkout\")",
            "path:/v2",
            "tag:screen | path:/v2",
            "status:500 | tag:cache",
        )
        for (filter in filters) {
            val fromTool = toolIds(mixed, filter)
            val fromRoute = routeIds(mixed, "filter" to filter)
            assertEquals(fromTool, fromRoute, "route and list_signals diverged on filter '$filter'")
        }
        // Pinned once by hand, so the comparison above cannot pass by both sides agreeing on
        // nonsense: newest first by mono, ties in archive order, ts ignored.
        assertEquals(
            listOf("m900", "m700a", "m700b", "m600", "m500", "m300a", "m300b", "m200", "m100"),
            routeIds(mixed),
        )
    }

    @Test
    fun a_session_over_the_clamp_pages_completely() = runBlocking {
        val first = routeIds(large, "limit" to 2000)
        assertEquals(1000, first.size, "the clamp stays 1..1000")
        assertEquals("L1205", first.first())
        assertEquals((1205 downTo 206).map { "L%04d".format(it) }, first, "the 1000 newest, newest first")

        val rest = routeIds(large, "offset" to 1000, "limit" to 1000)
        assertEquals(205, rest.size)
        assertEquals("L0001", rest.last(), "the oldest signal is reachable")
        assertEquals(1205, (first + rest).toSet().size, "no id twice across the pages")

        val screens = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = routeIds(large, "filter" to "tag:screen", "offset" to offset, "limit" to 1000)
            if (page.isEmpty()) break
            screens += page
            offset += page.size
        }
        assertEquals(1100, screens.size)
        assertEquals(1100, screens.toSet().size, "no screen twice")
    }

    @Test
    fun tag_is_a_constructed_term_and_ands_with_the_filter() = runBlocking {
        val screens = listOf("m900", "m700a", "m300a", "m100")
        assertEquals(screens, routeIds(mixed, "tag" to "screen"))
        assertEquals(screens, routeIds(mixed, "tag" to "SCREEN"), "the tag stays case-insensitive")
        assertEquals(listOf("m700a", "m300a"), routeIds(mixed, "tag" to "screen", "filter" to "name:Route"))
        // Pasted into the filter text this would read as `tag:screen | path:x` and return the
        // screens. As a term it is one tag nobody has.
        assertEquals(emptyList(), routeIds(mixed, "tag" to "screen | path:x"))
    }

    @Test
    fun a_bad_filter_is_a_400_carrying_the_parsers_message() = runBlocking {
        val expected = try {
            FilterParser.parseOrThrow("bogus:1")
            error("the parser accepted bogus:1")
        } catch (e: FilterParseException) {
            e.message!!
        }
        assertTrue(expected.startsWith("Unknown filter key 'bogus'"), expected)

        val response = route(mixed, "filter" to "bogus:1")
        assertEquals(400, response.status.value)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content
        assertEquals(expected, error)
    }
}
