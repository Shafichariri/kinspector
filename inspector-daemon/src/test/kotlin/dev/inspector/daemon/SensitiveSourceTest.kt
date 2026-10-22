package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SensitiveSettings
import dev.inspector.daemon.service.FindingRow
import dev.inspector.daemon.service.FindingSession
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.service.Finding
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.daemon.service.SensitiveSource
import dev.inspector.model.NetworkTransaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Base64
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sensitive-data source through the real findings service: each location and content type, what
 * is skipped, the strongest claim per path, grouping, the raises, coverage, and the fold. Shown red
 * with the raise applied to response bodies, the dedupe removed, and the engine checking ids against
 * the static catalogue (the custom rule's finding disappears).
 */
class SensitiveSourceTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private var nextMono = 1_000L
    private var nextId = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-sensitive-source")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    // --- harness ------------------------------------------------------------------------------------

    private class Call(val row: NetworkTransaction, val req: ByteArray?, val res: ByteArray?)

    private fun call(
        path: String = "/v1/things",
        query: String? = null,
        method: String = "POST",
        scheme: String = "https",
        host: String = HOST,
        req: String? = null,
        reqType: String? = if (req != null) "application/json" else null,
        res: String? = null,
        resType: String? = if (res != null) "application/json" else null,
        reqHeaders: Map<String, List<String>> = emptyMap(),
        resHeaders: Map<String, List<String>> = emptyMap(),
        redacted: List<String> = emptyList(),
        reqTruncated: Boolean = false,
        resOmitted: String? = null,
        resBytes: Long? = null,
        mono: Long? = null,
        id: String? = null,
        reqRaw: ByteArray? = null,
    ): Call {
        val reqBytes = reqRaw ?: req?.toByteArray()
        val resBody = res?.toByteArray()
        val row = NetworkTransaction(
            id = id ?: "t" + (nextId++).toString().padStart(7, '0'),
            ts = "2026-10-22T08:00:00.000Z",
            mono = mono ?: nextMono.also { nextMono += 100 },
            method = method,
            scheme = scheme,
            host = host,
            path = path,
            query = query,
            status = 200,
            callId = "c-${nextId}",
            reqBytes = reqBytes?.size?.toLong() ?: 0,
            resBytes = resBytes ?: resBody?.size?.toLong() ?: 0,
            reqHeaders = reqHeaders,
            resHeaders = resHeaders,
            reqContentType = reqType,
            resContentType = resType,
            reqBodyTruncated = reqTruncated,
            resBodyOmitted = resOmitted,
            redacted = redacted,
        )
        val keptReq = if (reqTruncated) reqBytes?.copyOf(reqBytes.size / 2) else reqBytes
        return Call(row, keptReq, if (resOmitted != null) null else resBody)
    }

    private fun write(vararg calls: Call, id: String = "s1"): Path {
        val writer = SessionWriter(config.sessionsDir.resolve(id), clientInfo().toSessionMeta(id, "2026-10-22T08:00:00.000Z"))
        calls.forEach { writer.append(it.row, it.req, it.res) }
        writer.close()
        return writer.sessionDir
    }

    private fun configure(text: String) {
        val file = tmp.resolve("config.json")
        val before = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(maxOf(before + 1_000, System.currentTimeMillis())))
    }

    private fun service() = FindingsService(repository, FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates))

    private fun report(dir: Path): FindingsReport = service().report(dir, "sensitive", 5000)

    private fun read(vararg calls: Call): FindingsReport = report(write(*calls))

    private fun FindingsReport.one(ruleId: String): Finding = findings.single { it.ruleId == ruleId }

    private fun FindingsReport.ids() = findings.map { "${it.ruleId} ${it.location} ${it.path}" }.sorted()

    private fun jwt(alg: String = "HS256") =
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"$alg","typ":"JWT"}""".toByteArray()) + ".eyJzdWIiOiIxIn0.c2lnbmF0dXJl"

    // --- locations and content types ---------------------------------------------------------------

    @Test
    fun `each location is scanned and reported masked`() {
        val report = read(
            call(path = "/v1/cards/4000123412341234", method = "GET"),
            call(query = "api_key=k-not-real&page=2", method = "GET"),
            call(req = """{"pay":{"number":"4000 1234 1234 1234"}}"""),
            call(res = """{"amount":"12.00","iban":"GB82WEST12345698765432"}""", method = "GET"),
            call(query = "t=${jwt()}", method = "GET"),
        )
        assertEquals(
            listOf(
                "sensitive.card-number reqBody $.pay.number", "sensitive.card-number url null",
                "sensitive.iban resBody $.iban", "sensitive.jwt-in-url query t", "sensitive.secret-in-url query api_key",
            ),
            report.ids(),
        )
        assertEquals("•••• 1234", report.findings.single { it.location == "url" }.evidence.single().masked)
        assertEquals("GB82 •••• 5432", report.one("sensitive.iban").evidence.single().masked)
        assertEquals("•••• HS256", report.one("sensitive.jwt-in-url").evidence.single().masked)
        assertEquals("••••", report.one("sensitive.secret-in-url").evidence.single().masked)
        assertEquals("POST https://$HOST/v1/things", report.findings.single { it.location == "reqBody" }.route)
    }

    @Test
    fun `content types route the body as form, text, no type or binary`() {
        val report = read(
            call(req = "user=a&password=hunter2&card=4000123412341234", reqType = "application/x-www-form-urlencoded"),
            call(req = "pay with 4000123412341234 today", reqType = "text/plain; charset=utf-8"),
            call(req = "<x>GB82WEST12345698765432</x>", reqType = "application/xml"),
            call(req = "iban GB82WEST12345698765432", reqType = null),
            call(reqRaw = byteArrayOf(1, 2, 3, 0xFF.toByte()), reqType = "application/octet-stream"),
            call(reqRaw = "4000123412341234".toByteArray(), reqType = "application/pdf"),
        )
        assertEquals(
            listOf(
                "sensitive.card-number reqBody card", "sensitive.card-number reqBody null",
                "sensitive.field.secret reqBody password", "sensitive.iban reqBody null",
            ),
            report.ids(),
        )
        assertEquals(2, report.one("sensitive.iban").txnCount, "xml and no content type")
        val coverage = report.coverage!!.getValue("sensitive").toString()
        assertTrue("\"bodies\":6" in coverage && "\"scanned\":4" in coverage && "\"binary\":2" in coverage, coverage)
    }

    @Test
    fun `a truncated or unparseable body gets value rules only, with a note, and is counted`() {
        val body = """{"card":"4000123412341234","password":"x","pad":"${"y".repeat(60)}"}"""
        val report = read(
            call(req = body, reqTruncated = true),
            call(req = """{"password":"x","note":"4000123412341234",}"""),
        )
        assertEquals(listOf("sensitive.card-number reqBody null"), report.ids(), "no field names from a prefix or a broken body")
        assertEquals(listOf("truncated: path unknown", "not valid JSON: path unknown"), report.findings.single().evidence.map { it.note })
        val coverage = report.coverage!!.getValue("sensitive")
        assertEquals(
            "Scanned 1 of 2 bodies · 1 truncated (values only) · no first-party hosts set, so nothing is raised for where data was sent",
            (coverage as kotlinx.serialization.json.JsonObject).getValue("line").jsonPrimitive.content,
        )
    }

    @Test
    fun `omitted, missing, binary and over-limit bodies are counted, so no findings never means not looked`() {
        val big = """{"a":"${"x".repeat(1024 * 1024)}","card":"4000123412341234"}"""
        val report = read(
            call(res = "{}", resOmitted = "contentType", resBytes = 900, method = "GET"),
            call(res = "{}", method = "GET").let { Call(it.row.copy(resBytes = 50), it.req, null) },
            call(reqRaw = byteArrayOf(0xC3.toByte(), 0x28), reqType = "application/json"),
            call(req = "4000123412341234" + " ".repeat(1024 * 1024), reqType = "text/plain"),
            call(req = big),
        )
        val line = (report.coverage!!.getValue("sensitive") as kotlinx.serialization.json.JsonObject).getValue("line").jsonPrimitive.content
        assertEquals(
            "Scanned 0 of 5 bodies · 2 not captured · 1 binary · 2 over the 1 MiB scan limit (values only) · " +
                "no first-party hosts set, so nothing is raised for where data was sent",
            line,
        )
        assertEquals(listOf("over the 1 MiB scan limit: path unknown"), report.findings.flatMap { f -> f.evidence.map { it.note } }.distinct())
        assertEquals(1, report.one("sensitive.card-number").txnCount, "the big JSON's card is past the first MiB")
    }

    // --- what is skipped -------------------------------------------------------------------------

    @Test
    fun `redacted paths, headers, query names and the placeholder are skipped`() {
        val report = read(
            call(
                req = """{"card":{"number":"4000123412341234"},"cardholder":{"iban":"GB82WEST12345698765432"},"pin":"‹redacted›"}""",
                redacted = listOf("body:req:$.card"),
            ),
            call(req = """{"x":{"cvv":"123"}}""", redacted = listOf("body:$.x")),
            call(
                query = "token=abc&secret=‹redacted›", scheme = "http", method = "GET",
                reqHeaders = mapOf("Authorization" to listOf("Bearer abc")), redacted = listOf("header:authorization", "query:token"),
            ),
        )
        assertEquals(listOf("sensitive.iban reqBody $.cardholder.iban"), report.ids(), "\$.cardholder is not under \$.card")
    }

    @Test
    fun `one evidence per row and path, the strongest claim first, and transport beside it`() {
        val report = read(
            call(req = """{"card_number":"4000123412341234","password":"p","cvc":"123"}"""),
            call(query = "password=p", method = "GET"),
            call(query = "password=p", method = "GET", scheme = "http", host = "203.0.113.9"),
        )
        assertEquals(
            listOf(
                "sensitive.card-number reqBody $.card_number", "sensitive.credentials-over-http query password",
                "sensitive.field.payment reqBody $.cvc", "sensitive.field.secret reqBody $.password",
                "sensitive.secret-in-url query password", "sensitive.secret-in-url query password",
            ),
            report.ids(),
        )
    }

    @Test
    fun `array indices group into one finding whose evidence keeps the concrete paths`() {
        val items = (0 until 7).joinToString(",") { """{"iban":"GB82WEST12345698765432"}""" }
        val report = read(call(res = """{"items":[$items]}""", method = "GET"), call(res = """{"items":[$items]}""", method = "GET"))
        val finding = report.one("sensitive.iban")
        assertEquals("$.items[*].iban", finding.path)
        assertEquals(2, finding.txnCount)
        assertEquals(5, finding.evidence.size, "at most five")
        assertEquals(listOf("$.items[0].iban", "$.items[1].iban", "$.items[2].iban"), finding.evidence.take(3).map { it.path })
    }

    @Test
    fun `at most 1000 groups, and coverage says when the limit was reached`() {
        val keys = (0 until 1001).joinToString(",") { """"k${it}Password":"x"""" }
        val report = read(call(req = "{$keys}"))
        assertEquals(1000, report.findingCount)
        assertTrue("\"groupLimitReached\":true" in report.coverage!!.getValue("sensitive").toString())
    }

    @Test
    fun `a key that matches a value rule is written as a dot, so the token never reaches a path`() {
        val token = jwt()
        val report = read(call(res = """{"sessions":{"$token":{"password":"x"}}}""", method = "GET"))
        assertEquals("$.sessions.•.password", report.one("sensitive.field.secret").path)
        assertFalse(token in InspectorJsonText.of(report))
    }

    // --- severity --------------------------------------------------------------------------------

    @Test
    fun `an empty first-party list raises nothing, and a set one raises requests to other hosts only`() {
        val calls = arrayOf(
            call(req = """{"card":"4000123412341234","firstName":"A"}"""),
            call(res = """{"iban":"GB82WEST12345698765432"}""", method = "GET"),
            call(req = """{"card":"4000123412341234"}""", host = "api.mine.invalid"),
        )
        val dir = write(*calls)
        val plain = report(dir)
        assertEquals(listOf("medium", "info", "medium", "medium"), listOf(
            plain.findings.first { it.ruleId == "sensitive.card-number" && it.route!!.contains(HOST) }.severity,
            plain.one("sensitive.field.name").severity,
            plain.one("sensitive.iban").severity,
            plain.findings.first { it.ruleId == "sensitive.card-number" && it.route!!.contains("mine") }.severity,
        ))
        assertFalse(plain.findings.any { "outside firstPartyHosts" in it.message })

        configure("""{"findings":{"sensitive":{"firstPartyHosts":["*.mine.invalid"]}}}""")
        val raised = report(dir)
        assertEquals("high", raised.findings.first { it.ruleId == "sensitive.card-number" && it.route!!.contains(HOST) }.severity)
        assertEquals("medium", raised.one("sensitive.field.name").severity)
        assertEquals("medium", raised.one("sensitive.iban").severity, "a response is never raised for its host")
        assertEquals("medium", raised.findings.first { it.ruleId == "sensitive.card-number" && it.route!!.contains("mine") }.severity)
        assertTrue("\"firstPartyHosts\":1" in raised.coverage!!.getValue("sensitive").toString())
    }

    @Test
    fun `a findings rules severity sets the base and the raises still apply, and a URL raises once more`() {
        configure("""{"findings":{"rules":{"sensitive.card-number":{"severity":"info"}},"sensitive":{"firstPartyHosts":["other.invalid"]}}}""")
        val report = read(call(req = """{"card":"4000123412341234"}"""), call(path = "/v1/x/4000123412341234", method = "GET"))
        assertEquals("medium", report.findings.single { it.location == "reqBody" }.severity, "info, then one step for the host")
        assertEquals("high", report.findings.single { it.location == "url" }.severity, "info, a step for the URL and one for the host")
    }

    @Test
    fun `test card numbers and http to loopback are info and never raised`() {
        configure("""{"findings":{"sensitive":{"firstPartyHosts":["other.invalid"]}}}""")
        val report = read(
            call(path = "/v1/x/4242424242424242", method = "GET"),
            call(scheme = "http", host = "10.0.2.2", reqHeaders = mapOf("Cookie" to listOf("s=1")), method = "GET"),
            call(scheme = "http", host = "192.168.1.20", reqHeaders = mapOf("X-Api-Key" to listOf("k")), method = "GET"),
            call(scheme = "http", host = "203.0.113.9", reqHeaders = mapOf("Authorization" to listOf("Basic dXNlcjpwYXNz")), method = "GET"),
        )
        val card = report.one("sensitive.card-number")
        assertEquals("info", card.severity)
        assertEquals("a published test card number", card.evidence.single().note)
        val transport = report.findings.filter { it.ruleId == "sensitive.credentials-over-http" }.associate { it.route!!.substringAfter("//").substringBefore('/') to it.severity }
        assertEquals(mapOf("10.0.2.2" to "info", "192.168.1.20" to "high", "203.0.113.9" to "high"), transport)
        assertEquals("Basic ••••", report.findings.single { it.route!!.contains("203.0.113.9") }.evidence.single().masked)
        assertEquals("x-api-key", report.findings.single { it.route!!.contains("192.168") }.path)
    }

    @Test
    fun `http to a private address starts one step lower`() {
        val report = read(call(scheme = "http", host = "192.168.1.20", reqHeaders = mapOf("Cookie" to listOf("s=1")), method = "GET"))
        assertEquals("medium", report.one("sensitive.credentials-over-http").severity)
    }

    // --- rules, custom rules, the engine ---------------------------------------------------------

    @Test
    fun `rules lists every shipped rule and each custom rule, and a custom finding reaches the reply`() {
        configure(
            """{"findings":{"sensitive":{"valueRules":[
                {"id":"member-id","label":"Member number","shape":"AA-######-#","fields":["memberId"]},
                {"id":"staff","label":"Staff number","shape":"S####","enabled":false}]}}}""",
        )
        val report = read(call(req = """{"member_id":"XY-123456-7","other":"XY-123456-7","n":"S1234"}"""))
        assertEquals(
            FindingRuleCatalog.SENSITIVE_RULES.map { it.id } + listOf("sensitive.custom.member-id", "sensitive.custom.staff"),
            report.rules.map { it.id },
        )
        val custom = report.rules.single { it.id == "sensitive.custom.member-id" }
        assertEquals(listOf("Member number", "your value rule in config.json", "medium", "true", "1"),
            listOf(custom.title, custom.summary, custom.severity, custom.enabled.toString(), custom.count.toString()))
        assertEquals(false, report.rules.single { it.id == "sensitive.custom.staff" }.enabled)
        assertEquals(listOf("sensitive.custom.member-id reqBody $.member_id"), report.ids(), "only under its field; the disabled one reports nothing")
        assertEquals(listOf(false, false), report.rules.filter { it.id == "sensitive.email" || it.id == "sensitive.phone" }.map { it.enabled })
    }

    @Test
    fun `email and phone ship off and report once turned on`() {
        val dir = write(call(req = """{"contact":"someone@mail.example.com","tel":"+44 20 7946 0958"}"""))
        assertEquals(emptyList(), report(dir).ids())
        configure("""{"findings":{"rules":{"sensitive.email":{"enabled":true},"sensitive.phone":{"enabled":true}}}}""")
        assertEquals(listOf("sensitive.email reqBody $.contact", "sensitive.phone reqBody $.tel"), report(dir).ids())
        assertEquals("•••@•••.com", report(dir).one("sensitive.email").evidence.single().masked)
    }

    @Test
    fun `txnIds, callIds and evidence are in timeline order whatever order the rows were written`() {
        val report = read(
            call(req = """{"card":"4000123412341234"}""", mono = 9_000, id = "a0000001"),
            call(req = """{"card":"4000123412341234"}""", mono = 1_000, id = "a0000002"),
            call(req = """{"card":"4000123412341234"}""", mono = 5_000, id = "a0000003"),
        )
        val finding = report.one("sensitive.card-number")
        assertEquals(listOf("a0000002", "a0000003", "a0000001"), finding.txnIds)
        assertEquals(listOf("a0000002", "a0000003", "a0000001"), finding.evidence.map { it.txnId })
    }

    // --- the fold --------------------------------------------------------------------------------

    @Test
    fun `an incremental fold over a session appended in three parts equals a cold scan`() {
        val writer = SessionWriter(config.sessionsDir.resolve("s2"), clientInfo().toSessionMeta("s2", "2026-10-22T08:00:00.000Z"))
        val warm = service()
        val parts = listOf(
            listOf(call(req = """{"card":"4000123412341234"}"""), call(query = "token=a", method = "GET")),
            listOf(call(req = """{"card":"4000123412341234","password":"x"}"""), call(res = """{"items":[{"iban":"GB82WEST12345698765432"}]}""", method = "GET")),
            listOf(call(scheme = "http", reqHeaders = mapOf("Cookie" to listOf("a")), method = "GET"), call(req = """{"items":[{"iban":"GB82WEST12345698765432"}]}""")),
        )
        for (part in parts) {
            part.forEach { writer.append(it.row, it.req, it.res) }
            assertEquals(service().report(writer.sessionDir, "sensitive", 5000), warm.report(writer.sessionDir, "sensitive", 5000))
        }
        writer.close()
        assertEquals(6, warm.report(writer.sessionDir, "sensitive", 5000).findingCount)
    }

    @Test
    fun `a body is on disk before its row, so a fold never sees a row whose body is still to come`() {
        val writer = SessionWriter(config.sessionsDir.resolve("s3"), clientInfo().toSessionMeta("s3", "2026-10-22T08:00:00.000Z"))
        val blocked = call(req = """{"card":"4000123412341234"}""", id = "b0000001")
        // A directory where the body file goes makes the body write fail.
        Files.createDirectories(SessionLayout.bodyFile(writer.sessionDir, "b0000001", "req"))
        assertFailsWith<Exception> { writer.append(blocked.row, blocked.req, null) }
        val index = SessionLayout.indexFile(writer.sessionDir)
        assertTrue(index.readLines().none { "b0000001" in it }, "the row was written before its body")
        writer.close()
    }

    @Test
    fun `the accumulator holds no row and no body after a fold`() {
        val calls = listOf(
            call(req = """{"card":"4000123412341234","password":"x"}"""),
            call(res = """{"items":[{"iban":"GB82WEST12345698765432"}]}""", method = "GET"),
        )
        val writer = SessionWriter(config.sessionsDir.resolve("s4"), clientInfo().toSessionMeta("s4", "2026-10-22T08:00:00.000Z"))
        val stored = calls.map { writer.append(it.row, it.req, it.res) }
        writer.close()
        val dir = writer.sessionDir
        val session = FindingSession(dir, config.routeTemplates, FindingSettings.DEFAULT) { id, side -> repository.readBody(dir, id, side) }
        val accumulator = SensitiveSource(SensitiveSettings()).accumulator(session)
        stored.forEach(accumulator::add)
        assertEquals(3, accumulator.findings().size, "card, password field, IBAN")
        val held = reachable(accumulator, skip = session)
        assertEquals(emptyList(), held.filter { it is NetworkTransaction || it is ByteArray }.map { it.javaClass.simpleName })
        assertTrue(held.any { it is FindingRow }, "the walk reached the groups")
    }

    /**
     * Every object reachable from [root] through its own fields and through collections, never into
     * [skip] (the session, which reads bodies for the fold but keeps none) or a JDK class's insides.
     */
    private fun reachable(root: Any, skip: Any): List<Any> {
        val seen = java.util.IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque<Any>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next === skip || seen.containsKey(next)) continue
            seen[next] = Unit
            when (next) {
                is Map<*, *> -> next.forEach { (k, v) -> k?.let(queue::add); v?.let(queue::add) }
                is Iterable<*> -> next.forEach { it?.let(queue::add) }
                is Pair<*, *> -> { next.first?.let(queue::add); next.second?.let(queue::add) }
                else -> if (next.javaClass.name.startsWith("dev.inspector.")) {
                    generateSequence<Class<*>>(next.javaClass) { it.superclass }.takeWhile { it.name.startsWith("dev.inspector.") }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .forEach { field -> field.isAccessible = true; field.get(next)?.let(queue::add) }
                }
            }
        }
        return seen.keys.toList()
    }

    private object InspectorJsonText {
        fun of(report: FindingsReport) = dev.inspector.model.InspectorJson.encodeToString(FindingsReport.serializer(), report)
    }

    private companion object {
        const val HOST = "api.example.invalid"
    }
}
