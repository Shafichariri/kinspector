package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.SignalTags
import dev.inspector.model.DEFAULT_DUPLICATE_WINDOW_MS
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.duplicateGroups
import dev.inspector.model.endpointShortcuts
import dev.inspector.model.pathScope
import dev.inspector.model.timeline
import dev.inspector.model.timelineRuns
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID), repo.listSessions().map { it.sessionId })
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID), repo.listSessions().map { it.sessionId })
        assertEquals(fixture.transactions.size, repo.readTransactions(dir).size)
        assertEquals(fixture.markers.size, repo.readMarkers(dir).size)
        assertEquals(fixture.signals.size, repo.readSignals(dir).size)
        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        assertEquals(LONG_SESSION_CALLS, repo.readTransactions(long).size)
    }

    /**
     * The long session, which the probes read past the route's clamp: they report the `twice` chip
     * counting 2 and call #203 marked as a repeat, and both hold only if every fact below does.
     */
    @Test
    fun the_long_session_holds_one_repeat_that_spans_the_clamp() {
        assertEquals(dir, repo.resolve(PROBE_SESSION_ID), "latest moved off the probe session")
        assertEquals(PROBE_SESSION_ID, repo.listSessions().first().sessionId, "the probe session is not first in the picker")
        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        val txns = repo.readTransactions(long).sortedBy { it.mono }
        assertEquals(LONG_SESSION_CALLS, txns.size)

        val first = txns[0]
        val repeat = txns[LONG_REPEAT_INDEX]
        assertEquals(LONG_SESSION_CALLS - 1000, LONG_REPEAT_INDEX, "the repeat is not the oldest of the newest 1000")
        assertEquals(sameRequest(first), sameRequest(repeat), "call #0 and call #$LONG_REPEAT_INDEX are not one request")
        assertNotEquals(first.callId, repeat.callId, "call #0 and call #$LONG_REPEAT_INDEX share a callId")
        assertTrue(repeat.mono - first.mono <= DEFAULT_DUPLICATE_WINDOW_MS, "the repeat is outside the window")
        assertEquals(
            listOf(first.id, repeat.id), txns.filter { sameRequest(it) == sameRequest(first) }.map { it.id },
            "another call shares the repeat's key",
        )
        assertEquals(listOf(listOf(first.id, repeat.id)), duplicateGroups(txns).map { it.ids }, "not exactly one repeat group")

        val endpoints = endpointShortcuts(txns, Int.MAX_VALUE)
        assertTrue(endpoints.size <= 3, "more than three endpoints: ${endpoints.map { it.segment }}")
        assertEquals(2, endpoints.single { it.segment == "twice" }.count)
        assertTrue(txns.all { it.host.endsWith(".example.invalid") }, "a host outside example.invalid")
    }

    /**
     * The model's duplicate key, which is `internal` there: method, URL, status and both byte counts,
     * the same five fields the web UI's `duplicateKey` joins.
     */
    private fun sameRequest(t: NetworkTransaction) = listOf(t.method, t.url, t.status, t.reqBytes, t.resBytes)

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
