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

/**
 * `inspector findings`, reading the archive off disk as `stats` does: the report REST serves, its
 * flags, and a bad category's exit, with the message all three surfaces take from
 * `FindingsEngine.parseCategory`.
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
