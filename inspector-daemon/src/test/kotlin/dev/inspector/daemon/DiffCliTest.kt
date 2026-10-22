package dev.inspector.daemon

import dev.inspector.daemon.service.DiffReport
import dev.inspector.model.InspectorJson
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `inspector diff`, reading the archive off disk as `stats` does: the report, its flags, and a failure's exit. */
class DiffCliTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-diff-cli")
        writeProbeFixture(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** Runs the CLI's own `main` by reflection, as `StatsCliTest` does, and returns what it printed. */
    private fun run(vararg args: String): String {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            Class.forName("dev.inspector.daemon.MainKt")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, arrayOf("diff", *args, "--data", tmp.toString()))
        } finally {
            System.setOut(original)
        }
        return out.toString()
    }

    private fun report(vararg args: String): DiffReport = InspectorJson.decodeFromString(DiffReport.serializer(), run(*args))

    @Test
    fun `it prints the report for the sessions named, changes only unless asked`() {
        val report = report("--a", BASE_SESSION_ID, "--b", PROBE_SESSION_ID)
        assertEquals(BASE_SESSION_ID to PROBE_SESSION_ID, report.a.sessionId to report.b.sessionId)
        assertEquals("/v3/shop/orders/submit", report.routes.single { it.loud && it.presence == "both" }.route)
        assertTrue(report.routes.all { it.changes.isNotEmpty() })
        assertTrue(report("--a", BASE_SESSION_ID, "--unchanged").routes.any { it.changes.isEmpty() })
        assertEquals(1, report("--a", BASE_SESSION_ID, "--limit", "1").routes.size)
    }

    @Test
    fun `the defaults, a filter and a since cut are honoured`() {
        val latest = report()
        assertEquals(LONG_SESSION_ID to PROBE_SESSION_ID, latest.a.sessionId to latest.b.sessionId)
        assertEquals(1201, latest.routes.single { it.route == "/v3/long/bulk" }.a!!.calls)

        val filtered = report("host:auth", "--a", BASE_SESSION_ID)
        assertEquals("host:auth", filtered.filter)
        assertEquals(listOf("/oauth/token"), filtered.routes.map { it.route })

        val cut = report("--a", BASE_SESSION_ID, "--since-b", "tapped checkout", "--unchanged")
        assertEquals("tapped checkout", cut.b.since)
        assertTrue(cut.b.matched < cut.b.total)
    }

    @Test
    fun `--md prints the Markdown form of the same report`() {
        val text = run("--a", BASE_SESSION_ID, "--md")
        assertTrue(text.startsWith("# Session diff: $PROBE_SESSION_ID against $BASE_SESSION_ID\n"), text.take(200))
        assertTrue("## Changed routes" in text)
    }

    @Test
    fun `an unknown session exits 1 with the message on stderr`() {
        // In a child JVM on this test's classpath, because the CLI ends the process on a failure.
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "dev.inspector.daemon.MainKt",
            "diff", "--a", "nope", "--data", tmp.toString(),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val stderr = process.errorStream.bufferedReader().readText()
        assertEquals(1, process.waitFor())
        assertEquals("inspector: no session 'nope' (a)", stderr.trim())
    }
}
