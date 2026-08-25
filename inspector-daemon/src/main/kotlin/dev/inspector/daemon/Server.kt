package dev.inspector.daemon

import dev.inspector.model.Bye
import dev.inspector.model.FilterParseException
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.model.MarkerMsg
import dev.inspector.model.SessionMeta
import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.SignalError
import dev.inspector.model.SignalMsg
import dev.inspector.model.SignalRequest
import dev.inspector.model.Txn
import dev.inspector.model.WireMsg
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64

/**
 * Thrown when the daemon's port is already held. Carries the port so the CLI can print the
 * command that finds the culprit.
 */
class PortUnavailableException(val port: Int, cause: Throwable) :
    IOException("port $port is already in use", cause)

/**
 * The host daemon.
 *
 * Binds to 127.0.0.1 only. There is no authentication and the archive contains unredacted
 * credentials by default, so the loopback bind is the security boundary — do not widen it
 * without adding auth first.
 */
class InspectorDaemon(
    private val config: DaemonConfig,
    private val control: ServerControl = ProcessServerControl(),
    private val peers: PeerRegistry = ProcessHandlePeerRegistry(),
    /** Exposed so replay can address a running app; see `docs/REPLAY.md`. */
    val liveApps: LiveApps = LiveApps(),
    private val replayer: Replayer? = null,
    private val mcp: McpControl? = null,
) {

    private val mcpControl: McpControl by lazy { mcp ?: ProcessMcpControl(config) }

    private val replay: Replayer by lazy {
        replayer ?: Replayer(SessionRepository(config), liveApps)
    }

    private val repository = SessionRepository(config)
    private val retention = Retention(config, repository)
    private val manager = SessionManager(config, repository, retention)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var server: EmbeddedServer<*, *>? = null

    fun start(wait: Boolean = true) {
        config.sessionsDir.toFile().mkdirs()
        requirePortAvailable()

        // Sweep on start: a daemon that crashed mid-session leaves the archive over its ceiling.
        val pruned = retention.prune()
        if (pruned.prunedSessionIds.isNotEmpty()) {
            println("inspector: pruned ${pruned.prunedSessionIds.size} session(s) on start")
        }

        scope.launch {
            while (isActive) {
                delay(30_000)
                manager.flushMeta()
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread { manager.closeAll() })

        val engine = embeddedServer(CIO, port = config.port, host = "127.0.0.1") { module() }
        server = engine
        println("inspector: archive at ${config.dataDir}")
        println("inspector: listening on http://127.0.0.1:${config.port}")
        println("inspector: retention ${config.maxSessions} sessions / ${config.maxTotalBytes / 1024 / 1024} MB")
        engine.start(wait = wait)
    }

    /**
     * Refuses to start when the port is taken, before anything is printed.
     *
     * CIO surfaces a failed bind asynchronously, so without this the daemon announced
     * "listening on …" and then sat there alive and serving nothing. That is how three
     * `inspector serve` processes ended up on one machine with only one of them working, all
     * three looking healthy in the terminal that launched them.
     *
     * A pre-flight bind can in principle lose a race to another process between the probe and
     * the real bind. That is a far better failure than the silent one it replaces.
     */
    private fun requirePortAvailable() {
        try {
            ServerSocket().use { probe ->
                // Matches what the server itself will do. SO_REUSEADDR still refuses a port
                // with a live listener, but permits one left in TIME_WAIT by a daemon that just
                // exited — so restarting right after Ctrl-C works, while a genuine collision
                // with a running daemon does not.
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("127.0.0.1", config.port))
            }
        } catch (cause: IOException) {
            throw PortUnavailableException(config.port, cause)
        }
    }

    fun stop() {
        manager.closeAll()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
    }

    private fun Application.module() {
        install(WebSockets)

        routing {
            ingestRoute()
            liveRoute()
            apiRoutes()
            controlRoutes()
            webUiRoutes()
        }
    }

    // --- lifecycle control --------------------------------------------------------------------

    /**
     * Stop and restart, so the web UI can end a daemon without hunting for the terminal that
     * launched it. This exists because the failure it addresses was real: three `inspector serve`
     * processes on one machine, two of them serving nothing.
     */
    private fun io.ktor.server.routing.Route.controlRoutes() {
        get("/api/server") {
            call.respondJson(
                """{"pid":${ProcessHandle.current().pid()},""" +
                    """"port":${config.port},""" +
                    """"canRestart":${control.canRestart}}"""
            )
        }

        post("/api/server/stop") {
            if (!call.requireControlHeader()) return@post
            call.respondJson("""{"ok":true,"action":"stop"}""")
            shutdownAfterResponse(restart = false)
        }

        post("/api/server/restart") {
            if (!call.requireControlHeader()) return@post
            if (!control.canRestart) {
                return@post call.respondError(
                    HttpStatusCode.NotImplemented,
                    "this process cannot report its own command line, so it cannot relaunch " +
                        "itself — stop it and start it again from your terminal",
                )
            }
            call.respondJson("""{"ok":true,"action":"restart"}""")
            shutdownAfterResponse(restart = true)
        }

        peerRoutes()
        replayRoute()
        mcpRoutes()
    }

    /**
     * MCP registration details, and a probe that proves the server answers.
     *
     * There is no start endpoint. The MCP server speaks stdio, so a process the daemon spawned
     * would have no client on its pipes; the editor spawns it on connect. Killing a wedged one
     * goes through `/api/peers/{pid}/kill`, after which the editor spawns a fresh one.
     */
    private fun io.ktor.server.routing.Route.mcpRoutes() {
        get("/api/mcp") {
            call.respondJson(InspectorJson.encodeToString(McpInfo.serializer(), mcpControl.info()))
        }

        post("/api/mcp/probe") {
            if (!call.requireControlHeader()) return@post
            val result = mcpControl.probe()
            call.respondJson(InspectorJson.encodeToString(McpProbeResult.serializer(), result))
        }
    }

    /**
     * Re-sends a captured request.
     *
     * Behind the control header, and that is doing more work here than it does for stop/restart.
     * Until now the daemon only *read* an archive; this turns it into something that sends
     * arbitrary requests carrying unredacted credentials. The header forces a preflight this
     * server never answers, which is CSRF defence — it is not authentication, and the loopback
     * bind is still the only thing standing between this and any local process. Said plainly in
     * `docs/REPLAY.md` rather than left implicit.
     */
    private fun io.ktor.server.routing.Route.replayRoute() {
        post("/api/replay") {
            if (!call.requireControlHeader()) return@post
            val body = call.receiveText()
            val request = try {
                InspectorJson.decodeFromString(ReplayRequest.serializer(), body)
            } catch (e: Exception) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "could not read the replay request: ${e.message}",
                )
            }

            val result = replay.replay(request)
            call.respondJson(InspectorJson.encodeToString(ReplayResult.serializer(), result))
        }
    }

    /**
     * Lists and kills sibling inspector processes.
     *
     * The manual alternative is a `pkill` that is genuinely hard to get right: `inspector` also
     * matches the editor's MCP connection, and so does `inspector.*serve`, because the classpath
     * carries `ktor-server-cio-*.jar`. Both traps are in `docs/DAEMON.md`. Matching argv tokens
     * here means the UI cannot make either mistake.
     */
    private fun io.ktor.server.routing.Route.peerRoutes() {
        get("/api/peers") {
            // Serialized rather than hand-built: `dataDir` is a filesystem path and can legally
            // contain quotes and backslashes.
            call.respondJson(
                InspectorDaemonJson.encodeToString(
                    ListSerializer(PeerDaemon.serializer()),
                    peers.list(),
                )
            )
        }

        post("/api/peers/{pid}/kill") {
            if (!call.requireControlHeader()) return@post
            val pid = call.parameters["pid"]?.toLongOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "pid must be a number")
            val force = call.request.queryParameters["force"] == "true"

            when (val outcome = peers.kill(pid, force)) {
                is KillOutcome.Signalled ->
                    call.respondJson("""{"ok":true,"pid":$pid,"force":$force}""")
                is KillOutcome.NoSuchProcess ->
                    call.respondError(HttpStatusCode.NotFound, "no live process with pid $pid")
                is KillOutcome.Refused ->
                    call.respondError(HttpStatusCode.Conflict, outcome.reason)
            }
        }
    }

    /**
     * Requires a header no cross-origin request can set without a preflight.
     *
     * The daemon listens on loopback with no authentication, which means *any* page the developer
     * happens to have open can POST to `127.0.0.1:8099`. A simple form or `no-cors` fetch could
     * therefore kill the daemon. A custom header forces the browser to preflight, and this server
     * answers no CORS preflight, so only its own page gets through. The read-only endpoints are
     * left alone — this guard is about not handing strangers an off switch.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.requireControlHeader(): Boolean {
        if (request.headers["X-Inspector-Control"] == "1") return true
        respondError(
            HttpStatusCode.Forbidden,
            "control endpoints require the X-Inspector-Control: 1 header",
        )
        return false
    }

    /**
     * Tears down after the response has gone out.
     *
     * On its own thread, and never inside the request handler: stopping the engine from within
     * one of its own handlers deadlocks, and the caller would see a dropped connection rather
     * than the acknowledgement that tells the UI its click worked.
     *
     * The port is released *before* the replacement is spawned. The other order has the new
     * process fail its own pre-flight bind check and exit, leaving no daemon at all.
     */
    private fun shutdownAfterResponse(restart: Boolean) {
        Thread {
            Thread.sleep(RESPONSE_FLUSH_MILLIS)
            manager.closeAll()
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            if (restart) {
                println("inspector: restarting on request from the web UI")
                control.spawnReplacement()
            } else {
                println("inspector: stopped on request from the web UI")
            }
            control.exit()
        }.apply {
            name = "inspector-shutdown"
            isDaemon = false
        }.start()
    }

    // --- ingest ----------------------------------------------------------------------------

    private fun io.ktor.server.routing.Route.ingestRoute() = webSocket("/ingest") {
        var sessionId: String? = null
        var connection: LiveApps.Connection? = null
        var warnedAboutSignals = false
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching {
                    InspectorJson.decodeFromString<WireMsg>(frame.readText())
                }.getOrElse {
                    System.err.println("inspector: dropping unparseable frame: ${it.message}")
                    continue
                }

                when (message) {
                    is Hello -> {
                        val opened = manager.openSession(message)
                        sessionId = opened.sessionId
                        // Registered so the host can address this app later. The send closure is
                        // handed over rather than the socket, keeping LiveApps free of Ktor.
                        connection?.let { liveApps.unregister(it.sessionId, it) }
                        connection = liveApps.register(opened.sessionId) { outbound ->
                            send(Frame.Text(InspectorJson.encodeToString(outbound)))
                        }
                        send(
                            Frame.Text(
                                InspectorJson.encodeToString<WireMsg>(
                                    HelloAck(opened.sessionId, opened.resumed)
                                )
                            )
                        )
                        println(
                            "inspector: ${if (opened.resumed) "resumed" else "started"} " +
                                "session ${opened.sessionId}"
                        )
                    }

                    is Txn -> {
                        val id = sessionId ?: continue
                        manager.append(
                            id,
                            message.txn,
                            decodeBody(message.reqBody, message.reqBodyB64),
                            decodeBody(message.resBody, message.resBodyB64),
                        )
                    }

                    is MarkerMsg -> sessionId?.let { manager.append(it, message.marker) }

                    is SignResponse -> sessionId?.let { liveApps.complete(it, message) }

                    // Signal ingest arrives in stage 2 (docs/SIGNALS.md). Until then, say so once
                    // per connection: a device built ahead of its daemon should be obvious rather
                    // than look like signals that vanished.
                    is SignalMsg -> if (!warnedAboutSignals) {
                        warnedAboutSignals = true
                        System.err.println(
                            "inspector: this daemon does not archive signals yet; dropping them " +
                                "for this connection. Upgrade the daemon to keep them."
                        )
                    }

                    // Correlated reply to a SignalRequest, which nothing sends until stage 3.
                    is SignalError -> Unit

                    Bye -> break

                    // Daemon-to-client only; a client sending one back is simply ignored.
                    is HelloAck -> Unit
                    is SignRequest -> Unit
                    is SignalRequest -> Unit
                }
            }
        } finally {
            connection?.let { liveApps.unregister(it.sessionId, it) }
            sessionId?.let {
                manager.closeSession(it)
                println("inspector: closed session $it")
            }
        }
    }

    private fun decodeBody(encoded: String?, base64: Boolean): ByteArray? {
        if (encoded == null) return null
        return if (base64) {
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        } else {
            encoded.toByteArray()
        }
    }

    // --- live fan-out ------------------------------------------------------------------------

    private fun io.ktor.server.routing.Route.liveRoute() = webSocket("/api/live") {
        manager.live.collect { event ->
            val payload = when (event) {
                is LiveEvent.Transaction -> """{"type":"txn","sessionId":"${event.sessionId}","txn":${
                    InspectorJson.encodeToString(event.txn)
                }}"""
                is LiveEvent.MarkerAdded -> """{"type":"marker","sessionId":"${event.sessionId}","marker":${
                    InspectorJson.encodeToString(event.marker)
                }}"""
                is LiveEvent.SessionStarted -> """{"type":"sessionStarted","meta":${
                    InspectorJson.encodeToString(event.meta)
                }}"""
                is LiveEvent.SessionEnded -> """{"type":"sessionEnded","sessionId":"${event.sessionId}"}"""
            }
            send(Frame.Text(payload))
        }
    }

    /**
     * Resolves the `{id}` path parameter to a session directory, responding 404 and returning
     * null when it is unknown or unsafe. Callers `?: return@get`.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.resolveSession(): java.nio.file.Path? {
        val id = parameters["id"].orEmpty()
        val dir = repository.resolve(id)
        if (dir == null) {
            respondError(HttpStatusCode.NotFound, "no session '$id'")
            return null
        }
        return dir
    }

    // --- web UI ---------------------------------------------------------------------------------

    private fun io.ktor.server.routing.Route.webUiRoutes() {
        get("/") { call.respondResource("index.html", ContentType.Text.Html) }
        get("/app.js") { call.respondResource("app.js", ContentType.Text.JavaScript) }
        get("/style.css") { call.respondResource("style.css", ContentType.Text.CSS) }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondResource(
        name: String,
        contentType: ContentType,
    ) {
        val bytes = this@InspectorDaemon.javaClass.classLoader
            .getResourceAsStream("web/$name")?.readBytes()
        if (bytes == null) {
            respondError(HttpStatusCode.NotFound, "missing bundled resource web/$name")
            return
        }
        respondBytes(bytes, contentType)
    }

    // --- REST ---------------------------------------------------------------------------------

    private fun io.ktor.server.routing.Route.apiRoutes() {
        get("/api/sessions") {
            call.respondJson(
                InspectorJson.encodeToString(
                    ListSerializer(SessionMeta.serializer()),
                    repository.listSessions(),
                )
            )
        }

        get("/api/sessions/{id}/summary") {
            val dir = call.resolveSession() ?: return@get
            val summary = repository.summarize(dir)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "session has no metadata")
            call.respondJson(InspectorJson.encodeToString(SessionSummary.serializer(), summary))
        }

        get("/api/sessions/{id}/transactions") {
            val dir = call.resolveSession() ?: return@get
            val filter = call.request.queryParameters["filter"].orEmpty()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            try {
                val page = repository.queryTransactions(dir, filter, offset, limit)
                call.respondJson(InspectorJson.encodeToString(TransactionPage.serializer(), page))
            } catch (e: FilterParseException) {
                // The parser's message names the fix; pass it through untouched.
                call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid filter")
            }
        }

        get("/api/sessions/{id}/transactions/{txnId}") {
            val dir = call.resolveSession() ?: return@get
            val txnId = call.parameters["txnId"].orEmpty()
            val txn = repository.readTransaction(dir, txnId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "no transaction $txnId")
            call.respondJson(InspectorJson.encodeToString(txn))
        }

        get("/api/sessions/{id}/transactions/{txnId}/body/{side}") {
            val dir = call.resolveSession() ?: return@get
            val txnId = call.parameters["txnId"].orEmpty()
            val side = call.parameters["side"].orEmpty()
            val bytes = repository.readBody(dir, txnId, side)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "no $side body for $txnId")
            val contentType = repository.readTransaction(dir, txnId)?.let {
                if (side == "req") it.reqContentType else it.resContentType
            }
            call.respondBytes(
                bytes,
                runCatching { ContentType.parse(contentType ?: "") }.getOrNull()
                    ?: ContentType.Application.OctetStream,
            )
        }

        get("/api/sessions/{id}/markers") {
            val dir = call.resolveSession() ?: return@get
            call.respondJson(
                InspectorJson.encodeToString(ListSerializer(Marker.serializer()), repository.readMarkers(dir))
            )
        }

        post("/api/sessions/{id}/markers") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receiveText()
            val label = runCatching {
                InspectorJson.decodeFromString<Map<String, String>>(body)["label"]
            }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "expected {\"label\":\"…\"}")

            val resolvedId = if (id == "latest") {
                repository.resolve("latest")?.fileName?.toString()
            } else id
            val active = manager.activeSessionIds()
            if (resolvedId == null || resolvedId !in active) {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "markers can only be added to a session that is currently recording",
                )
            }
            manager.append(
                resolvedId,
                Marker(
                    ts = java.time.Instant.now().toString(),
                    mono = 0,
                    label = label,
                    source = MarkerSource.AGENT,
                ),
            )
            call.respondJson("""{"ok":true}""")
        }
    }
}

/** Long enough for CIO to write the response before the engine is torn down under it. */
private const val RESPONSE_FLUSH_MILLIS = 150L

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(json: String) {
    respondText(json, ContentType.Application.Json)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
) {
    respondText(
        InspectorJson.encodeToString(mapOf("error" to message)),
        ContentType.Application.Json,
        status,
    )
}
