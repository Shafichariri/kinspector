package dev.inspector.daemon

import dev.inspector.model.SignalTags
import dev.inspector.model.duplicateGroups
import dev.inspector.model.pathScope
import dev.inspector.model.timeline
import dev.inspector.model.timelineRuns
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CI probe session, read back the way the daemon serves it.
 *
 * Two jobs. That the daemon can read what [writeProbeFixture] writes at all — a fixture it cannot
 * serve would surface as a CI run full of `n/a`, far from the cause. And that each thing a probe
 * depends on is actually in it: the probes report `n/a` for a case the session lacks, and a
 * fixture that quietly lost its marker tie would turn the check for that rule into a skip.
 */
class ProbeFixtureTest {

    private lateinit var tmp: Path
    private lateinit var repo: SessionRepository
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-probe-fixture")
        writeProbeFixture(tmp)
        repo = SessionRepository(DaemonConfig(dataDir = tmp))
        dir = assertNotNull(repo.resolve("latest"), "latest does not resolve to the probe session")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun the_daemon_reads_back_everything_the_fixture_wrote() {
        val fixture = probeFixture()
        assertEquals(listOf(PROBE_SESSION_ID), repo.listSessions().map { it.sessionId })
        assertEquals(fixture.transactions.size, repo.readTransactions(dir).size)
        assertEquals(fixture.markers.size, repo.readMarkers(dir).size)
        assertEquals(fixture.signals.size, repo.readSignals(dir).size)
        for ((id, pair) in fixture.bodies) {
            pair.first?.let { assertTrue(it.contentEquals(repo.readBody(dir, id, "req")), "req body of $id") }
            pair.second?.let { assertTrue(it.contentEquals(repo.readBody(dir, id, "res")), "res body of $id") }
        }
    }

    @Test
    fun writing_twice_replaces_the_session_rather_than_doubling_it() {
        writeProbeFixture(tmp)
        val fixture = probeFixture()
        assertEquals(listOf(PROBE_SESSION_ID), repo.listSessions().map { it.sessionId })
        assertEquals(fixture.transactions.size, repo.readTransactions(dir).size)
        assertEquals(fixture.markers.size, repo.readMarkers(dir).size)
        assertEquals(fixture.signals.size, repo.readSignals(dir).size)
    }

    @Test
    fun every_case_a_probe_reads_is_present() {
        val txns = repo.readTransactions(dir)
        val markers = repo.readMarkers(dir)
        val signals = repo.readSignals(dir)

        val scope = assertNotNull(pathScope(txns), "no scope bar")
        assertTrue(txns.any { it.host != scope.host }, "no row outside the scope's host")
        assertTrue(txns.map { it.path.substringAfterLast('/') }.distinct().size >= 5, "fewer than five endpoints")
        assertTrue(txns.any { it.status == 500 }, "no 500")
        assertTrue(txns.groupBy { it.callId }.values.any { chain -> chain.size >= 2 && chain.any { it.status == 302 } }, "no redirect chain")
        assertTrue(txns.groupBy { it.callId }.values.any { chain -> chain.size >= 3 }, "no retry chain")
        assertTrue(
            duplicateGroups(txns).any { it.callCount < it.ids.size },
            "no repeat that swept up a retry, so rows and calls never differ",
        )
        assertTrue(markers.any { m -> txns.any { it.mono == m.mono } }, "no marker at a call's mono")

        val runs = timelineRuns(timeline(txns, markers, signals))
        assertTrue(runs.any { it.entries.size >= 3 }, "no run of three identical observations")
        val screens = signals.filter { it.tag == SignalTags.SCREEN }.map { it.name }
        assertTrue(screens.zipWithNext().any { (a, b) -> a == b }, "no repeated screen")
        assertTrue(screens.size > screens.distinct().size, "no revisit")
        assertTrue(signals.any { it.tag == SignalTags.CACHE } && signals.any { it.tag == SignalTags.STATE }, "cache or state missing")

        val res = txns.mapNotNull { t -> repo.readBody(dir, t.id, "res")?.let { t to it } }
        assertTrue(res.any { (t, _) -> t.resContentType == "application/json" }, "no JSON body")
        assertTrue(res.any { (_, b) -> runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)) }.isFailure }, "no binary body")
        assertTrue(txns.any { it.resBytes == 0L && it.status != null }, "no empty body")
        assertTrue(txns.all { it.host.endsWith(".example.invalid") }, "a host outside example.invalid")
    }
}
