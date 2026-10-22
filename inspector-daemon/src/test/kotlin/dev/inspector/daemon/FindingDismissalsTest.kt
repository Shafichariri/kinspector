package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.DismissalEntry
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.service.Dismissal
import dev.inspector.daemon.service.Finding
import dev.inspector.daemon.service.FindingDismissals
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.InspectorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `findingDismissals`: matched on rule, route, location and path exactly; applied to the reply after
 * the cache, so a dismissal moves no `ruleVersion` and rescans nothing; read live, so the daemon and a
 * second service standing for `inspector mcp` both see it on their next read; counted where hidden.
 * Shown red with the dismissals read once at start and with the rule version computed over them.
 */
class FindingDismissalsTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-finding-dismissals")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
        dir = writeFindingsSession(
            config, "payments",
            listOf(
                mutation("b0000001", 1_000, "c-dup-a"),
                mutation("b0000002", 2_200, "c-dup-b"),
                mutation("b0000003", 4_000, "c-retry", path = "/v1/transfers", status = 503),
                mutation("b0000004", 4_300, "c-retry", path = "/v1/transfers", attempt = 2),
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun service() = FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates))

    private fun FindingsService.read(includeDismissed: Boolean = false): FindingsReport = report(dir, null, 1000, includeDismissed)

    /** Writes the config file and moves its modified time on, so an edit is always seen as one. */
    private fun configure(text: String) {
        val file = tmp.resolve("config.json")
        val before = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(maxOf(before + 1_000, System.currentTimeMillis())))
    }

    /** `findingDismissals` naming [finding] by its four parts, as the page writes it. */
    private fun dismissalOf(finding: Finding, reason: String = "expected in this flow"): JsonObject = buildJsonObject {
        put("ruleId", finding.ruleId)
        put("route", finding.route)
        put("location", finding.location)
        put("path", finding.path)
        put("reason", reason)
        put("at", "2026-10-22")
    }

    private fun dismiss(vararg entries: JsonObject) =
        configure(buildJsonObject { put("findingDismissals", buildJsonArray { entries.forEach { add(it) } }) }.toString())

    private fun finding(ruleId: String, route: String?, location: String?, path: String?) = Finding(
        id = "f", ruleId = ruleId, category = "x", severity = "high", route = route, location = location, path = path,
        message = "m", txnIds = listOf("t"), callIds = listOf("c"), txnCount = 1, evidence = emptyList(),
    )

    @Test
    fun `a dismissal matches rule, route, location and path exactly, and a null only a null`() {
        val target = finding("sensitive.field.secret", "POST https://a.example.invalid/login", "reqBody", "$.password")
        val noPath = finding("payment.duplicate-mutation", "POST https://a.example.invalid/pay", null, null)
        val entry = DismissalEntry("sensitive.field.secret", "POST https://a.example.invalid/login", "reqBody", "$.password", "the form", "2026-10-22")
        val misses = listOf(
            entry.copy(ruleId = "sensitive.field.name"),
            entry.copy(route = "POST https://a.example.invalid/login2"),
            entry.copy(location = "resBody"),
            entry.copy(location = null),
            entry.copy(path = "$.Password"),
            entry.copy(path = null),
        )
        for (miss in misses) assertNull(FindingDismissals.apply(listOf(target), listOf(miss)).single().dismissed, miss.toString())
        assertEquals(Dismissal("the form", "2026-10-22"), FindingDismissals.apply(listOf(target), listOf(entry)).single().dismissed)

        val nullEntry = DismissalEntry("payment.duplicate-mutation", "POST https://a.example.invalid/pay", null, null, "batch job", null)
        assertEquals(Dismissal("batch job", null), FindingDismissals.apply(listOf(noPath), listOf(nullEntry)).single().dismissed)
        assertNull(FindingDismissals.apply(listOf(noPath), listOf(nullEntry.copy(location = "reqBody"))).single().dismissed)
        assertNull(FindingDismissals.apply(listOf(noPath), listOf(nullEntry.copy(path = ""))).single().dismissed, "an empty path is not null")
    }

    @Test
    fun `a dismissed finding is counted, not listed unless asked, and its rule still counts it`() {
        val before = service().read()
        val target = before.findings.first()
        assertEquals("high", target.severity)
        dismiss(dismissalOf(target))

        val after = service().read()
        assertEquals(before.findingCount - 1, after.findingCount)
        assertEquals(before.high - 1, after.high)
        assertEquals(listOf(before.medium, before.info), listOf(after.medium, after.info))
        assertEquals(0, before.dismissedCount)
        assertEquals(1, after.dismissedCount)
        assertEquals(before.findings.drop(1), after.findings, "the rest, in the same order")
        assertEquals(before.rules, after.rules, "a rule whose finding was set aside still says it found one")

        val all = service().read(includeDismissed = true)
        assertEquals(before.findings.map { it.id }, all.findings.map { it.id }, "listed in place, not moved")
        assertEquals(Dismissal("expected in this flow", "2026-10-22"), all.findings.first().dismissed)
        assertTrue(all.findings.drop(1).all { it.dismissed == null })
        assertEquals(after.findingCount, all.findingCount, "the counts do not depend on what is listed")
    }

    @Test
    fun `a dismissal applies on the next read here and in a second service, rescanning nothing`() {
        val daemon = service()
        val mcp = service()
        val before = daemon.read()
        assertEquals(before, mcp.read())
        val collects = daemon.collects to mcp.collects

        dismiss(dismissalOf(before.findings.first()))
        val daemonAfter = daemon.read()
        val mcpAfter = mcp.read()
        assertEquals(1, daemonAfter.dismissedCount)
        assertEquals(daemonAfter, mcpAfter, "a second process sees the dismissal with no restart")
        assertEquals(before.ruleVersion, daemonAfter.ruleVersion, "dismissals are not part of the rule version")
        assertEquals(collects, daemon.collects to mcp.collects, "the cached list was reused: no fold, no body read")

        configure("{}")
        assertEquals(before, daemon.read(), "removing it brings the finding back")
        assertEquals(before, mcp.read())
    }

    @Test
    fun `a bad dismissal in the file is skipped and named, and the good ones still apply`() {
        val target = service().read().findings.first()
        val bad = buildJsonObject { put("ruleId", target.ruleId); put("route", target.route) }
        dismiss(bad, dismissalOf(target))
        val report = service().read()
        assertEquals(1, report.dismissedCount)
        assertEquals("findingDismissals entry 1 needs a reason of 1 to 200 characters: why the finding is expected", report.configError)
    }

    @Test
    fun `list_findings hides dismissed findings unless includeDismissed, and always says how many`() {
        val target = service().read().findings.first()
        dismiss(dismissalOf(target))
        val tools = McpTools(config)
        fun call(args: String) = InspectorJson.decodeFromString(
            FindingsReport.serializer(),
            mcpCallTool(tools, "list_findings", """{"session":"payments"$args}"""),
        )
        val hidden = call("")
        assertEquals(1, hidden.dismissedCount)
        assertTrue(hidden.findings.none { it.id == target.id })
        assertEquals(target.id, call(""","includeDismissed":true""").findings.first().id)
        assertEquals("expected in this flow", call(""","includeDismissed":"true"""").findings.first().dismissed?.reason)
        assertTrue(call(""","includeDismissed":false""").findings.none { it.id == target.id })
    }

    @Test
    fun `no MCP tool can write a dismissal`() {
        // The page and the file are the only writers: an agent that could silence the detector in the
        // middle of the task it is checking would make it no detector. Nothing in mcp/ may reach the
        // config writer, and no tool takes a dismissal as an argument.
        val sources = Path.of("src/main/kotlin/dev/inspector/daemon/mcp").walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 5, "found the MCP sources: $sources")
        val writers = sources.filter { file -> listOf("ConfigFile", ".merge(", "api/config").any { it in file.readText() } }
        assertEquals(emptyList(), writers.map { it.fileName.toString() })

        val listed = mcpExchange(McpTools(config), """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            .single().getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
        val dismissing = listed.flatMap { tool ->
            val name = tool.getValue("name").jsonPrimitive.content
            val properties = tool.getValue("inputSchema").jsonObject.getValue("properties").jsonObject.keys
            (listOf(name) + properties.filter { it != "includeDismissed" }).filter { "dismiss" in it.lowercase() }.map { "$name: $it" }
        }
        assertEquals(emptyList(), dismissing)
        assertNotEquals(0, listed.size)
    }
}
