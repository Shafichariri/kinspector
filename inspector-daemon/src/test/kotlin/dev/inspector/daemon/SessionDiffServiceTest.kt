package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.service.DiffCaps
import dev.inspector.daemon.service.DiffOutcome
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRequest
import dev.inspector.daemon.service.SessionDiffService
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The diff service over an archive on disk: which sessions it compares, the errors every adapter
 * passes on, the `since` cut, a whole session read past the 1000-row clamp, and the cache.
 */
class SessionDiffServiceTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private var computed = 0
    private val writers = ArrayList<SessionWriter>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-diff-service")
        config = DaemonConfig(dataDir = tmp)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        // A test may have closed one already, as a session that ended would be.
        writers.forEach { runCatching { it.close() } }
        tmp.toFile().deleteRecursively()
    }

    private fun service(caps: DiffCaps = DiffCaps()) =
        SessionDiffService(repository, RouteTemplates.NONE, caps, onCompute = { computed++ })

    /** An open writer on a new session of [appId], started at [startedAt]. */
    private fun session(id: String, startedAt: String, appId: String = "com.example.projectx"): SessionWriter =
        SessionWriter(config.sessionsDir.resolve(id), clientInfo(appId = appId).toSessionMeta(id, startedAt)).also { writers += it }

    private fun SessionWriter.calls(vararg paths: String, mono: Long = 1_000): SessionWriter = apply {
        paths.forEachIndexed { i, path -> append(txn("${path.hashCode().toUInt().toString(16).take(6)}${i.toString(16).padStart(2, '0')}", path = path, mono = mono + i), null, null) }
    }

    private fun ok(outcome: DiffOutcome): DiffReport = assertIs<DiffOutcome.Ok>(outcome, outcome.toString()).report

    @Test
    fun `A defaults to the newest earlier session of B's app, a tie on startedAt broken by id`() {
        session("s1", "2026-01-01T09:00:00.000Z").calls("/v1/a")
        session("s2", "2026-01-01T10:00:00.000Z", appId = "com.example.other").calls("/v1/a")
        session("s3-a", "2026-01-01T10:00:00.000Z").calls("/v1/a")
        session("s3-b", "2026-01-01T10:00:00.000Z").calls("/v1/a")
        val service = service()

        val tie = ok(service.diff(DiffRequest(b = "s3-b")))
        assertEquals("s3-a" to "previousRun", tie.a.sessionId to tie.aChosen)
        assertEquals(true, tie.sameApp)
        assertEquals("s1", ok(service.diff(DiffRequest(b = "s3-a"))).a.sessionId, "another app is skipped")
        assertEquals(
            DiffOutcome.NotFound("no earlier session of app 'com.example.projectx' before 's1'; name one with a="),
            service.diff(DiffRequest(b = "s1")),
        )
        val given = ok(service.diff(DiffRequest(a = "s2", b = "s1")))
        assertEquals("given" to false, given.aChosen to given.sameApp)
    }

    @Test
    fun `each error reads as every adapter passes it on`() {
        session("s1", "2026-01-01T09:00:00.000Z").calls("/v1/a")
        session("s2", "2026-01-01T10:00:00.000Z").calls("/v1/a").append(Marker(ts = "2026-01-01T10:00:01.000Z", mono = 5, label = "launch", source = MarkerSource.APP))
        Files.createDirectories(config.sessionsDir.resolve("bare"))
        Files.writeString(SessionLayout.indexFile(config.sessionsDir.resolve("bare")), "")
        SessionWriter.updateLatestLink(config.dataDir, config.sessionsDir.resolve("s2"))
        val service = service()

        assertEquals(DiffOutcome.NotFound("no session 'nope' (a)"), service.diff(DiffRequest(a = "nope", b = "s2")))
        assertEquals(DiffOutcome.NotFound("no session 'nope' (b)"), service.diff(DiffRequest(b = "nope")))
        assertEquals(DiffOutcome.NotFound("no session '../s1' (b)"), service.diff(DiffRequest(b = "../s1")))
        assertEquals(
            DiffOutcome.NotFound("session 'bare' has no metadata, so its previous run cannot be found; name one with a="),
            service.diff(DiffRequest(b = "bare")),
        )
        assertEquals(DiffOutcome.Invalid("a and b are the same session 's1'"), service.diff(DiffRequest(a = "s1", b = "s1")))
        assertEquals(DiffOutcome.Invalid("a and b are the same session 's2'"), service.diff(DiffRequest(a = "latest", b = "s2")))
        assertIs<DiffOutcome.Invalid>(service.diff(DiffRequest(a = "s1", b = "s2", filter = "status>>")))
        assertEquals(
            DiffOutcome.Invalid("session B has no marker 'checkout'. Its markers: launch."),
            service.diff(DiffRequest(a = "s1", b = "s2", sinceB = "checkout")),
        )
        assertEquals(
            DiffOutcome.Invalid("session A has no marker 'launch'. It has none."),
            service.diff(DiffRequest(a = "s1", b = "s2", sinceA = "launch")),
        )
        assertEquals(0, computed, "an error computed a report")

        val empty = DaemonConfig(dataDir = Files.createDirectory(tmp.resolve("empty")))
        assertEquals(
            DiffOutcome.NotFound("no session 'latest' (b)"),
            SessionDiffService(SessionRepository(empty), RouteTemplates.NONE).diff(DiffRequest()),
        )
    }

    @Test
    fun `since cuts a side at its marker, as a constructed term, whatever the label holds`() {
        // One quote: the tokenizer would read the rest of the filter as quoted, so no term can carry it.
        val label = "tapped \"pay | now"
        session("s1", "2026-01-01T09:00:00.000Z").calls("/v1/early", "/v1/late")
        session("s2", "2026-01-01T10:00:00.000Z").calls("/v1/early", mono = 1_000)
            .apply { append(Marker(ts = "2026-01-01T10:00:02.000Z", mono = 2_000, label = label, source = MarkerSource.USER)) }
            .calls("/v1/late", mono = 3_000)
        val report = ok(service().diff(DiffRequest(a = "s1", b = "s2", sinceB = label)))
        assertEquals(listOf("/v1/early" to "removed", "/v1/late" to "both"), report.routes.map { it.route to it.presence }.sortedBy { it.first })
        assertEquals(2 to 1, report.b.total to report.b.matched)
        assertEquals(label, report.b.since)
        assertEquals(false, report.b.filterCarried, "a label with a quote cannot be written in a term")
        assertEquals(listOf(label), report.b.markers)

        // An even number of quotes round-trips, so it is carried into each of B's terms.
        val even = "tapped \"pay\" | now"
        writers.last().append(Marker(ts = "2026-01-01T10:00:02.500Z", mono = 2_500, label = even, source = MarkerSource.USER))
        val carried = ok(service().diff(DiffRequest(a = "s1", b = "s2", sinceB = even)))
        assertEquals(true, carried.b.filterCarried)
        assertTrue(carried.routes.single { it.route == "/v1/late" }.b!!.filterTerm.startsWith("since:marker(\"$even\") "))
    }

    @Test
    fun `the long session is read whole, past the 1000-row clamp`() {
        writeProbeFixture(tmp)
        val report = ok(service().diff(DiffRequest(a = LONG_SESSION_ID, b = PROBE_SESSION_ID)))
        val bulk = report.routes.single { it.route == "/v3/long/bulk" }
        assertEquals("removed", bulk.presence)
        assertEquals(1201 to 1201, bulk.a!!.calls to bulk.a!!.attempts)
        assertEquals(LONG_SESSION_CALLS, report.a.total)
    }

    @Test
    fun `a second read is cached, and an appended row or marker recomputes`() {
        session("s1", "2026-01-01T09:00:00.000Z").calls("/v1/a").close()
        val b = session("s2", "2026-01-01T10:00:00.000Z").calls("/v1/a")
        b.append(Marker(ts = "2026-01-01T10:00:00.500Z", mono = 500, label = "go", source = MarkerSource.USER))
        val service = service()
        val request = DiffRequest(a = "s1", b = "s2")
        val first = ok(service.diff(request))
        assertSame(first, ok(service.diff(request)), "a read with no write was recomputed")
        assertEquals(1, computed)
        // A found as B's previous run is the same comparison, served from the same entry, but says so.
        val found = ok(service.diff(DiffRequest(b = "s2")))
        assertEquals("previousRun" to "given", found.aChosen to first.aChosen)
        assertEquals(1, computed)

        b.calls("/v1/b", mono = 2_000)
        val second = ok(service.diff(request))
        assertEquals(2, computed)
        assertEquals(first.b.total + 1, second.b.total)
        assertTrue(second.routes.any { it.route == "/v1/b" && it.presence == "added" })

        val since = request.copy(sinceB = "go")
        ok(service.diff(since))
        assertEquals(3, computed)
        // A later marker with the same label moves the cut: every row is now before it.
        b.append(Marker(ts = "2026-01-01T10:00:05.000Z", mono = 5_000, label = "go", source = MarkerSource.USER))
        val moved = ok(service.diff(since))
        assertEquals(4, computed)
        assertEquals(0, moved.b.matched)
    }

    @Test
    fun `limit and unchanged cut the routes after the order, and counts stay whole`() {
        session("s1", "2026-01-01T09:00:00.000Z").calls("/v1/same", "/v1/gone")
        session("s2", "2026-01-01T10:00:00.000Z").calls("/v1/same", "/v1/new", "/v1/new2")
        val service = service()
        val full = ok(service.diff(DiffRequest(a = "s1", b = "s2")))
        assertEquals(4, full.routeCount)
        assertEquals(listOf(2, 1, 0, 0, 1), with(full.counts) { listOf(added, removed, changed, unsure, unchanged) })
        assertEquals("/v1/same", full.routes.last().route, "unchanged rows sort last")

        val changed = ok(service.diff(DiffRequest(a = "s1", b = "s2", unchanged = false)))
        assertEquals(3, changed.routes.size)
        assertEquals(full.counts, changed.counts)
        val one = ok(service.diff(DiffRequest(a = "s1", b = "s2", limit = 1)))
        assertEquals(listOf(full.routes.first()), one.routes)
        assertEquals(4, one.routeCount)
        assertNotSame(full, one)
        assertEquals(1, computed, "a cut of a cached report recomputed it")
    }

    @Test
    fun `an unsure route is counted apart from unchanged, and unchanged=false keeps it`() {
        writeQaPair(tmp)
        val service = service()
        val full = ok(service.diff(DiffRequest(a = QA_PAIR_A, b = QA_PAIR_B)))
        assertEquals(listOf(0, 0, 0, 1, 1), with(full.counts) { listOf(added, removed, changed, unsure, unchanged) })
        assertEquals(full.routeCount, with(full.counts) { added + removed + changed + unsure + unchanged }, "the counts overlap")
        assertEquals(listOf("/v1/product", "/v1/health"), full.routes.map { it.route }, "an unsure route sorts before an unchanged one")

        val cut = ok(service.diff(DiffRequest(a = QA_PAIR_A, b = QA_PAIR_B, unchanged = false)))
        assertEquals(listOf("/v1/product" to true), cut.routes.map { it.route to it.unsure })
        assertEquals(full.counts, cut.counts)
        assertEquals(full.counts, ok(service.diff(DiffRequest(a = QA_PAIR_A, b = QA_PAIR_B, unchanged = false, limit = 1))).counts)
        assertEquals(1, computed, "a cut of a cached report recomputed it")
    }

    @Test
    fun `a byte budget lowered to one byte is exhausted, and nothing is claimed from what it left unread`() {
        writeProbeFixture(tmp)
        val report = ok(service(DiffCaps(bytesBudget = 1)).diff(DiffRequest(a = BASE_SESSION_ID, b = PROBE_SESSION_ID)))
        assertTrue(report.shapeBudget.exhausted)
        assertEquals(0, report.shapeBudget.bodiesRead)
        val products = report.routes.single { it.route == "/v3/shop/products/{id}" }
        assertEquals(emptyList(), products.responseShape!!.typeChanged)
        assertTrue(products.responseShape!!.a.unread == 1 && !products.responseShape!!.compared)
        assertTrue(report.routes.all { r -> listOfNotNull(r.requestShape, r.responseShape).all { it.removed.isEmpty() && it.added.isEmpty() } })
    }
}
