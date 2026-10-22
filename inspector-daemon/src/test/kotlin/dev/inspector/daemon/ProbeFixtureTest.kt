package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.SignalTags
import dev.inspector.model.DEFAULT_DUPLICATE_WINDOW_MS
import dev.inspector.model.InspectorJson
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID, PAYMENTS_SESSION_ID, BASE_SESSION_ID), repo.listSessions().map { it.sessionId })
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
        assertEquals(listOf(PROBE_SESSION_ID, LONG_SESSION_ID, PAYMENTS_SESSION_ID, BASE_SESSION_ID), repo.listSessions().map { it.sessionId })
        assertEquals(fixture.transactions.size, repo.readTransactions(dir).size)
        assertEquals(fixture.markers.size, repo.readMarkers(dir).size)
        assertEquals(fixture.signals.size, repo.readSignals(dir).size)
        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        assertEquals(LONG_SESSION_CALLS, repo.readTransactions(long).size)
        val payments = assertNotNull(repo.resolve(PAYMENTS_SESSION_ID), "no payments session")
        assertEquals(paymentsSessionCalls().size, repo.readTransactions(payments).size)
        val base = assertNotNull(repo.resolve(BASE_SESSION_ID), "no base session")
        assertEquals(baseSessionCalls().transactions.size, repo.readTransactions(base).size)
    }

    /**
     * The base session, which the diff compares the probe session with: each route shows one rule
     * against the probe's, and only while every fact below holds. Read through the repository, so a
     * fixture edit that loses one fails here and not as a diff probe reading `n/a` or a wrong count.
     */
    @Test
    fun the_base_session_holds_each_difference_the_diff_reads() {
        val base = assertNotNull(repo.resolve(BASE_SESSION_ID), "no base session")
        assertEquals(BASE_SESSION_ID, repo.listSessions().last().sessionId, "the base session is not the oldest")
        assertEquals(dir, repo.resolve("latest"), "latest moved off the probe session")
        val metas = repo.listSessions().associateBy { it.sessionId }
        assertEquals(1, listOf(PROBE_SESSION_ID, LONG_SESSION_ID, BASE_SESSION_ID).map { metas.getValue(it).appId }.distinct().size, "the diff's sessions are not one app")

        val a = repo.readTransactions(base)
        val b = repo.readTransactions(dir)
        fun on(rows: List<NetworkTransaction>, path: String) = rows.filter { it.path == path }
        fun res(session: Path, row: NetworkTransaction) = String(assertNotNull(repo.readBody(session, row.id, "res"), "no response body on ${row.id}"))

        // Unchanged: the same call, the same body.
        val config = on(a, "/v3/shop/config").single()
        assertEquals(res(dir, on(b, "/v3/shop/config").single()), res(base, config))

        // The token: a request header added, and a redacted response that must not read as a type change.
        val tokenA = on(a, "/oauth/token").single()
        val tokenB = on(b, "/oauth/token").single()
        assertTrue(tokenA.reqHeaders.keys.none { it.equals("X-Request-Id", ignoreCase = true) }, "the base token sends X-Request-Id")
        assertTrue(tokenB.reqHeaders.keys.any { it.equals("X-Request-Id", ignoreCase = true) }, "the probe token lost X-Request-Id")
        assertEquals(listOf("body:res:\$.access_token", "body:res:\$.expires_in"), tokenA.redacted)
        assertTrue("\"expires_in\":\"‹redacted›\"" in res(base, tokenA), "the base token's expires_in is not the placeholder")
        assertTrue("\"expires_in\":3600" in res(dir, tokenB), "the probe token's expires_in is not a number")

        // The feed: a prefix of a far larger response, kept as truncated, which no strict parse reads.
        val feed = on(a, "/v3/shop/feed").single()
        assertTrue(feed.resBodyTruncated, "the base feed is not marked truncated")
        assertEquals(BASE_FEED_BYTES, feed.resBytes)
        val prefix = assertNotNull(repo.readBody(base, feed.id, "res"), "no prefix on disk")
        assertEquals(40, prefix.size)
        assertTrue(runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(prefix)) }.isFailure, "the prefix parses")
        assertTrue(!on(b, "/v3/shop/feed").single().resBodyTruncated, "the probe feed is truncated")

        // The product: price a number here, a string in the probe session.
        assertTrue("\"price\":9.99" in res(base, on(a, "/v3/shop/products/42").single()))
        assertTrue("\"price\":\"9.99\"" in res(dir, on(b, "/v3/shop/products/42").single()))

        // The order: two calls answered 200 with an order, against one answered 500 with an error.
        val ordersA = on(a, "/v3/shop/orders/submit")
        val ordersB = on(b, "/v3/shop/orders/submit")
        assertEquals(listOf(2, 1), listOf(ordersA, ordersB).map { rows -> rows.map { it.callId }.distinct().size }, "orders calls A, B")
        assertEquals(listOf(listOf(200, 200), listOf(500)), listOf(ordersA, ordersB).map { rows -> rows.map { it.status } })
        assertTrue(ordersA.all { "\"orderId\"" in res(base, it) && "\"status\"" in res(base, it) })
        assertTrue("\"error\"" in res(dir, ordersB.single()))
        assertEquals(2, ordersA.map { String(repo.readBody(base, it.id, "req")!!) }.distinct().size, "the two orders send one body")

        // The stock: five 80 ms responses against the probe's four attempts, one status new.
        val stockA = on(a, "/v3/shop/stock")
        val stockB = on(b, "/v3/shop/stock")
        assertEquals(listOf(5, 4), listOf(stockA.size, stockB.size), "stock attempts A, B")
        assertTrue(stockA.all { it.status == 200 && it.ms == 80L })
        assertTrue(stockB.any { it.status == 503 })

        // Removed: in the base session only.
        assertEquals(1, on(a, "/v3/shop/legacy-banner").size)
        assertTrue(on(b, "/v3/shop/legacy-banner").isEmpty())

        // Nothing for the findings and signal probes to read here.
        assertTrue(repo.readMarkers(base).isEmpty() && repo.readSignals(base).isEmpty(), "the base session holds a marker or a signal")
        val findings = FindingsService(repo, FindingsEngine(FindingSettingsSource(tmp), DaemonConfig(dataDir = tmp).routeTemplates))
        assertEquals(0, findings.report(base, null, 5000).findingCount, "the base session has findings")
        assertTrue(a.all { it.host.endsWith(".example.invalid") }, "a host outside example.invalid")
    }

    /**
     * The payments session, which the Findings probes read: older than the other two, so `latest` and
     * the picker stay put, and holding exactly the counts the probes print, under the default rules.
     * The probe session's are the three planted values' alone (the next test).
     */
    @Test
    fun the_payments_session_holds_each_finding_the_probes_count() {
        assertEquals(dir, repo.resolve(PROBE_SESSION_ID), "latest moved off the probe session")
        val payments = assertNotNull(repo.resolve(PAYMENTS_SESSION_ID), "no payments session")
        val service = FindingsService(repo, FindingsEngine(FindingSettingsSource(tmp), DaemonConfig(dataDir = tmp).routeTemplates))
        val report = service.report(payments, null, 5000)
        assertEquals(listOf(18, 10, 3, 6, 1), listOf(report.total, report.findingCount, report.high, report.medium, report.info))
        assertEquals(listOf(1, 1, 1, 1, 5, 1), report.rules.filter { it.category == "payment" }.map { it.count }, "per payment rule, in catalogue order")
        assertEquals(12, report.findings.flatMap { it.txnIds }.toSet().size, "flagged rows")
        assertTrue(report.coverage.toString().contains("13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated)."))
        val probe = service.report(dir, null, 5000)
        assertEquals(17, probe.rules.size, "six payment rules and eleven sensitive")
        assertEquals(0, probe.findings.count { it.category == "payment" }, "a payment finding on the probe session")
        val rows = repo.readTransactions(payments)
        assertTrue(rows.all { it.host.endsWith(".example.invalid") }, "a host outside example.invalid")
    }

    /**
     * The sensitive-data probes read the probe session's Findings page: each planted value makes one
     * finding with a masked hint, in rows the fixture already had, so no other probe's count moves. The
     * long session has none, so the probes that need a session with no findings read it instead.
     */
    @Test
    fun the_probe_session_holds_each_planted_value_and_no_new_row() {
        val txns = repo.readTransactions(dir)
        assertEquals((1..16).map { "a" + it.toString(16).padStart(7, '0') }, txns.map { it.id }.sorted(), "rows added or lost")
        val req = { id: String -> String(assertNotNull(repo.readBody(dir, id, "req"), "no request body on $id")) }
        val res = { id: String -> String(assertNotNull(repo.readBody(dir, id, "res"), "no response body on $id")) }
        assertTrue(PLANTED_CARD in req("a0000008") && "\"cvc\"" in req("a0000008"), "the card is not in the checkout POST")
        assertEquals(req("a0000008").length.toLong(), txns.single { it.id == "a0000008" }.reqBytes, "the checkout's byte count")
        assertEquals("api_key=$PLANTED_KEY", txns.single { it.id == "a0000004" }.query, "the key is not on the product GET")
        assertTrue(PLANTED_IBAN in res("a000000b"), "the IBAN is not in the balance")
        assertEquals(res("a000000b").length.toLong(), txns.single { it.id == "a000000b" }.resBytes, "the balance's byte count")

        val service = FindingsService(repo, FindingsEngine(FindingSettingsSource(tmp), DaemonConfig(dataDir = tmp).routeTemplates))
        val report = service.report(dir, null, 5000)
        val seen = report.findings.map { Triple(it.ruleId, it.severity, it.evidence.single().masked) }.toSet()
        assertEquals(
            setOf(
                Triple("sensitive.card-number", "medium", "•••• 5556"),
                Triple("sensitive.field.payment", "medium", "••••"),
                Triple("sensitive.secret-in-url", "high", "••••"),
                Triple("sensitive.iban", "medium", "GB82 •••• 5432"),
            ),
            seen,
        )
        assertEquals(listOf(16, 4, 1, 3, 0, 0), listOf(report.total, report.findingCount, report.high, report.medium, report.info, report.dismissedCount))
        val iban = report.findings.single { it.ruleId == "sensitive.iban" }
        assertEquals(listOf("\$.accounts[*].iban", "\$.accounts[0].iban"), listOf(iban.path, iban.evidence.single().path), "the IBAN's group and concrete paths")
        val coverage = report.coverage.toString()
        assertTrue("\"firstPartyHosts\":0" in coverage && "\"line\"" in coverage, "no sensitive coverage line with no first-party hosts: $coverage")
        val text = InspectorJson.encodeToString(FindingsReport.serializer(), report)
        for (value in listOf(PLANTED_CARD, PLANTED_IBAN, PLANTED_KEY)) assertTrue(value !in text, "the report names $value")

        val long = assertNotNull(repo.resolve(LONG_SESSION_ID), "no long session")
        assertEquals(0, service.report(long, null, 5000).findingCount, "the long session has findings")
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
