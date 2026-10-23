package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.Finding
import dev.inspector.daemon.service.FindingRow
import dev.inspector.daemon.service.FindingSession
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.TimelineEntry
import dev.inspector.model.timeline
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FindingSession.TIMELINE` is a total order however `seq` is mixed in one session.
 *
 * Mixed `seq` is reachable without editing an archive: a 1.2.0 daemon re-encodes every row through a
 * model with no `seq` field, so it writes none, and a 1.3.0 or later daemon that resumes that session
 * inside the five-minute grace appends rows that carry one. The comparator used to compare `seq` only
 * when both rows carried it, which is intransitive, so a group's order depended on file order and the
 * JDK's sort could throw on a group of 32 or more. [mixedGroup]'s seed 4 is the first seed that threw.
 */
class FindingsTimelineOrderTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var sessions = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-timeline")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun row(id: String, mono: Long, seq: Long?) = FindingRow(id, "c-$id", mono, seq)

    /**
     * [n] rows in one millisecond, a third of them without `seq`. Seeded `java.util.Random`, so the
     * rows are the same on every JDK; ids are unique because each ends in its index.
     */
    private fun mixedGroup(seed: Long, n: Int = 32): List<FindingRow> {
        val random = Random(seed)
        return List(n) { i ->
            val id = "%02d".format(random.nextInt(100)) + i
            row(id, 1_000, if (random.nextInt(3) == 0) null else random.nextInt(1000).toLong())
        }
    }

    /** Written out rather than through the comparator: rows without `seq` by id, then the rest by `seq`. */
    private fun expectedOrder(rows: List<FindingRow>): List<String> =
        rows.groupBy { it.mono }.toSortedMap().values.flatMap { tie ->
            tie.filter { it.seq == null }.sortedBy { it.id } +
                tie.filter { it.seq != null }.sortedWith(compareBy({ it.seq }, { it.id }))
        }.map { it.id }

    private fun sorted(rows: List<FindingRow>) = rows.sortedWith(FindingSession.TIMELINE).map { it.id }

    @Test
    fun `the roadmap's cycle sorts to one order from every input order`() {
        val rows = listOf(row("z", 1_000, 0), row("a", 1_000, 1), row("m", 1_000, null))
        val orders = permutations(rows).map(::sorted).toSet()
        assertEquals(setOf(listOf("m", "z", "a")), orders)
    }

    @Test
    fun `the comparator is antisymmetric and transitive over every small mixed case`() {
        val rows = buildList {
            for (mono in listOf(1L, 2L)) for (seq in listOf(null, 0L, 1L)) for (id in listOf("a", "m", "z")) add(row(id, mono, seq))
        }
        val c = FindingSession.TIMELINE
        val faults = ArrayList<String>()
        for (a in rows) for (b in rows) {
            if (Integer.signum(c.compare(a, b)) != -Integer.signum(c.compare(b, a))) faults += "antisymmetry $a $b"
            if ((c.compare(a, b) == 0) != (a == b)) faults += "a tie between distinct rows $a $b"
            for (d in rows) if (c.compare(a, b) < 0 && c.compare(b, d) < 0 && c.compare(a, d) >= 0) faults += "cycle $a < $b < $d"
        }
        assertTrue(faults.isEmpty(), "${faults.size} faults, first ${faults.take(3)}")
    }

    @Test
    fun `a group of 32 rows with mixed seq sorts, and to one order whatever the input order`() {
        val rows = mixedGroup(seed = 4)
        assertTrue(rows.any { it.seq == null } && rows.any { it.seq != null }, "the group mixes seq")
        val expected = expectedOrder(rows)
        assertEquals(expected, sorted(rows), "file order")
        for (shuffle in 0L until 50L) assertEquals(expected, sorted(rows.shuffled(Random(shuffle))), "shuffle $shuffle")
    }

    @Test
    fun `rows that all carry seq go by mono, then seq, then id, as before`() {
        // Id order disagrees with seq order in the first millisecond, so a key that dropped seq fails.
        val rows = listOf(row("b", 2, 1), row("a", 1, 9), row("x", 1, 3), row("d", 2, 1), row("c", 1, 3))
        assertEquals(listOf("c", "x", "a", "b", "d"), sorted(rows))
        assertEquals(listOf("c", "x", "a", "b", "d"), sorted(rows.reversed()))
    }

    /**
     * The shape the resume path writes: rows from the daemon without `seq` sit before the resumed
     * daemon's rows in the file, so in a tie `timeline()` keeps them first and orders the rest by
     * `seq`. Ids rise with file order, because between two rows without `seq` the timeline keeps file
     * order and findings, which hold no file position, go by id.
     */
    @Test
    fun `calls are in the order timeline() draws them`() {
        val calls = listOf(
            mutation("a01", 1_000, "a01"), mutation("a02", 1_000, "a02"), mutation("a03", 2_000, "a03"),
            mutation("b09", 1_000, "b09", seq = 9), mutation("b07", 1_000, "b07", seq = 7),
            mutation("b08", 2_000, "b08", seq = 8), mutation("b05", 3_000, "b05", seq = 5), mutation("b04", 3_000, "b04", seq = 4),
        ).map { it.row }
        val drawn = timeline(calls, emptyList()).map { (it as TimelineEntry.Call).txn.id }
        assertEquals(listOf("a01", "a02", "b07", "b09", "a03", "b08", "b04", "b05"), drawn)
        val rows = calls.map(FindingRow::of)
        assertEquals(drawn, sorted(rows))
        for (shuffle in 0L until 20L) assertEquals(drawn, sorted(rows.shuffled(Random(shuffle))), "shuffle $shuffle")
    }

    // --- Each sort that uses the comparator, through a real session in two file orders ----------

    private fun findings(calls: List<Captured>): List<Finding> {
        val dir = writeFindingsSession(config, "s${++sessions}", calls)
        val engine = FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates)
        return FindingsService(SessionRepository(config), engine).report(dir, null, 5000).findings
    }

    /**
     * R2 sorts its group itself (`PaymentRules`) for the evidence lines, and the builder sorts the
     * members again (`Findings.kt`) for `txnIds`.
     */
    @Test
    fun `key reused with a changed body lists its 32 calls in one order from either file order`() {
        val rows = mixedGroup(seed = 4)
        val calls = rows.mapIndexed { i, r ->
            mutation(r.id, r.mono, r.callId, path = "/v1/orders", key = "k-0000000000001", body = """{"qty":${i % 2}}""", seq = r.seq)
        }
        val expected = expectedOrder(rows)
        for ((name, file) in listOf("file order" to calls, "reversed" to calls.reversed())) {
            val finding = findings(file).single { it.ruleId == "payment.key-reused-body-changed" }
            assertEquals(expected, finding.txnIds, "txnIds, $name")
            assertEquals(expected, finding.evidence.drop(1).map { it.txnId }, "evidence, $name")
        }
    }

    /**
     * `SensitiveSource` keeps the first five evidence lines in timeline order as rows arrive, so in
     * the reversed file the earliest rows come last and must displace the ones it holds.
     */
    @Test
    fun `a secret field's evidence lists its 32 calls in one order from either file order`() {
        val rows = mixedGroup(seed = 4)
        val calls = rows.map { r ->
            mutation(r.id, r.mono, r.callId, path = "/v1/login", body = """{"password":"pw-${r.id}"}""", seq = r.seq)
        }
        val expected = expectedOrder(rows)
        for ((name, file) in listOf("file order" to calls, "reversed" to calls.reversed())) {
            val finding = findings(file).single { it.ruleId == "sensitive.field.secret" }
            assertEquals(expected, finding.txnIds, "txnIds, $name")
            assertEquals(expected.take(5), finding.evidence.map { it.txnId }, "evidence, $name")
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }
}
