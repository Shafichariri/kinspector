package dev.inspector.daemon

import dev.inspector.daemon.archive.FindingRuleInfo
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.service.Evidence
import dev.inspector.daemon.service.Finding
import dev.inspector.daemon.service.FindingAccumulator
import dev.inspector.daemon.service.FindingMask
import dev.inspector.daemon.service.FindingRow
import dev.inspector.daemon.service.FindingSession
import dev.inspector.daemon.service.FindingSource
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.model.NetworkTransaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's half of the extension point, with test-only sources: what it drops, orders, caps and
 * counts, whatever a source hands it. The payment rules are `PaymentRulesTest`'s.
 */
class FindingsEngineTest {

    private lateinit var tmp: Path
    private val logged = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-engine")
        logged.clear()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun rule(id: String, severity: FindingSeverity, category: String = "test") =
        FindingRuleInfo(id, category, "Test", id, "A test rule.", severity)

    /** One finding per row whose path names a rule: `/r/<ruleId>` with the row as its own group. */
    private class TestSource(
        override val category: String,
        override val rules: List<FindingRuleInfo>,
        override val version: String = "test-1",
        private val masked: String? = null,
        private val coverage: JsonObject? = null,
        /** Ids the accumulator emits under, whether or not they are registered. */
        private val catalogue: Map<String, FindingRuleInfo> = rules.associateBy { it.id },
    ) : FindingSource {
        override fun accumulator(session: FindingSession): FindingAccumulator = object : FindingAccumulator {
            val rows = ArrayList<NetworkTransaction>()
            override fun add(row: NetworkTransaction) { rows += row }
            override fun coverage(): JsonObject? = coverage
            override fun findings(): List<Finding> = rows.groupBy { it.path.removePrefix("/r/") }.mapNotNull { (id, members) ->
                val info = catalogue[id] ?: return@mapNotNull null
                session.finding(
                    info, session.severity(id).takeIf { info.id.startsWith("payment.") } ?: info.defaultSeverity,
                    groupKey = members.first().callId, route = session.routeOf(members.first()),
                    rows = members.map(FindingRow::of), message = "test",
                    evidence = members.map { Evidence(it.id, masked = masked) },
                )
            }
        }
    }

    private fun engine(vararg sources: FindingSource, templates: RouteTemplates = RouteTemplates.NONE) =
        FindingsEngine(FindingSettingsSource(tmp), templates, { sources.toList() }, { logged += it })

    private fun engineFor(templates: RouteTemplates = RouteTemplates.NONE, build: (FindingSettings) -> List<FindingSource>) =
        FindingsEngine(FindingSettingsSource(tmp), templates, build, { logged += it })

    private fun row(i: Int, ruleId: String, mono: Long = 1_000L + i, callId: String = "c$i") =
        txn("%08x".format(i), path = "/r/$ruleId", mono = mono, method = "POST", callId = callId)

    private fun compute(engine: FindingsEngine, rows: List<NetworkTransaction>): FindingsReport =
        engine.compute("s", tmp, rows) { _, _ -> null }

    @Test
    fun `a disabled rule has no findings and is listed, an override changes severity`() {
        tmp.resolve("config.json").writeText(
            """{"findings":{"rules":{"payment.retry-with-key":{"enabled":false},"payment.duplicate-mutation":{"severity":"info"}}}}""",
        )
        val source = TestSource(
            "payment",
            listOf(rule("payment.duplicate-mutation", FindingSeverity.HIGH, "payment"), rule("payment.retry-with-key", FindingSeverity.INFO, "payment")),
        )
        val report = compute(engine(source), listOf(row(1, "payment.duplicate-mutation"), row(2, "payment.retry-with-key")))
        assertEquals(listOf("payment.duplicate-mutation"), report.findings.map { it.ruleId })
        assertEquals("info", report.findings.single().severity)
        val disabled = report.rules.single { it.id == "payment.retry-with-key" }
        assertEquals(false, disabled.enabled)
        assertEquals(0, disabled.count)
        val overridden = report.rules.single { it.id == "payment.duplicate-mutation" }
        assertEquals(listOf("info", "high", "1"), listOf(overridden.severity, overridden.defaultSeverity, overridden.count.toString()))
    }

    @Test
    fun `the order is severity, then the first member's time, then rule id, then id`() {
        val source = TestSource("test", listOf(rule("test.b", FindingSeverity.INFO), rule("test.a", FindingSeverity.HIGH), rule("test.c", FindingSeverity.HIGH)))
        val report = compute(engine(source), listOf(row(1, "test.b", mono = 10), row(2, "test.c", mono = 30), row(3, "test.a", mono = 20)))
        assertEquals(listOf("test.a", "test.c", "test.b"), report.findings.map { it.ruleId })
        assertEquals(listOf(2, 0, 1), listOf(report.high, report.medium, report.info))
        assertEquals(3, report.findingCount)
    }

    @Test
    fun `an id stays put when a row joins its group`() {
        val source = TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)))
        val before = compute(engine(source), listOf(row(1, "test.a", callId = "c-a")))
        val after = compute(engine(source), listOf(row(1, "test.a", callId = "c-a"), row(2, "test.a", callId = "c-b")))
        assertEquals(before.findings.single().id, after.findings.single().id)
        assertEquals(2, after.findings.single().txnCount)
        assertEquals(12, before.findings.single().id.length)
    }

    @Test
    fun `a chain over 250 calls lists 200 ids and counts 250`() {
        val source = TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)))
        val finding = compute(engine(source), (1..250).map { row(it, "test.a") }).findings.single()
        assertEquals(200, finding.txnIds.size)
        assertEquals(200, finding.callIds.size)
        assertEquals(250, finding.txnCount)
        assertEquals(50, finding.evidence.size)
        assertEquals("00000001", finding.txnIds.first(), "timeline order")
    }

    @Test
    fun `a finding under an id the source did not register is dropped and logged`() {
        val stray = rule("test.stray", FindingSeverity.HIGH)
        val source = TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)), catalogue = mapOf("test.a" to rule("test.a", FindingSeverity.HIGH), "test.stray" to stray))
        val report = compute(engine(source), listOf(row(1, "test.a"), row(2, "test.stray"), row(3, "test.stray")))
        assertEquals(listOf("test.a"), report.findings.map { it.ruleId })
        assertEquals(1, logged.count { "test.stray" in it }, logged.toString())
    }

    @Test
    fun `a custom rule built from settings is kept and listed, though the catalogue never heard of it`() {
        // As the sensitive source builds sensitive.custom.<id> from findings.sensitive: per instance.
        tmp.resolve("config.json").writeText("""{"findings":{"payment":{"paymentPaths":["mine"]}}}""")
        val engine = engineFor { settings ->
            val custom = rule("test.custom.${settings.payment.paymentPaths.first()}", FindingSeverity.MEDIUM)
            listOf(TestSource("test", listOf(custom)))
        }
        val report = compute(engine, listOf(row(1, "test.custom.mine")))
        assertEquals(listOf("test.custom.mine"), report.findings.map { it.ruleId })
        assertEquals(listOf("test.custom.mine"), report.rules.map { it.id })
        assertEquals(1, report.rules.single().count)
    }

    @Test
    fun `ruleVersion moves with the settings and a source's version, not with key order`() {
        val config = tmp.resolve("config.json")
        val source = TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)))
        val engine = engine(source)
        val base = engine.current().ruleVersion
        assertEquals(8, base.length)

        config.writeText("""{"findings":{"payment":{"duplicateWindowMs":5000,"resubmitWindowMs":9000}}}""")
        val edited = engine.current().ruleVersion
        assertNotEquals(base, edited)
        config.writeText("""{"findings":{"payment":{"resubmitWindowMs":9000,"duplicateWindowMs":5000}}}   """)
        assertEquals(edited, engine.current().ruleVersion, "the same settings in another order")

        assertNotEquals(edited, engine(TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)), version = "test-2")).current().ruleVersion)
        assertNotEquals(edited, engine(source, templates = RouteTemplates.compile(listOf("/a/{b}"))).current().ruleVersion)
    }

    @Test
    fun `coverage is assembled per category, and one without a line is dropped and logged`() {
        val good = buildJsonObject { put("line", "2 things looked at."); put("things", 2) }
        val report = compute(
            engine(
                TestSource("one", listOf(rule("one.a", FindingSeverity.HIGH, "one")), coverage = good),
                TestSource("two", listOf(rule("two.a", FindingSeverity.HIGH, "two")), coverage = buildJsonObject { put("things", 1) }),
            ),
            emptyList(),
        )
        assertEquals(JsonObject(mapOf("one" to good)), report.coverage)
        assertEquals(1, logged.count { "two" in it && "line" in it }, logged.toString())
        assertEquals(listOf("one.a", "two.a"), report.rules.map { it.id }, "source order")
    }

    @Test
    fun `the category narrows findings, rules, counts and coverage, and limit cuts only the list`() {
        val one = TestSource("one", listOf(rule("one.a", FindingSeverity.HIGH, "one")), coverage = buildJsonObject { put("line", "x") })
        val two = TestSource("two", listOf(rule("two.a", FindingSeverity.INFO, "two")))
        val engine = engine(one, two)
        val rules = engine.current()
        val fold = engine.fold(rules, tmp) { _, _ -> null }
        listOf(row(1, "one.a"), row(2, "two.a"), row(3, "one.a", callId = "c9")).forEach(fold::add)
        val collected = engine.collect(fold)
        val report = engine.report("s", rules, collected, 3, "two", 1000)
        assertEquals(listOf("two.a"), report.rules.map { it.id })
        assertEquals(listOf(1, 0, 0, 1), listOf(report.findingCount, report.high, report.medium, report.info))
        assertNull(report.coverage)
        val cut = engine.report("s", rules, collected, 3, null, 1)
        assertEquals(1, cut.findings.size)
        assertEquals(2, cut.findingCount)
        assertEquals(3, cut.total)
    }

    @Test
    fun `an unknown category is refused naming the categories`() {
        assertEquals("unknown category 'pay'. Use one of: payment.", FindingsEngine.parseCategory("pay").exceptionOrNull()?.message)
        assertNull(FindingsEngine.parseCategory(" ").getOrThrow())
        assertEquals("payment", FindingsEngine.parseCategory("payment").getOrThrow())
    }
}
