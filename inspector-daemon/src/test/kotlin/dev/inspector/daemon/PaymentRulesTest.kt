package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.Finding
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.daemon.service.PaymentSource
import dev.inspector.model.BodyOmission
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The six payment-safety rules, each trigger and boundary on its own fixture, then the payments
 * session the web probes use, counted whole.
 */
class PaymentRulesTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var sessions = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-payment-rules")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** Every finding over [calls], written as a fresh session, under [findings] as the config's key. */
    private fun report(vararg calls: Captured, findings: String? = null): FindingsReport {
        if (findings != null) tmp.resolve("config.json").writeText("""{"findings":$findings}""")
        val dir = writeFindingsSession(config, "s${++sessions}", calls.toList())
        val repository = SessionRepository(config)
        return FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates)).report(dir, null, 5000)
    }

    private fun FindingsReport.of(ruleId: String): List<Finding> = findings.filter { it.ruleId == ruleId }

    private fun found(ruleId: String, vararg calls: Captured, findings: String? = null): List<Finding> =
        report(*calls, findings = findings).of(ruleId)

    private val r1 = "payment.duplicate-mutation"
    private val r2 = "payment.key-reused-body-changed"
    private val r3 = "payment.retry-without-key"
    private val r4 = "payment.ambiguous-resubmit"
    private val r5 = "payment.key-missing-payment-path"
    private val r6 = "payment.retry-with-key"

    // --- R1 -------------------------------------------------------------------------------------

    private fun order(id: String, mono: Long, callId: String, body: String = """{"qty":1}""", key: String? = null, path: String = "/v1/orders", host: String = FINDINGS_HOST, method: String = "POST", attempt: Int = 1, truncated: Boolean = false, omitted: String? = null, redacted: List<String> = emptyList()) =
        mutation(id, mono, callId, path = path, body = body, key = key, host = host, method = method, attempt = attempt, truncated = truncated, omitted = omitted, redacted = redacted)

    @Test
    fun `R1 the same body on one route 1200 ms apart from two calls is one finding`() {
        val finding = found(r1, order("a0000001", 1_000, "a"), order("a0000002", 2_200, "b")).single()
        assertEquals(listOf("a0000001", "a0000002"), finding.txnIds)
        assertEquals("high", finding.severity)
        assertEquals("reqBody", finding.location)
        assertEquals(
            "POST https://$FINDINGS_HOST/v1/orders was sent 2 times in 1.2 s as separate calls with the same request body and no idempotency key.",
            finding.message,
        )
        assertEquals(
            listOf(
                "no Idempotency-Key or X-Idempotency-Key header on any call",
                "call 1: attempt 1, 201, +0 ms",
                "call 2: attempt 1, 201, +1200 ms; same request body as call 1, 9 B",
            ),
            finding.evidence.map { it.note },
        )
    }

    @Test
    fun `R1 the window is 3000 ms inclusive and chains consecutive gaps`() {
        assertEquals(1, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 4_000, "b")).size)
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 4_001, "b")).size)
        val chain = found(r1, order("a0000001", 0, "a"), order("a0000002", 2_500, "b"), order("a0000003", 5_000, "c")).single()
        assertEquals(3, chain.txnCount)
    }

    @Test
    fun `R1 needs two calls, the same bytes, both bodies whole, no key, a mutation and one origin`() {
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "a", attempt = 2)).size, "same callId")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", body = """{"qty":2}""")).size, "one byte different")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", truncated = true)).size, "truncated")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", omitted = BodyOmission.STREAMING)).size, "omitted")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", redacted = listOf("body:req:$.pin"))).size, "redacted path")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", key = "k-0000000000001")).size, "one with a key")
        assertEquals(
            0,
            found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", key = "‹redacted›", redacted = listOf("header:idempotency-key"))).size,
            "a redacted key is not a candidate",
        )
        assertEquals(0, found(r1, order("a0000001", 1_000, "a", method = "GET"), order("a0000002", 1_200, "b", method = "GET")).size, "two GETs")
        assertEquals(0, found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b", host = "other.example.invalid")).size, "two hosts")
        assertEquals(
            0,
            found(r1, order("a0000001", 1_000, "a"), order("a0000002", 1_200, "b"), findings = """{"payment":{"ignorePaths":["/v1/orders"]}}""").size,
            "ignored path",
        )
    }

    @Test
    fun `R1 two empty DELETEs are a duplicate`() {
        val finding = found(
            r1,
            mutation("a0000001", 1_000, "a", method = "DELETE", path = "/v1/cart", body = null),
            mutation("a0000002", 1_500, "b", method = "DELETE", path = "/v1/cart", body = null),
        ).single()
        assertContains(finding.evidence.last().note!!, "same request body as call 1, 0 B")
    }

    // --- R2 -------------------------------------------------------------------------------------

    @Test
    fun `R2 one key with two bodies is one finding with the key masked`() {
        val key = "k-PLANTED-7a1f-SECRET-9f3a"
        val finding = found(r2, order("a0000001", 1_000, "a", key = key), order("a0000002", 9_000, "b", key = key, body = """{"qty":2}""")).single()
        assertEquals("One Idempotency-Key was sent with 2 different request bodies across 2 calls.", finding.message)
        assertEquals("•••• 9f3a", finding.evidence.first().masked)
        assertEquals("idempotency-key", finding.evidence.first().path)
        assertEquals(listOf("body A, 9 B", "body B, 9 B"), finding.evidence.drop(1).map { it.note })
        assertEquals(listOf("reqHeader", "idempotency-key"), listOf(finding.location, finding.path))
    }

    @Test
    fun `R2 needs two known different bodies, and a redacted key proves nothing`() {
        val key = "k-0000000000001"
        assertEquals(0, found(r2, order("a0000001", 1_000, "a", key = key), order("a0000002", 9_000, "b", key = key)).size, "same body")
        assertEquals(
            0,
            found(r2, order("a0000001", 1_000, "a", key = key), order("a0000002", 9_000, "b", key = key, body = """{"qty":22}""", truncated = true)).size,
            "one unknown",
        )
        val placeholder = listOf("header:idempotency-key")
        assertEquals(
            0,
            found(r2, order("a0000001", 1_000, "a", key = "‹redacted›", redacted = placeholder), order("a0000002", 9_000, "b", key = "‹redacted›", body = """{"qty":2}""", redacted = placeholder)).size,
            "redacted key",
        )
    }

    @Test
    fun `R2 counts a retry inside one call, spans routes, and matches the header in any case`() {
        val key = "k-0000000000001"
        assertEquals(1, found(r2, order("a0000001", 1_000, "a", key = key), order("a0000002", 1_300, "a", key = key, body = """{"qty":2}""", attempt = 2)).size)
        val routes = found(r2, order("a0000001", 1_000, "a", key = key), order("a0000002", 9_000, "b", key = key, body = """{"qty":2}""", path = "/v1/refunds")).single()
        assertContains(routes.message, " on POST https://$FINDINGS_HOST/v1/orders and POST https://$FINDINGS_HOST/v1/refunds.")
        assertEquals(
            1,
            found(
                r2,
                mutation("a0000001", 1_000, "a", path = "/v1/orders", key = key, keyHeader = "idempotency-KEY"),
                mutation("a0000002", 9_000, "b", path = "/v1/orders", key = key, keyHeader = "X-IDEMPOTENCY-KEY", body = "{}"),
            ).size,
        )
    }

    // --- R3 and R6 ------------------------------------------------------------------------------

    private fun attempt(id: String, mono: Long, n: Int, status: Int?, key: String? = null, path: String = "/v1/orders", method: String = "POST", redacted: List<String> = emptyList(), callId: String = "r") =
        mutation(id, mono, callId, path = path, method = method, status = status, attempt = n, key = key, redacted = redacted)

    @Test
    fun `R3 a retry with no key, and R6 with the same key on both`() {
        val unsafe = report(attempt("a0000001", 1_000, 1, 503), attempt("a0000002", 1_300, 2, 201))
        assertEquals(1, unsafe.of(r3).size)
        assertEquals(0, unsafe.of(r6).size, "never both for one call")
        assertEquals(
            "POST https://$FINDINGS_HOST/v1/orders was retried automatically (2 attempts) without an idempotency key, so the server cannot tell the retry from a new request.",
            unsafe.of(r3).single().message,
        )
        assertEquals(listOf("attempt 1: 503; no key", "attempt 2: 201; no key"), unsafe.of(r3).single().evidence.map { it.note })

        val key = "k-PLANTED-0001-aaaa"
        val safe = report(attempt("a0000001", 1_000, 1, 503, key), attempt("a0000002", 1_300, 2, 201, key))
        assertEquals(0, safe.of(r3).size)
        val r6Finding = safe.of(r6).single()
        assertEquals("info", r6Finding.severity)
        assertEquals("POST https://$FINDINGS_HOST/v1/orders was retried automatically (2 attempts) with the same Idempotency-Key on every attempt.", r6Finding.message)
        assertEquals(listOf("•••• aaaa", "•••• aaaa"), r6Finding.evidence.map { it.masked })
    }

    @Test
    fun `R3 a 3xx predecessor is a redirect hop, not a retry`() {
        val report = report(attempt("a0000001", 1_000, 1, 302), attempt("a0000002", 1_100, 2, 201))
        assertEquals(0, report.of(r3).size + report.of(r6).size)
    }

    @Test
    fun `R3 a key on one attempt only, or a changed key, is no key to the server`() {
        assertEquals(1, found(r3, attempt("a0000001", 1_000, 1, 503), attempt("a0000002", 1_300, 2, 201, "k-0000000000002")).size)
        val changed = found(r3, attempt("a0000001", 1_000, 1, 503, "k-0000000000001"), attempt("a0000002", 1_300, 2, 201, "k-0000000000002")).single()
        assertEquals("attempt 2: 201; key changed from attempt 1", changed.evidence.last().note)
    }

    @Test
    fun `R6 two redacted keys are treated as kept, and say so`() {
        val placeholder = listOf("header:idempotency-key")
        val finding = found(r6, attempt("a0000001", 1_000, 1, 503, "‹redacted›", redacted = placeholder), attempt("a0000002", 1_300, 2, 201, "‹redacted›", redacted = placeholder)).single()
        assertContains(finding.evidence.first().note!!, "key redacted at capture; cannot confirm it was the same")
    }

    @Test
    fun `R3 a GET or an ignored path is never judged, and attempt 2 alone is judged on its own key`() {
        val get = report(attempt("a0000001", 1_000, 1, 503, method = "GET"), attempt("a0000002", 1_300, 2, 200, method = "GET"))
        assertEquals(0, get.of(r3).size + get.of(r6).size)
        val ignored = report(attempt("a0000001", 1_000, 1, 503), attempt("a0000002", 1_300, 2, 201), findings = """{"payment":{"ignorePaths":["orders"]}}""")
        assertEquals(0, ignored.of(r3).size)

        val alone = found(r6, attempt("a0000002", 1_300, 2, 201, "k-0000000000001"), findings = "{}").single()
        assertEquals("attempt 1 is not in the archive", alone.evidence.first().note)
        assertEquals(1, found(r3, attempt("a0000002", 1_300, 2, 201)).size)
    }

    // --- R4 -------------------------------------------------------------------------------------

    private fun timeout(id: String = "a0000001", mono: Long = 11_000, key: String? = null, error: String = "SocketTimeoutException: after 10000ms", callId: String = "failed") =
        mutation(id, mono, callId, path = "/v1/checkout", status = null, error = error, ms = 10_000, key = key)

    /** The failure ends at 21 000. */
    private fun resend(id: String, mono: Long, key: String? = null, path: String = "/v1/checkout", callId: String = "resend-$id") =
        mutation(id, mono, callId, path = path, key = key)

    @Test
    fun `R4 a timeout sent again as a new call is one finding, naming only the exception's class`() {
        val finding = found(r4, timeout(), resend("a0000002", 25_000)).single()
        assertEquals("medium", finding.severity)
        assertEquals(listOf("a0000001", "a0000002"), finding.txnIds)
        assertEquals(
            "POST https://$FINDINGS_HOST/v1/checkout ended in SocketTimeoutException, so the server may have processed it, and was sent again 4.0 s later as a new call without the same idempotency key.",
            finding.message,
        )
        assertEquals(
            listOf("transport failure: SocketTimeoutException", "sent again 4.0 s after the failure", "the same request body", "the failed call: no key", "the new call: no key"),
            finding.evidence.map { it.note },
        )
        assertEquals(null, finding.location)
    }

    @Test
    fun `R4 the window runs from the end of the failure, 60 000 ms inclusive`() {
        assertEquals(1, found(r4, timeout(), resend("a0000002", 81_000)).size)
        assertEquals(0, found(r4, timeout(), resend("a0000002", 81_001)).size)
        assertEquals(0, found(r4, timeout(), resend("a0000002", 20_999)).size, "started before the failure ended")
    }

    @Test
    fun `R4 a failure that never reached a server, a kept key, another route or a later success is no finding`() {
        for (never in PaymentSource.NEVER_SENT) {
            assertEquals(0, found(r4, timeout(error = "$never: api.example.invalid"), resend("a0000002", 25_000)).size, never)
        }
        assertEquals(
            setOf("UnknownHostException", "UnresolvedAddressException", "ConnectException", "NoRouteToHostException", "ConnectTimeoutException"),
            PaymentSource.NEVER_SENT,
        )
        assertEquals(0, found(r4, timeout(key = "k-0000000000003"), resend("a0000002", 25_000, key = "k-0000000000003")).size, "same key")
        assertEquals(1, found(r4, timeout(key = "k-0000000000003"), resend("a0000002", 25_000, key = "k-0000000000004")).size, "different keys")
        assertEquals(0, found(r4, timeout(), resend("a0000002", 25_000, path = "/v1/orders")).size, "another route")
        assertEquals(
            0,
            found(r4, timeout(), mutation("a0000002", 21_500, "failed", path = "/v1/checkout", attempt = 2), resend("a0000003", 25_000)).size,
            "the failed call later succeeded on a retry",
        )
    }

    @Test
    fun `R4 two resends name the earliest only, and a redacted key says so`() {
        val finding = found(r4, timeout(), resend("a0000003", 30_000), resend("a0000002", 25_000)).single()
        assertEquals(listOf("a0000001", "a0000002"), finding.txnIds)
        val redacted = found(
            r4,
            timeout(key = "‹redacted›").let { it.copy(row = it.row.copy(redacted = listOf("header:idempotency-key"))) },
            resend("a0000002", 25_000),
        ).single()
        assertContains(redacted.message, "; the key was redacted at capture, so whether it was reused is unknown.")
    }

    // --- R5 -------------------------------------------------------------------------------------

    @Test
    fun `R5 a mutation on a payment path without a key`() {
        val finding = found(r5, mutation("a0000001", 1_000, "a", path = "/v1/payments")).single()
        assertEquals(
            "POST https://$FINDINGS_HOST/v1/payments matches the payment path pattern \"payment\" and carried no idempotency key.",
            finding.message,
        )
        assertEquals(listOf("matched findings.payment.paymentPaths \"payment\"", "no Idempotency-Key or X-Idempotency-Key header"), finding.evidence.map { it.note })
        assertEquals(0, found(r5, mutation("a0000001", 1_000, "a", path = "/v1/payments", key = "k-0000000000001")).size, "with a key")
        assertEquals(0, found(r5, mutation("a0000001", 1_000, "a", path = "/v1/payments", method = "GET", body = null)).size, "GET")
        assertEquals(1, found(r5, mutation("a0000001", 1_000, "a", path = "/v1/PAYMENTS")).size, "any case")
        assertEquals(
            0,
            found(r5, mutation("a0000001", 1_000, "a", path = "/v1/payments", key = "‹redacted›", redacted = listOf("header:idempotency-key"))).size,
            "redacted key",
        )
    }

    @Test
    fun `R5 no payment paths finds nothing and says why, and a glob is a glob`() {
        val none = report(mutation("a0000001", 1_000, "a", path = "/v1/payments"), findings = """{"payment":{"paymentPaths":[]}}""")
        assertEquals(0, none.of(r5).size)
        assertEquals("no payment paths configured", none.rules.single { it.id == r5 }.note)

        val glob = report(
            mutation("a0000001", 1_000, "a", path = "/v1/charges"),
            mutation("a0000002", 9_000, "b", path = "/v1/charges/1"),
            findings = """{"payment":{"paymentPaths":["*/charges"]}}""",
        )
        assertEquals(listOf("a0000001"), glob.of(r5).flatMap { it.txnIds })
    }

    // --- the payments session -------------------------------------------------------------------

    @Test
    fun `the payments session gives each rule its count and says what it could not compare`() {
        val report = report(*paymentsSession().toTypedArray())
        assertEquals(
            listOf(r1 to 1, r2 to 1, r3 to 1, r4 to 1, r5 to 5, r6 to 1),
            report.rules.filter { it.category == "payment" }.map { it.id to it.count },
        )
        assertEquals(listOf(10, 3, 6, 1), listOf(report.findingCount, report.high, report.medium, report.info))
        assertEquals(18, report.total)
        assertEquals(
            listOf("b0000001", "b0000002", "b0000003", "b000000f", "b0000010"),
            report.of(r5).flatMap { it.txnIds }.sorted(),
        )
        val coverage = report.coverage!!.getValue("payment").jsonObject
        assertEquals("13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated).", coverage.getValue("line").jsonPrimitive.content)
        assertEquals(2, coverage.getValue("bodiesUnknown").jsonObject.getValue("truncated").jsonPrimitive.int)
        // Twelve rows carry a finding: the GETs, the offline pair and the redirect carry none.
        assertEquals(12, report.findings.flatMap { it.txnIds }.toSet().size)
    }

    @Test
    fun `no evidence line or id carries a key, a body, an error message or a digest`() {
        val report = report(*paymentsSession().toTypedArray())
        val text = dev.inspector.model.InspectorJson.encodeToString(FindingsReport.serializer(), report)
        for (secret in listOf("PLANTED", "acct-1", "qty", "after 10000ms", "api.example.invalid\"")) {
            assertTrue(secret !in text.replace("https://api.example.invalid/", ""), "$secret in the reply")
        }
        assertTrue(Regex("[0-9a-f]{20,}").find(text) == null, "a digest-length hex run in the reply")
    }

    /** The payments session the web probes read, from `ProbeFixture.kt`: 18 rows, 10 findings. */
    private fun paymentsSession(): List<Captured> = paymentsSessionCalls()
}
