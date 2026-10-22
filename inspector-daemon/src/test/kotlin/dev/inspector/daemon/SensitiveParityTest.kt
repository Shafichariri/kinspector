package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.model.InspectorJson
import dev.inspector.model.NetworkTransaction
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `category=sensitive` on REST and `list_findings` over a 1203-row session with bodies: every row
 * counted, past the transactions route's clamp of 1000, and the same ids in the same order on both.
 * Shown red reading through `queryTransactions` (`expected <1203> but was <1000>`).
 */
class SensitiveParityTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-sensitive-parity")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        val writer = SessionWriter(config.sessionsDir.resolve(SESSION), clientInfo().toSessionMeta(SESSION, "2026-10-22T08:00:00.000Z"))
        for (i in 0 until 1203) {
            // The oldest row alone carries a card; a page of the newest 1000 would never see it.
            val body = if (i == 0) """{"card":"4000123412341234"}""" else """{"n":$i,"password":"x"}"""
            val bytes = body.toByteArray()
            val row = NetworkTransaction(
                id = "r" + i.toString().padStart(7, '0'), ts = "2026-10-22T08:00:00.000Z", mono = 1_000L + i, method = "POST",
                scheme = "https", host = "api.example.invalid", path = "/v1/items/${i % 7}/notes", status = 201,
                callId = "c-$i", reqBytes = bytes.size.toLong(), reqContentType = "application/json",
            )
            writer.append(row, bytes, null)
        }
        writer.close()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `REST and list_findings agree on a 1203-row session, every row counted`() = runBlocking {
        val daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${config.port}"
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get("$base/api/server").status.value }.getOrNull() != 200) delay(50)
            }
            val rest = InspectorJson.decodeFromString(
                FindingsReport.serializer(),
                http.get("$base/api/sessions/$SESSION/findings?category=sensitive&limit=200").bodyAsText(),
            )
            val mcp = InspectorJson.decodeFromString(
                FindingsReport.serializer(),
                mcpCallTool(McpTools(config), "list_findings", """{"session":"$SESSION","category":"sensitive","limit":200}"""),
            )
            assertEquals(1203, rest.total)
            assertEquals(1203, (rest.coverage!!.getValue("sensitive").toString().substringAfter("\"rows\":").substringBefore(',')).toInt())
            assertEquals(rest.findings.map { it.id }, mcp.findings.map { it.id })
            assertEquals(listOf("r0000000"), rest.findings.single { it.ruleId == "sensitive.card-number" }.txnIds)
            assertEquals(1202, rest.findings.single { it.ruleId == "sensitive.field.secret" }.txnCount)
        } finally {
            http.close()
            daemon.stop()
        }
    }

    private companion object {
        const val SESSION = "sensitive-long"
    }
}
