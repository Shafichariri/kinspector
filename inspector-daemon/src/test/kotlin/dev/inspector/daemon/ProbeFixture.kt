package dev.inspector.daemon

import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalTags
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/*
 * The session CI points `render-web-ui.js` and the archive probe at.
 *
 * Written at CI time by the daemon's own `SessionWriter`, never committed. A committed archive is a
 * second copy of the on-disk format that nothing keeps current: the day a field is added, the
 * fixture quietly stops exercising it while every probe goes on passing against the old shape. Built
 * here, it is whatever the writer writes today, and `ProbeFixtureTest` reads it back through
 * `SessionRepository` so a fixture the daemon cannot serve fails a unit test rather than a CI run.
 *
 * Every probe that matters needs something in here to look at, and a probe with nothing to look at
 * reports `n/a` — which the expected check count then catches. So the contents are chosen, not
 * sampled: a shared `/v3/shop/` prefix on one host and a second host it does not cover, enough
 * endpoints for chips, a 500, a retry chain, a redirect chain, a repeat that swept up a retry (so
 * rows and calls differ), JSON, binary and empty bodies, a marker at exactly a call's `mono`, and
 * screen, cache and state signals with a run of identical observations and a repeated screen.
 * Every host is under `example.invalid`, so a replay probe that escaped its stub reaches nothing.
 */

const val PROBE_SESSION_ID = "2026-01-01T09-00-00_probe_ci_debug"
private const val PROBE_STARTED_AT = "2026-01-01T09:00:00.000Z"
private const val API = "api.example.invalid"
private const val AUTH = "auth.example.invalid"

fun main(args: Array<String>) {
    val dir = Paths.get(args.singleOrNull() ?: error("usage: ProbeFixture <data dir>"))
    writeProbeFixture(dir)
    println("probe fixture written to ${dir.toAbsolutePath()}")
}

/**
 * Writes the probe session under [dataDir] and points `latest` at it.
 *
 * Replaces the probe session if one is already there. `SessionWriter` appends, so a second run into
 * the same directory used to double every row — and the probes, which count rows against the
 * daemon's own answers, would then pass or fail on how many times the task had run. Only the probe
 * session's own folder is removed; anything else under [dataDir] is left alone.
 */
fun writeProbeFixture(dataDir: Path): Path {
    val config = DaemonConfig(dataDir = dataDir)
    Files.createDirectories(config.sessionsDir)
    val previous = config.sessionsDir.resolve(PROBE_SESSION_ID)
    if (Files.exists(previous)) {
        Files.walk(previous).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    val fixture = probeFixture()
    val dir = writeSession(
        config, PROBE_SESSION_ID, PROBE_STARTED_AT,
        transactions = fixture.transactions,
        markers = fixture.markers,
        signals = fixture.signals,
        bodies = fixture.bodies,
    )
    SessionWriter.updateLatestLink(config.dataDir, dir)
    return dir
}

class ProbeFixtureContents(
    val transactions: List<NetworkTransaction>,
    val markers: List<Marker>,
    val signals: List<Pair<Signal, ByteArray?>>,
    val bodies: Map<String, Pair<ByteArray?, ByteArray?>>,
)

private val base: Instant = Instant.parse(PROBE_STARTED_AT)

/** Wall clock follows `mono`, so the clock column and the ages read like a real session. */
private fun tsAt(mono: Long): String = base.plusMillis(mono).toString()

fun probeFixture(): ProbeFixtureContents {
    val rows = ArrayList<NetworkTransaction>()
    val bodies = HashMap<String, Pair<ByteArray?, ByteArray?>>()

    fun call(
        id: String,
        mono: Long,
        path: String,
        status: Int? = 200,
        method: String = "GET",
        host: String = API,
        callId: String = "c-$id",
        attempt: Int = 1,
        query: String? = null,
        ms: Long = 120,
        reqHeaders: Map<String, List<String>> = mapOf("Accept" to listOf("application/json")),
        reqBody: ByteArray? = null,
        reqContentType: String? = null,
        resBody: ByteArray? = null,
        resContentType: String? = resBody?.let { "application/json" },
        resBytes: Long = resBody?.size?.toLong() ?: 0,
        error: String? = null,
    ) {
        rows += NetworkTransaction(
            id = id, ts = tsAt(mono), mono = mono, method = method, scheme = "https", host = host,
            path = path, query = query, status = status, error = error, ms = ms, attempt = attempt,
            callId = callId, reqBytes = reqBody?.size?.toLong() ?: 0, resBytes = resBytes,
            reqHeaders = reqHeaders,
            resHeaders = resContentType?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap(),
            reqContentType = reqContentType, resContentType = resContentType,
        )
        if (reqBody != null || resBody != null) bodies[id] = reqBody to resBody
    }

    fun json(text: String) = text.toByteArray()

    // --- before the first screen: the launch column ---
    call("a0000001", 1_000, "/v3/shop/config", resBody = json("""{"flags":{"newCheckout":true},"region":"eu"}"""))
    // The replay editor's hop-by-hop check and the bundle's header cap both need these headers.
    call(
        "a0000002", 1_100, "/oauth/token", method = "POST", host = AUTH,
        reqHeaders = mapOf(
            "Host" to listOf(AUTH),
            "Content-Length" to listOf("38"),
            "Accept-Encoding" to listOf("gzip"),
            "Authorization" to listOf("Bearer " + "x".repeat(400)),
            "X-Request-Id" to listOf("r-1"),
        ),
        reqBody = json("""{"grant_type":"refresh_token","r":"1"}"""),
        reqContentType = "application/json",
        resBody = json("""{"access_token":"redacted-in-fixture","expires_in":3600}"""),
    )

    // --- Home ---
    // A 64-bit id, which is exactly what JSON.parse would silently round.
    call("a0000003", 1_300, "/v3/shop/feed", resBody = json("""{"items":[{"id":1234567890123456789,"title":"one"},{"id":2,"title":"two"}]}"""))

    // --- Product ---
    call("a0000004", 1_650, "/v3/shop/products/42", resBody = json("""{"id":42,"price":"9.99"}"""))

    // --- Cart ---
    call("a0000005", 1_820, "/v3/shop/cart", resBody = json("""{"items":[{"sku":"k1","qty":1}]}"""))
    call("a0000006", 1_950, "/v3/shop/cart/items", method = "DELETE", status = 204)

    // --- Product again: a revisit is a new visit ---
    call(
        "a0000007", 2_050, "/v3/shop/images/logo",
        resBody = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff.toByte(), 0xfe.toByte()),
        resContentType = "image/png",
    )

    // --- Checkout: a marker dropped at exactly the millisecond of the call it marks ---
    call(
        "a0000008", 2_400, "/v3/shop/orders/submit", method = "POST", status = 500,
        reqBody = json("""{"cart":"k1","pay":"card"}"""), reqContentType = "application/json",
        resBody = json("""{"error":"internal"}"""),
    )
    // A redirect chain: two attempts of one call.
    call("a0000009", 2_450, "/v3/shop/promo", status = 302, callId = "c-promo", ms = 30)
    call("a000000a", 2_460, "/v3/shop/promo/current", callId = "c-promo", attempt = 2, resBody = json("""{"promo":"none"}"""))

    // Two endpoints tied on count and recency. The model breaks that tie by char code, which puts
    // `Balance` before `accounts`; a locale-aware compare puts them the other way round.
    call("a000000b", 2_600, "/v3/shop/wallet/Balance", resBody = json("""{"amount":"12.00"}"""))
    call("a000000c", 2_600, "/v3/shop/accounts", resBody = json("""{"accounts":[]}"""))

    // A repeat that swept up a retry: call A once, call B twice (attempts 1 and 2), all 503 with
    // the same byte count. Three rows, two calls — "sent 2x", never "sent 3x". Call B's third
    // attempt succeeds, so the retry chain is three attempts long and ends well.
    val unavailable = json("""{"error":"unavailable"}""")
    call("a000000d", 2_700, "/v3/shop/stock", status = 503, callId = "c-stock-a", resBody = unavailable)
    call("a000000e", 2_800, "/v3/shop/stock", status = 503, callId = "c-stock-b", resBody = unavailable)
    call("a000000f", 2_900, "/v3/shop/stock", status = 503, callId = "c-stock-b", attempt = 2, resBody = unavailable)
    call("a0000010", 3_000, "/v3/shop/stock", callId = "c-stock-b", attempt = 3, resBody = json("""{"stock":4}"""))

    val markers = listOf(
        Marker(ts = tsAt(900), mono = 900, label = "launch", source = MarkerSource.APP),
        Marker(ts = tsAt(2_400), mono = 2_400, label = "tapped checkout", source = MarkerSource.USER),
    )

    val signals = ArrayList<Pair<Signal, ByteArray?>>()
    var signalIndex = 0
    fun observe(tag: String, name: String, mono: Long, payload: String?) {
        val data = payload?.toByteArray()
        val id = "5" + (++signalIndex).toString(16).padStart(7, '0')
        signals += Signal(
            id = id, ts = tsAt(mono), mono = mono, tag = tag, name = name,
            bytes = data?.size?.toLong() ?: 0,
        ) to data
    }

    observe(SignalTags.SCREEN, "Home", 1_200, "{}")
    observe("analytics", "home_viewed", 1_250, """{"source":"launch"}""")
    observe(SignalTags.SCREEN, "Product", 1_600, """{"id":42}""")
    observe("tap", "add_to_cart", 1_700, null)
    observe(SignalTags.SCREEN, "Cart", 1_800, "{}")
    // Both cache shapes the tag browser reads: a whole-cache snapshot whose `items` each carry their
    // own id, and a single entry. Two storages, so the browser has a facet to offer.
    observe(
        SignalTags.CACHE, "cart", 1_850,
        """{"items":[{"id":"cart/lines","storage":"memory","scope":"user","expired":false,"value":{"lines":1}},""" +
            """{"id":"cart/total","storage":"disk","scope":"user","value":"9.99"}]}""",
    )
    observe(SignalTags.CACHE, "catalog", 1_860, """{"storage":"disk","scope":"app","expired":false,"value":{"pages":3}}""")
    observe(SignalTags.STATE, "CartViewModel", 1_900, """{"loading":false,"lines":1}""")
    observe(SignalTags.SCREEN, "Product", 2_000, """{"id":7}""")
    observe(SignalTags.SCREEN, "Checkout", 2_200, """{"step":"review","items":3}""")
    observe(SignalTags.SCREEN, "Checkout", 2_300, """{"step":"pay","items":3}""")
    // A run of identical adjacent observations, past the collapse threshold of three.
    for (i in 0 until 5) observe(SignalTags.STATE, "CheckoutViewModel", 2_310L + i * 10, "\"Submitting(try=$i)\"")
    observe(
        SignalTags.CACHE, "cart", 2_500,
        """{"items":[{"id":"cart/lines","storage":"memory","scope":"user","expired":false,"value":{"lines":0}}]}""",
    )
    observe(SignalTags.CACHE, "catalog", 2_520, """{"storage":"disk","scope":"app","expired":true,"value":{"pages":4}}""")
    observe(SignalTags.STATE, "CartViewModel", 2_550, """{"loading":false,"lines":0}""")

    return ProbeFixtureContents(rows, markers, signals, bodies)
}
