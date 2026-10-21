package dev.inspector.daemon

import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.model.InspectorJson
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `inspector stats`, reading the archive off disk as `query` does: the report, and its flags.
 *
 * The failures exit the process, so they are not run here; `StatsRouteTest` holds the same messages.
 */
class StatsCliTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-stats-cli")
        writeProbeFixture(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /**
     * Runs the CLI's own `main` and decodes what it printed. By reflection, because the probe
     * fixture declares a `main` of its own in this package, and a bare call would be ambiguous.
     */
    private fun stats(vararg args: String): RouteStatsReport {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            Class.forName("dev.inspector.daemon.MainKt")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, arrayOf("stats", *args, "--data", tmp.toString()))
        } finally {
            System.setOut(original)
        }
        return InspectorJson.decodeFromString(RouteStatsReport.serializer(), out.toString())
    }

    @Test
    fun `it prints the latest session's report with every row counted`() {
        val report = stats()
        assertEquals(PROBE_SESSION_ID, report.sessionId)
        assertEquals(16, report.matched)
        assertEquals(13, report.routes.size)
        assertEquals("attempts", report.sort)
    }

    @Test
    fun `sort, limit, session and a filter are honoured`() {
        val byP90 = stats("--sort", "p90")
        assertEquals("p90", byP90.sort)
        val p90s = byP90.routes.map { it.p90Ms ?: -1 }
        assertEquals(p90s.sortedDescending(), p90s)
        assertEquals("/v3/shop/promo", byP90.routes.last().route, "the 30 ms hop is the fastest")

        assertEquals(2, stats("--limit", "2").routes.size)
        assertEquals(LONG_SESSION_CALLS, stats("--session", LONG_SESSION_ID).matched)

        val filtered = stats("status>=500")
        assertEquals(4, filtered.matched)
        assertEquals("status>=500", filtered.filter)
    }
}
