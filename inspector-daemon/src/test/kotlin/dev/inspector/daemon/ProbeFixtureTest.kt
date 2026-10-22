package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsService
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID, PAYMENTS_SESSION_ID), repo.listSessions().map { it.sessionId })
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID, PAYMENTS_SESSION_ID), repo.listSessions().map { it.sessionId })
        assertEquals(fixture.transactions.size, repo.readTransactions(dir).size)
        assertEquals(fixture.markers.size, repo.readMarkers(dir).size)
        assertEquals(fixture.signals.size, repo.readSignals(dir).size)
        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        assertEquals(LONG_SESSION_CALLS, repo.readTransactions(long).size)
        val payments = assertNotNull(repo.resolve(PAYMENTS_SESSION_ID), "no payments session")
        assertEquals(paymentsSessionCalls().size, repo.readTransactions(payments).size)
    }

    /**
     * The payments session, which the Findings probes read: older than the other two, so `latest` and
     * the picker stay put, and holding exactly the counts the probes print, under the default rules.
     * The probe session has none, which `findings none` reads as checked and clean.
     */
    @Test
    fun the_payments_session_holds_each_finding_the_probes_count() {
        assertEquals(dir, repo.resolve(PROBE_SESSION_ID), "latest moved off the probe session")
        val payments = assertNotNull(repo.resolve(PAYMENTS_SESSION_ID), "no payments session")
        val service = FindingsService(repo, FindingsEngine(FindingSettingsSource(tmp), DaemonConfig(dataDir = tmp).routeTemplates))
        val report = service.report(payments, null, 5000)
        assertEquals(listOf(18, 10, 3, 6, 1), listOf(report.total, report.findingCount, report.high, report.medium, report.info))
        assertEquals(listOf(1, 1, 1, 1, 5, 1), report.rules.map { it.count }, "per rule, in catalogue order")
        assertEquals(12, report.findings.flatMap { it.txnIds }.toSet().size, "flagged rows")
        assertTrue(report.coverage.toString().contains("13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated)."))
        val probe = service.report(dir, null, 5000)
        assertEquals(listOf(16, 0), listOf(probe.total, probe.findingCount), "the probe session has findings")
        assertEquals(6, probe.rules.size)
        val rows = repo.readTransactions(payments)
        assertTrue(rows.all { it.host.endsWith(".example.invalid") }, "a host outside example.invalid")
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
     * The now-strip probes switch from the probe session to the long one, and assert the strip lists
     * the long session's own values: with none, or the same ones, "dropped" and "blanked" read alike.
     */
    @Test
    fun the_long_session_has_current_values_of_its_own() {
        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        val keys = { d: java.nio.file.Path -> repo.currentSignals(d).map { it.tag to it.name }.toSet() }
        val probe = keys(dir)
        val own = keys(long)
        assertTrue(own.isNotEmpty(), "the long session holds no current values")
        assertTrue(probe.isNotEmpty(), "the probe session holds no current values")
        assertTrue(own.intersect(probe).isEmpty(), "the two sessions share current keys: ${own.intersect(probe)}")
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
