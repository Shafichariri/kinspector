package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.daemon.service.StatsSort
import dev.inspector.model.InspectorJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cache: a fold of what was appended since the last read equals a cold read of the whole file,
 * whatever happened in between.
 *
 * "A new service" is the cold compute in every case, because a service's cache dies with it.
 */
class RouteStatsServiceTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-route-stats")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun service() = RouteStatsService(repository, config.routeTemplates)

    private fun RouteStatsService.read(dir: Path, filter: String = ""): RouteStatsReport =
        report(dir, filter, StatsSort.P50, 1000)

    private fun cold(dir: Path, filter: String = "") = service().read(dir, filter)

    private fun writer(id: String = "s1") =
        SessionWriter(config.sessionsDir.resolve(id), clientInfo().toSessionMeta(id, "2026-08-16T10:14:02.311Z"))

    private fun call(i: Int, status: Int = 200, attempt: Int = 1, callId: String = "c$i") =
        txn("%08x".format(i), path = "/users/${i % 3}/orders", status = status, ms = 10L + i, mono = 100L * i, attempt = attempt, callId = callId)

    @Test
    fun `a fold after appends equals a cold compute, field by field`() {
        val writer = writer()
        val warm = service()
        (0 until 5).forEach { writer.append(call(it), null, null) }
        assertEquals(5, warm.read(writer.sessionDir).total)

        (5 until 40).forEach { writer.append(call(it, status = if (it % 7 == 0) 503 else 200), null, null) }
        writer.append(call(40, attempt = 2, callId = "c39"), null, null)
        writer.append(txn("ffff0001", path = "/other", status = 302, callId = "r"), null, null)

        val folded = warm.read(writer.sessionDir)
        assertEquals(cold(writer.sessionDir), folded)
        assertEquals(42, folded.total)
        assertEquals(cold(writer.sessionDir, "status>=500"), warm.read(writer.sessionDir, "status>=500"))
        writer.close()
    }

    @Test
    fun `a marker appended under a since filter recomputes the cut`() {
        val writer = writer()
        val warm = service()
        val filter = "since:marker(\"go\")"
        (1..4).forEach { writer.append(call(it), null, null) }
        assertEquals(0, warm.read(writer.sessionDir, filter).matched, "no marker yet, so nothing is after it")

        writer.append(marker("go", mono = 250))

        val after = warm.read(writer.sessionDir, filter)
        assertEquals(2, after.matched)
        assertEquals(cold(writer.sessionDir, filter), after)
        writer.close()
    }

    @Test
    fun `a line written without its newline waits, and is counted once when it is completed`() {
        val writer = writer()
        (0 until 3).forEach { writer.append(call(it), null, null) }
        writer.close()
        val index = SessionLayout.indexFile(writer.sessionDir)
        val warm = service()
        assertEquals(3, warm.read(writer.sessionDir).total)

        // As a buffer flushed mid-row leaves it: the row is on disk and its newline is not.
        val row = InspectorJson.encodeToString(call(3))
        Files.writeString(index, row.substring(0, 40), StandardOpenOption.APPEND)
        assertEquals(3, warm.read(writer.sessionDir).total)
        Files.writeString(index, row.substring(40), StandardOpenOption.APPEND)
        assertEquals(3, warm.read(writer.sessionDir).total)

        Files.writeString(index, "\n", StandardOpenOption.APPEND)
        val completed = warm.read(writer.sessionDir)
        assertEquals(4, completed.total)
        assertEquals(cold(writer.sessionDir), completed)
    }

    @Test
    fun `a session folder deleted and written again is read from the start`() {
        val first = writer()
        (0 until 3).forEach { first.append(call(it), null, null) }
        first.close()
        val warm = service()
        assertEquals(3, warm.read(first.sessionDir).total)

        first.sessionDir.toFile().deleteRecursively()
        val second = writer()
        (10 until 16).forEach { second.append(call(it, status = 500), null, null) }
        second.close()

        val again = warm.read(second.sessionDir)
        assertEquals(6, again.total)
        assertEquals(cold(second.sessionDir), again)
    }

    /** Same file, same inode, longer than before: only the bytes it starts with say it is not the same. */
    @Test
    fun `an index rewritten in place is read from the start`() {
        val writer = writer()
        (0 until 3).forEach { writer.append(call(it), null, null) }
        writer.close()
        val index = SessionLayout.indexFile(writer.sessionDir)
        val warm = service()
        assertEquals(3, warm.read(writer.sessionDir).total)

        val rows = (20 until 26).joinToString("") { InspectorJson.encodeToString(call(it, status = 404)) + "\n" }
        Files.writeString(index, rows, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)

        val again = warm.read(writer.sessionDir)
        assertEquals(6, again.total)
        assertEquals(cold(writer.sessionDir), again)
    }

    @Test
    fun `a session with no index reads as empty`() {
        val dir = Files.createDirectories(config.sessionsDir.resolve("empty"))
        val report = service().read(dir)
        assertEquals(0, report.total)
        assertEquals(emptyList(), report.routes)
    }
}
