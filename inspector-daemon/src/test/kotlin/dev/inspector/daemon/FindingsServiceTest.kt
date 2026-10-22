package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.InspectorJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The findings cache: a fold of what was appended equals a cold compute, the list is collected again
 * only when rows arrived, and an edit to the `findings` key reaches the next read, in this service and
 * in a second one standing for `inspector mcp`. "A new service" is the cold compute, because a
 * service's cache dies with it.
 */
class FindingsServiceTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-service")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun service() = FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates))

    private fun FindingsService.read(dir: Path, category: String? = null): FindingsReport = report(dir, category, 1000)

    private fun cold(dir: Path) = service().read(dir)

    private fun writer(id: String = "s1") =
        SessionWriter(config.sessionsDir.resolve(id), clientInfo().toSessionMeta(id, "2026-08-16T10:14:02.311Z"))

    /** Writes the config file and moves its modified time on, so an edit is always seen as one. */
    private fun configure(text: String) {
        val file = tmp.resolve("config.json")
        val before = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(maxOf(before + 1_000, System.currentTimeMillis())))
    }

    private fun SessionWriter.add(call: Captured) = append(call.row, call.body, null)

    @Test
    fun `a fold after appends equals a cold compute`() {
        val writer = writer()
        val warm = service()
        writer.add(mutation("00000001", 1_000, "c-a"))
        assertEquals(1, warm.read(writer.sessionDir).findingCount, "one payment path without a key")

        writer.add(mutation("00000002", 2_200, "c-b"))
        writer.add(mutation("00000003", 4_000, "c-r", path = "/v1/transfers", status = 503))
        writer.add(mutation("00000004", 4_300, "c-r", path = "/v1/transfers", attempt = 2))
        (5..40).forEach { writer.add(getCall("%08x".format(it), 5_000L + it)) }

        val folded = warm.read(writer.sessionDir)
        assertEquals(cold(writer.sessionDir), folded)
        assertEquals(40, folded.total)
        assertEquals(
            listOf("payment.duplicate-mutation", "payment.retry-without-key", "payment.key-missing-payment-path"),
            folded.findings.map { it.ruleId }.distinct(),
        )
        writer.close()
    }

    @Test
    fun `a line written without its newline waits, and is counted once when it is completed`() {
        val writer = writer()
        writer.add(mutation("00000001", 1_000, "c-a"))
        writer.close()
        val warm = service()
        assertEquals(1, warm.read(writer.sessionDir).total)

        val index = SessionLayout.indexFile(writer.sessionDir)
        val line = InspectorJson.encodeToString(mutation("00000002", 1_500, "c-b").row)
        Files.writeString(index, line.substring(0, 40), StandardOpenOption.APPEND)
        assertEquals(1, warm.read(writer.sessionDir).total)
        Files.writeString(index, line.substring(40) + "\n", StandardOpenOption.APPEND)
        val completed = warm.read(writer.sessionDir)
        assertEquals(2, completed.total)
        assertEquals(cold(writer.sessionDir), completed)
    }

    @Test
    fun `a replaced file rebuilds from the start`() {
        val first = writeFindingsSession(config, "s1", listOf(mutation("00000001", 1_000, "c-a"), mutation("00000002", 1_500, "c-b")))
        val warm = service()
        assertEquals(2, warm.read(first).total)

        val index = SessionLayout.indexFile(first)
        val replacement = InspectorJson.encodeToString(getCall("00000009", 1_000).row) + "\n"
        Files.delete(index)
        Files.writeString(index, replacement)
        val after = warm.read(first)
        assertEquals(1, after.total)
        assertEquals(0, after.findingCount)
    }

    @Test
    fun `a finding on the oldest of 1203 rows is found, past the transactions route's clamp`() {
        // The transactions route keeps the newest 1000; a fold that read through it would never see this row.
        val calls = listOf(mutation("bfffffff", 500, "c-first")) +
            (0 until 1202).map { getCall("b" + it.toString(16).padStart(7, '0'), 1_000L + it * 10) }
        val dir = writeFindingsSession(config, "long", calls)
        val report = service().read(dir)
        assertEquals(1203, report.total)
        assertEquals(listOf("bfffffff"), report.findings.single().txnIds)
        assertEquals("payment.key-missing-payment-path", report.findings.single().ruleId)
    }

    @Test
    fun `an unchanged session reuses its collected list, and a new row collects again`() {
        val writer = writer()
        writer.add(mutation("00000001", 1_000, "c-a"))
        val warm = service()
        repeat(3) { warm.read(writer.sessionDir) }
        warm.read(writer.sessionDir, "payment")
        assertEquals(1, warm.collects)

        writer.add(mutation("00000002", 2_000, "c-b"))
        warm.read(writer.sessionDir)
        assertEquals(2, warm.collects)
        writer.close()
    }

    @Test
    fun `an edit to the findings key changes the next reply here and in a second service`() {
        val dir = writeFindingsSession(
            config, "s1",
            listOf(mutation("00000001", 1_000, "c-a"), mutation("00000002", 4_500, "c-b")),
        )
        val daemon = service()
        val mcp = service()
        val before = daemon.read(dir)
        assertEquals(listOf("payment.key-missing-payment-path"), before.findings.map { it.ruleId }.distinct())
        assertEquals(before, mcp.read(dir))

        // A wider window makes the two calls, 3.5 s apart, one duplicate.
        configure("""{"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        val daemonAfter = daemon.read(dir)
        assertEquals("payment.duplicate-mutation", daemonAfter.findings.first().ruleId)
        assertNotEquals(before.ruleVersion, daemonAfter.ruleVersion)
        assertEquals(daemonAfter, mcp.read(dir), "a second process reads the same edit with no restart")

        configure("""{"findings":{"rules":{"payment.duplicate-mutation":{"enabled":false},"payment.key-missing-payment-path":{"enabled":false}}}}""")
        assertEquals(0, daemon.read(dir).findingCount)
        assertEquals(0, mcp.read(dir).findingCount)
    }
}
