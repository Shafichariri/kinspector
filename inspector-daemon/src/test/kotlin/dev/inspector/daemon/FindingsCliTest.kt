package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `inspector findings`, reading the archive off disk as `stats` does: the report REST serves, its
 * flags, and a bad category's exit, with the message all three surfaces take from
 * `FindingsEngine.parseCategory`. Then `--fail-on`, the CI gate, by its exit code in a child JVM:
 * 1 for a finding at or above the threshold, 0 for none, 2 whenever it cannot judge.
 */
class FindingsCliTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-cli")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = 8099, dataDir = tmp)
        writeFindingsSession(
            config, "payments",
            listOf(mutation("b0000001", 1_000, "c-dup-a"), mutation("b0000002", 2_200, "c-dup-b")),
        )
        // A retry carrying the same key on both attempts: one info finding (the safe case), nothing higher.
        writeFindingsSession(
            config, "info-only",
            listOf(
                mutation("e0000001", 1_000, "c-retry-key", path = "/v1/orders", status = 503, key = "k-0001-aaaa-bbbb"),
                mutation("e0000002", 1_300, "c-retry-key", path = "/v1/orders", attempt = 2, key = "k-0001-aaaa-bbbb"),
            ),
        )
        writeFindingsSession(config, "empty", emptyList())
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** Runs the CLI's own `main` by reflection, as `StatsCliTest` does, and decodes what it printed. */
    private fun findings(vararg args: String): FindingsReport {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            Class.forName("dev.inspector.daemon.MainKt")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, arrayOf("findings", *args, "--data", tmp.toString()))
        } finally {
            System.setOut(original)
        }
        return InspectorJson.decodeFromString(FindingsReport.serializer(), out.toString())
    }

    private fun direct(session: String, category: String?, limit: Int): FindingsReport {
        val repository = SessionRepository(config)
        val service = FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates))
        return service.report(repository.resolve(session)!!, category, limit)
    }

    @Test
    fun `it prints the report REST serves`() {
        assertEquals(direct("payments", null, 1000), findings("--session", "payments"))
        // With no --session, the latest: the probe session, whose planted values are its findings.
        assertEquals(direct(PROBE_SESSION_ID, null, 1000), findings())
        assertEquals(PROBE_SESSION_ID, findings().sessionId)
    }

    @Test
    fun `category and limit are honoured`() {
        val narrowed = findings("--session", "payments", "--category", "payment")
        assertEquals("payment", narrowed.category)
        val one = findings("--session", "payments", "--limit", "1")
        assertEquals(1, one.findings.size)
        assertEquals(3, one.findingCount)
    }

    private class Run(val exit: Int, val stdout: String, val stderr: String)

    /** The CLI in a child JVM on this test's classpath, because it ends the process with its verdict. */
    private fun run(vararg args: String): Run {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val out = Files.createTempFile(tmp, "stdout", ".txt")
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "dev.inspector.daemon.MainKt",
            "findings", *args, "--data", tmp.toString(),
        ).redirectOutput(out.toFile()).start()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        return Run(exit, Files.readString(out), stderr)
    }

    private fun configure(json: String) = Files.writeString(tmp.resolve("config.json"), json)

    @Test
    fun `fail-on high exits 1 on a high finding and names it on stderr, with the report unchanged on stdout`() {
        val run = run("--session", "payments", "--fail-on", "high")
        assertEquals(1, run.exit, run.stderr)
        assertEquals(direct("payments", null, 1000), InspectorJson.decodeFromString(FindingsReport.serializer(), run.stdout))
        val lines = run.stderr.lines()
        assertTrue(lines.first().startsWith("inspector: 1 finding at or above high in payments (rules "), run.stderr)
        assertTrue(lines.any { it.startsWith("  high payment.duplicate-mutation POST https://api.example.invalid/v1/payments — ") }, run.stderr)
        // Medium findings are below the threshold and are not listed.
        assertTrue(lines.none { it.startsWith("  medium ") }, run.stderr)
        // --limit cuts the list, never the count.
        assertEquals(1, run("--session", "payments", "--fail-on", "medium", "--limit", "1").exit)
    }

    @Test
    fun `fail-on exits 0 when nothing is at or above the threshold`() {
        assertEquals(1, direct("info-only", null, 1000).info, "the session must hold the info finding it stands for")
        assertEquals(0, direct("info-only", null, 1000).high + direct("info-only", null, 1000).medium)
        val run = run("--session", "info-only", "--fail-on", "high")
        assertEquals(0, run.exit, run.stderr)
        assertTrue(run.stderr.startsWith("inspector: 0 findings at or above high in info-only"), run.stderr)
        assertEquals(1, run("--session", "info-only", "--fail-on", "info").exit)
    }

    @Test
    fun `fail-on exits 2 when it cannot judge`() {
        val bad = run("--session", "payments", "--fail-on", "critical")
        assertEquals(2, bad.exit)
        assertEquals("inspector: cannot judge: unknown --fail-on 'critical'. Use one of: high, medium, info.", bad.stderr.trim())
        assertEquals(2, run("--session", "payments", "--fail-on").exit, "a flag with no value")
        assertEquals(2, run("--session", "nope", "--fail-on", "high").exit)
        assertEquals(2, run("--session", "payments", "--category", "pay", "--fail-on", "high").exit)

        val empty = run("--session", "empty", "--fail-on", "high")
        assertEquals(2, empty.exit)
        assertTrue("holds no calls" in empty.stderr, empty.stderr)

        // A findings key that does not parse: in a fresh process the fallback is the defaults.
        configure("""{"findings": "off"}""")
        val broken = run("--session", "payments", "--fail-on", "high")
        assertEquals(2, broken.exit, broken.stderr)
        assertTrue("config.json could not be read" in broken.stderr, broken.stderr)
    }

    @Test
    fun `a dismissal the page and list_findings honour lets the gate pass`() {
        val target = direct("payments", null, 1000).findings.single { it.ruleId == "payment.duplicate-mutation" }
        configure(
            buildJsonObject {
                put(
                    "findingDismissals",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("ruleId", target.ruleId)
                                put("route", target.route)
                                put("location", target.location)
                                put("path", target.path)
                                put("reason", "the flow posts twice on purpose")
                            },
                        )
                    },
                )
            }.toString(),
        )
        assertEquals(1, direct("payments", null, 1000).dismissedCount)
        val run = run("--session", "payments", "--fail-on", "high")
        assertEquals(0, run.exit, run.stderr)
    }

    @Test
    fun `without fail-on, a high finding and a missing session exit as before`() {
        val plain = run("--session", "payments")
        assertEquals(0, plain.exit)
        assertEquals("", plain.stderr)
        assertEquals(direct("payments", null, 1000), InspectorJson.decodeFromString(FindingsReport.serializer(), plain.stdout))
        assertEquals(1, run("--session", "nope").exit)
        assertEquals(0, run("--session", "empty").exit)
    }

    @Test
    fun `a bad category exits 1 with the message on stderr`() {
        // In a child JVM on this test's classpath, because the CLI ends the process on a failure.
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "dev.inspector.daemon.MainKt",
            "findings", "--category", "pay", "--data", tmp.toString(),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val stderr = process.errorStream.bufferedReader().readText()
        assertEquals(1, process.waitFor())
        assertEquals("inspector: unknown category 'pay'. Use one of: payment, sensitive.", stderr.trim())
    }
}
