package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.service.Har
import dev.inspector.daemon.service.daemonVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Findings in the HAR, through the route: each entry a finding names carries one
 * `finding <severity> <ruleId>: <message>` note, and nothing else changes, so a session with no
 * findings exports exactly the HAR it did before findings existed. `HarTest`, unedited, pins that
 * call; this pins that the route makes it.
 */
class HarFindingsTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-har-findings")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        writeFindingsSession(
            config, "payments",
            listOf(
                mutation("b0000001", 1_000, "c-dup-a"),
                mutation("b0000002", 2_200, "c-dup-b", status = 200),
                getCall("b0000003", 3_000),
            ),
        )
        daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    @AfterTest
    fun tearDown() {
        http.close()
        daemon.stop()
        tmp.toFile().deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    private suspend fun har(session: String, filter: String? = null): String {
        val response = http.get(url("/api/sessions/$session/har")) { filter?.let { parameter("filter", it) } }
        assertEquals(200, response.status.value, response.bodyAsText())
        return response.bodyAsText()
    }

    private fun entries(text: String) = Json.parseToJsonElement(text).jsonObject
        .getValue("log").jsonObject.getValue("entries").jsonArray.map { it.jsonObject }

    private fun JsonObject.comment() = this["comment"]?.jsonPrimitive?.content

    @Test
    fun `a session with no findings exports the HAR it did before findings, byte for byte`() = runBlocking {
        val repository = SessionRepository(config)
        val dir = repository.resolve(PROBE_SESSION_ID)!!
        val base = Har.export(
            meta = repository.readMeta(dir),
            transactions = repository.filterTransactions(dir),
            bodyOf = { id, side -> repository.readBody(dir, id, side) },
            creatorVersion = daemonVersion(),
        )
        assertEquals(base, har(PROBE_SESSION_ID))

        // And that call itself adds nothing: an entry whose row has nothing to say has no comment,
        // as before findings existed. The equality above cannot see a change both sides share.
        val plain = repository.filterTransactions(dir).sortedBy { it.mono }.map { txn ->
            txn.attempt == 1 && txn.error == null && !txn.reqBodyTruncated && !txn.resBodyTruncated &&
                txn.reqBodyOmitted == null && txn.resBodyOmitted == null && txn.redacted.isEmpty()
        }
        val comments = entries(base).map { it.comment() }
        assertTrue(plain.count { it } > 0, "the probe session has plain rows to check")
        assertEquals(emptyList(), comments.filterIndexed { i, comment -> plain[i] && comment != null })
    }

    @Test
    fun `each entry a finding names carries it, in report order, after its own notes`() = runBlocking {
        val entries = entries(har("payments"))
        val first = entries[0].comment()!!
        assertEquals(
            "finding high payment.duplicate-mutation: POST https://$FINDINGS_HOST/v1/payments was sent 2 times in 1.2 s " +
                "as separate calls with the same request body and no idempotency key.; " +
                "finding medium payment.key-missing-payment-path: POST https://$FINDINGS_HOST/v1/payments matches the " +
                "payment path pattern \"payment\" and carried no idempotency key.",
            first,
        )
        assertNull(entries[2].comment(), "a GET no finding names is untouched")
    }

    @Test
    fun `a filtered HAR keeps the whole session's findings on the entries it keeps`() = runBlocking {
        // status:200 drops b0000001, the other half of the duplicate; b0000002 still carries it.
        val entries = entries(har("payments", "status:200"))
        assertEquals(2, entries.size)
        assertContains(entries[0].comment()!!, "finding high payment.duplicate-mutation: ")
    }
}
