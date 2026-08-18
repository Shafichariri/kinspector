package dev.inspector.daemon

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Sends a replayed request. Separated from the route so it can be driven directly in tests. */
fun interface ReplayTransport {
    /** Returns status, headers and body text. */
    fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): ReplayTransport.Response

    data class Response(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val bodyTruncated: Boolean,
    )
}

/**
 * The real transport, over the JDK client.
 *
 * `NEVER` for redirects on purpose: a replay is an experiment about one request, and silently
 * following a 302 would report the destination's status for a hop the caller never asked about.
 * The captured chain already shows what the app did.
 */
class JdkReplayTransport(
    private val maxBodyBytes: Int = 256 * 1024,
    timeout: Duration = Duration.ofSeconds(30),
) : ReplayTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(timeout)
        .build()

    override fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): ReplayTransport.Response {
        val publisher = if (body == null || body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .method(method.uppercase(), publisher)

        headers.forEach { (name, value) ->
            // The JDK refuses connection-level headers outright. They are stripped upstream by
            // `replayableHeaders`; this catch is for anything an edit reintroduces by hand, and it
            // drops rather than fails, because losing `Connection` matters less than losing the run.
            runCatching { builder.header(name, value) }
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val text = response.body() ?: ""
        return ReplayTransport.Response(
            status = response.statusCode(),
            headers = response.headers().map().mapValues { (_, v) -> v.toList() },
            body = text.take(maxBodyBytes),
            bodyTruncated = text.length > maxBodyBytes,
        )
    }
}

/**
 * Rebuilds a captured request, optionally re-signed by the running app, and sends it.
 *
 * The ordering inside [replay] is load-bearing: edits are applied **before** the app is asked for
 * fresh headers. A signing scheme typically covers the method and path, so re-signing a request
 * and then editing its path produces a signature for a request that was never sent. Doing it in
 * this order works for unedited replays too, so getting it wrong would pass every test that did
 * not specifically edit a path — the worst possible failure distribution.
 */
class Replayer(
    private val repository: SessionRepository,
    private val liveApps: LiveApps,
    private val transport: ReplayTransport = JdkReplayTransport(),
) {

    suspend fun replay(request: ReplayRequest): ReplayResult {
        val sessionDir = repository.resolve(request.session)
            ?: return ReplayResult(ok = false, error = "no session '${request.session}'")
        val txn = repository.readTransaction(sessionDir, request.txnId)
            ?: return ReplayResult(ok = false, error = "no transaction '${request.txnId}'")

        val bodyOverridden = request.body != null
        try {
            refuseIfUnreplayable(txn, bodyOverridden)
        } catch (refusal: ReplayRefusedException) {
            return ReplayResult(ok = false, error = refusal.message)
        }

        // 1. Apply edits.
        val method = (request.method ?: txn.method).uppercase()
        val url = request.url ?: txn.url
        val headers = (request.headers ?: replayableHeaders(txn.reqHeaders)).toMutableMap()
        val body = request.body?.toByteArray()
            ?: repository.readBody(sessionDir, txn.id, "req")

        // 2. Only then ask the app for whatever must be fresh, so it signs what is actually sent.
        var resigned = emptyList<String>()
        if (request.resign) {
            val connection = liveApps.forSession(sessionDir.fileName.toString())
                ?: liveApps.sole()
                ?: return ReplayResult(
                    ok = false,
                    error = "no app is attached, so per-request headers cannot be regenerated. " +
                        "Start the app with Inspector streaming, or replay without re-signing.",
                )
            try {
                val fresh = connection.requestHeaders(method, url)
                headers.putAll(fresh)
                resigned = fresh.keys.sorted()
            } catch (failure: ReplaySignerException) {
                return ReplayResult(ok = false, error = failure.message, resignedHeaders = emptyList())
            }
        }

        val startedAt = System.nanoTime()
        return try {
            val response = transport.send(method, url, headers, body)
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            ReplayResult(
                ok = true,
                status = response.status,
                ms = ms,
                resHeaders = response.headers,
                body = response.body,
                bodyTruncated = response.bodyTruncated,
                resignedHeaders = resigned,
                diagnosis = diagnose(response.status, response.body, request.resign),
            )
        } catch (cause: Exception) {
            ReplayResult(
                ok = false,
                resignedHeaders = resigned,
                error = "${cause::class.simpleName}: ${cause.message}",
                // The daemon runs on the developer's machine; the app runs on an emulator or a
                // device. Reaching a host the app can reach is not a given, and this is the first
                // thing to check when a replay cannot connect at all.
                diagnosis = "Could not complete the request. The daemon reaches the network from " +
                    "this machine, not from the device — an emulator-only host, device DNS or a " +
                    "VPN-gated API will be unreachable here even though the app reached it.",
            )
        }
    }
}
