package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.DiffCounts
import dev.inspector.daemon.service.DiffMarkdown
import dev.inspector.daemon.service.DiffOutcome
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRequest
import dev.inspector.daemon.service.DiffRoute
import dev.inspector.daemon.service.DiffSideInfo
import dev.inspector.daemon.service.SessionDiffService
import dev.inspector.daemon.service.ShapeBudget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `GET /api/diff.md`'s document: its header, its sections in order, escaped cells, and unknown never worded as removed. */
class DiffMarkdownTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-diff-md")
        writeProbeFixture(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun baseAgainstProbe(): DiffReport {
        val service = SessionDiffService(SessionRepository(DaemonConfig(dataDir = tmp)), RouteTemplates.NONE)
        return assertIs<DiffOutcome.Ok>(service.diff(DiffRequest(a = BASE_SESSION_ID, b = PROBE_SESSION_ID))).report
    }

    /** A report around [routes] built in memory, as the service would wrap them. */
    private fun report(routes: List<DiffRoute>): DiffReport {
        fun info(id: String) = DiffSideInfo(
            sessionId = id, appId = "com.example.projectx", startedAt = "2026-01-01T00:00:00.000Z",
            endedAt = "2026-01-01T00:10:00.000Z", total = 3, matched = 3, filterCarried = true, markers = emptyList(),
        )
        return DiffReport(
            a = info("sa"), b = info("sb"), aChosen = "given", sameApp = true, filter = "", routeCount = routes.size,
            counts = DiffCounts(
                added = routes.count { it.presence == "added" }, removed = routes.count { it.presence == "removed" },
                changed = routes.count { it.presence == "both" && it.changes.isNotEmpty() },
                unsure = routes.count { it.changes.isEmpty() && it.unsure == true },
                unchanged = routes.count { it.changes.isEmpty() && it.unsure != true },
            ),
            shapeBudget = ShapeBudget(0, 0, 1, false), routes = routes,
        )
    }

    @Test
    fun `the header says what was compared under which rules, and the sections come in order`() {
        val text = DiffMarkdown.render(baseAgainstProbe())
        val lines = text.lines()
        assertEquals("# Session diff: $PROBE_SESSION_ID against $BASE_SESSION_ID", lines.first())
        assertTrue(lines.any { it.startsWith("- **A (before):** `$BASE_SESSION_ID` · com.example.projectx 1.4.2 · iPhone 16 Pro · 2025-12-30T09:00:00.000Z · 12 calls") }, text)
        assertTrue("- **Filter:** none" in lines)
        assertTrue("- **Routes:** 14 — 7 added, 1 removed, 4 changed, 1 with unknown keys, 1 unchanged" in lines, text)
        assertTrue(text.contains("a key is never called removed because of it."))
        val sections = listOf(
            "## Changed routes", "## Routes added", "## Routes removed", "## Routes with unknown keys", "## Unchanged routes",
        ).map { lines.indexOf(it) }
        assertTrue(sections.all { it > 0 } && sections == sections.sorted(), sections.toString())
        assertFalse(lines.any { it.startsWith(">") }, "a blockquote with one app and an ended B")
    }

    @Test
    fun `each blockquote appears only when it applies`() {
        val base = baseAgainstProbe()
        val text = DiffMarkdown.render(base.copy(sameApp = false, b = base.b.copy(endedAt = null)))
        assertTrue("> A and B are different apps." in text.lines())
        assertTrue("> B has no recorded end; this compares the 16 calls it held when the diff ran." in text.lines())
    }

    @Test
    fun `the truncated feed reads unknown, and removed appears in no line about it`() {
        val feed = DiffMarkdown.render(baseAgainstProbe()).lines().filter { "/v3/shop/feed" in it }
        assertTrue(feed.isNotEmpty())
        assertTrue(feed.any { "unknown (1 truncated)" in it }, feed.toString())
        assertTrue(feed.none { "removed" in it.lowercase() }, feed.toString())
    }

    @Test
    fun `a route whose only news is an unsure key has its own section, and removed is in no line about it`() {
        val (a, b) = qaPair()
        val lines = DiffMarkdown.render(report(diffInMemory(a, b))).lines()
        assertTrue("- **Routes:** 2 — 0 added, 0 removed, 0 changed, 1 with unknown keys, 1 unchanged" in lines, lines.joinToString("\n"))
        val section = lines.indexOf("## Routes with unknown keys")
        val unchanged = lines.indexOf("## Unchanged routes")
        val product = lines.indexOfFirst { it.startsWith("| GET api.example.invalid/v1/product |") }
        assertTrue(product in section until unchanged, "the route is not in its section: ${lines.joinToString("\n")}")
        assertTrue(lines.subList(unchanged, lines.size).none { "/v1/product" in it }, "the unsure route listed as unchanged")
        val block = lines.subList(lines.indexOf("### GET api.example.invalid/v1/product"), unchanged)
        assertTrue(block.any { it.startsWith("    - `\$.price` is in 2 of A's 2 whole bodies and none of B's 1") }, block.joinToString("\n"))
        assertTrue(lines.subList(section, unchanged).none { "removed" in it.lowercase() }, "a line about the unsure route says removed")
    }

    @Test
    fun `unsure keys and redacted keys sit under their own headings, never under removed`() {
        val a = DiffSideRows().apply {
            call(res = """{"next":"p2","token":"‹redacted›"}""", redacted = listOf("body:res:\$.token"))
        }
        val b = DiffSideRows().apply {
            call(res = """{"token":"t"}""")
            call(res = """{"next":"""", resBytes = 900_000, resTruncated = true)
            call(path = "/v1/other")
        }
        val lines = DiffMarkdown.render(report(diffInMemory(a, b))).lines()
        fun headingAbove(needle: String): String {
            val at = lines.indexOfFirst { needle in it && it.startsWith("    - ") }
            assertTrue(at > 0, "no list entry for $needle")
            return lines.subList(0, at).last { it.startsWith("  - ") }
        }
        assertEquals("  - Unknown — capture did not keep every body:", headingAbove("`\$.next`"))
        assertEquals("  - Redacted — not compared:", headingAbove("`\$.token`"))
        assertTrue(lines.none { "`\$.next`" in it && "removed" in it.lowercase() })
    }

    @Test
    fun `a route holding a pipe is escaped in every table`() {
        val a = DiffSideRows().apply {
            call(path = "/c|b"); call(path = "/rem|b"); call(path = "/same|b")
        }
        val b = DiffSideRows().apply {
            call(path = "/c|b"); call(path = "/c|b"); call(path = "/add|b"); call(path = "/same|b")
        }
        val text = DiffMarkdown.render(report(diffInMemory(a, b)))
        val rows = text.lines().filter { it.startsWith("| ") && "|b" in it }
        assertEquals(4, rows.size, text)
        for (row in rows) assertFalse(Regex("""[^\\]\|b""").containsMatchIn(row), "an unescaped pipe: $row")
    }
}
