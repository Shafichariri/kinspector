package dev.inspector.daemon.http

import dev.inspector.daemon.archive.ConfigFile
import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.Retention
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.LiveApps
import dev.inspector.daemon.service.McpControl
import dev.inspector.daemon.service.PeerRegistry
import dev.inspector.daemon.service.ProcessHandlePeerRegistry
import dev.inspector.daemon.service.ProcessMcpControl
import dev.inspector.daemon.service.ProcessServerControl
import dev.inspector.daemon.service.Replayer
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.daemon.service.ServerControl
import dev.inspector.daemon.service.SessionDeleter
import dev.inspector.daemon.service.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    // Handed to the route functions unresolved, so a daemon that never probes or replays never
    // builds either: a real Replayer builds a JDK HttpClient, and with it a selector thread.
    private val mcpControlLazy: Lazy<McpControl> = lazy { mcp ?: ProcessMcpControl(config) }

    private val replayLazy: Lazy<Replayer> = lazy {
        replayer ?: Replayer(SessionRepository(config), liveApps)
    }

    private val repository = SessionRepository(config)
    private val retention = Retention(config, repository)
    private val manager = SessionManager(config, repository, retention)
    private val deleter = SessionDeleter(repository, config.sessionsDir, manager::activeSessionIds)
    private val stats = RouteStatsService(repository, config.routeTemplates)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var server: EmbeddedServer<*, *>? = null

    /**
     * [beforeServing] runs once the port is known to be free and just before the server starts —
     * where `serve` starts the USB bridge, so a daemon that is about to fail on a taken port never
     * announces one.
     */
    fun start(wait: Boolean = true, beforeServing: () -> Unit = {}) {
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
        beforeServing()
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

    /**
     * The route tree as Ktor holds it, for `ControlHeaderCoverageTest`.
     *
     * The test walks this rather than a list of paths it was told about, so a control route added
     * without the header is found by being registered, not by somebody remembering to name it.
     */
    internal fun routingRoot(): RoutingRoot? = server?.application?.pluginOrNull(RoutingRoot)

    fun stop() {
        manager.closeAll()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
    }

    private fun Application.module() {
        install(WebSockets)

        routing {
            ingestRoutes(manager, liveApps)
            liveRoutes(manager)
            sessionRoutes(repository, manager, deleter)
            exportRoutes(repository, config)
            statsRoutes(repository, stats)
            signalRoutes(repository, liveApps)
            markerRoutes(repository, manager)
            lifecycleRoutes()
            peerRoutes(peers)
            configRoutes(config, ConfigFile(config.dataDir))
            replayRoutes(replayLazy)
            mcpRoutes(mcpControlLazy)
            webUiRoutes()
        }
    }

    /**
     * Stop and restart, so the web UI can end a daemon without hunting for the terminal that
     * launched it. This exists because the failure it addresses was real: three `inspector serve`
     * processes on one machine, two of them serving nothing.
     */
    private fun Route.lifecycleRoutes() {
        get("/api/server") {
            call.respondJson(
                """{"pid":${ProcessHandle.current().pid()},""" +
                    """"port":${config.port},""" +
                    """"canRestart":${control.canRestart}}"""
            )
        }

        controlGated {
            post("/api/server/stop") {
                call.respondJson("""{"ok":true,"action":"stop"}""")
                shutdownAfterResponse(restart = false)
            }

            post("/api/server/restart") {
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
        }
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
}

/** Long enough for CIO to write the response before the engine is torn down under it. */
private const val RESPONSE_FLUSH_MILLIS = 150L
