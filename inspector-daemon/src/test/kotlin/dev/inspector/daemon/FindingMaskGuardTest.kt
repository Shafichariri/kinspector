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
 * The engine's masking guard: a hint with fewer than four dots or more than ten other characters is
 * replaced by the engine, whichever source sent it, so a source that forgets to mask leaks a few
 * characters, not a card. Shown red both ways: with the check removed a raw number survives, and with
 * every hint rejected the valid masks fail.
 */
class FindingMaskGuardTest {

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
    fun `a hint the source forgot to mask is replaced, and a masked one passes`() {
        for ((hint, expected) in listOf(
            "4111111111111111" to FindingMask.DOTS,
            "•••• abcdefghijk" to FindingMask.DOTS,
            "••• 4242" to FindingMask.DOTS,
            "•••• 4242" to "•••• 4242",
            "GB82 •••• 5432" to "GB82 •••• 5432",
            "••••" to "••••",
        )) {
            val source = TestSource("test", listOf(rule("test.a", FindingSeverity.HIGH)), masked = hint)
            val report = compute(engine(source), listOf(row(1, "test.a")))
            assertEquals(expected, report.findings.single().evidence.single().masked, hint)
        }
        assertTrue(logged.any { "test.a" in it && "masked" in it }, logged.toString())
    }

    @Test
    fun `the mask helper shows a tail of four, and nothing of a short value`() {
        assertEquals("•••• 9f3a", FindingMask.tail("k-PLANTED-7a1f-SECRET-9f3a"))
        assertEquals("••••", FindingMask.tail("short-key"))
        assertTrue(FindingMask.isMasked(FindingMask.tail("k-PLANTED-7a1f-SECRET-9f3a")))
        assertEquals(JsonPrimitive("•••• 9f3a"), JsonPrimitive(FindingMask.tail("x".repeat(20) + "9f3a")))
    }
}
