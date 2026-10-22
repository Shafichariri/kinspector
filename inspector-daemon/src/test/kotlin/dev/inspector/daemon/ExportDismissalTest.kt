package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.service.ExportMarkdown
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.Har
import dev.inspector.daemon.service.SessionExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A dismissed finding in every export: listed with its reason in the JSON (and so the HTML, which
 * embeds it), the Markdown and the HAR note, its hint masked. Beside `ExportTest`, whose file is at
 * its size budget. Shown red with dismissed findings filtered out of the export.
 */
class ExportDismissalTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-export-dismissal")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun build(dir: Path) = SessionExport.build(
        dir, repository, config.routeTemplates, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates), "9.9.9",
        Instant.parse("2026-10-10T12:00:00Z"),
    )

    @Test
    fun `a dismissed finding is listed with its reason in the JSON, Markdown and HAR forms`() {
        val id = "2026-08-16T10-14-02_app_dev_debug"
        val writer = SessionWriter(config.sessionsDir.resolve(id), clientInfo().toSessionMeta(id, "2026-08-16T10:14:02.000Z"))
        listOf(mutation("aaaa1111", 1_000, "c-a"), mutation("bbbb2222", 2_200, "c-b")).forEach { writer.append(it.row, it.body, null) }
        writer.append(
            txn("cccc3333", path = "/v1/profile", mono = 3_000).copy(method = "POST", reqContentType = "application/json", reqBytes = 30),
            """{"card":"4000123412341234"}""".toByteArray(), null,
        )
        writer.close()
        val dir = writer.sessionDir

        val card = build(dir).findings.findings.single { it.ruleId == "sensitive.card-number" }
        assertEquals("•••• 1234", card.evidence.single().masked)
        Files.writeString(
            tmp.resolve("config.json"),
            """{"findingDismissals":[{"ruleId":"${card.ruleId}","route":"${card.route}","location":"${card.location}",""" +
                """"path":"${card.path}","reason":"a test fixture card","at":"2026-10-22"}]}""",
        )
        val document = build(dir)
        assertEquals(1, document.findings.dismissedCount)
        val exported = json.decodeFromJsonElement(
            FindingsReport.serializer(),
            json.parseToJsonElement(SessionExport.toJson(document)).jsonObject.getValue("findings"),
        )
        assertEquals("a test fixture card", exported.findings.single { it.id == card.id }.dismissed?.reason)

        val markdown = ExportMarkdown.render(document)
        val section = markdown.substring(markdown.indexOf("## Findings"), markdown.indexOf("## Timeline"))
        assertContains(section, "1 dismissed, listed with their reasons.")
        assertContains(section, "- Card number in the request body.\n  dismissed: a test fixture card (2026-10-22)\n")
        assertFalse("4000123412341234" in section)

        val har = Har.export(
            meta = repository.readMeta(dir),
            transactions = repository.filterTransactions(dir),
            bodyOf = { txnId, side -> repository.readBody(dir, txnId, side) },
            creatorVersion = "9.9.9",
            findingsByTxn = FindingsEngine.byTxn(document.findings),
        )
        assertContains(har, "finding medium sensitive.card-number: Card number in the request body. (dismissed: a test fixture card)")
    }
}
