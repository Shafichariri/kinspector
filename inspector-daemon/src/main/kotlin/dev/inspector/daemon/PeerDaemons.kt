package dev.inspector.daemon

import kotlinx.serialization.Serializable
import kotlin.io.path.Path

/**
 * Another inspector process on this machine.
 *
 * "Related to my solution" is decided by argv, not by a name substring — see [PeerArgv.MAIN_CLASS].
 */
@Serializable
data class PeerDaemon(
    val pid: Long,
    /** The subcommand: `serve`, `mcp`, `sessions`, … */
    val role: String,
    /** Resolved the way that process itself would resolve it. Null for roles that do not listen. */
    val port: Int?,
    val dataDir: String?,
    val startedEpochMs: Long?,
    /** True for the process serving this request; it must be stopped via `/api/server/stop`. */
    val self: Boolean,
)

sealed interface KillOutcome {
    /** The signal was delivered. Exit is asynchronous, so the caller should re-list to confirm. */
    data object Signalled : KillOutcome

    /** No live process with that pid. Usually means it already exited. */
    data object NoSuchProcess : KillOutcome

    data class Refused(val reason: String) : KillOutcome
}

/**
 * Lists and terminates sibling inspector processes.
 *
 * An interface for the same reason [ServerControl] is one: the daemon tests run a daemon inside
 * the test JVM, and a registry that really enumerated and killed processes would be aiming at the
 * test runner.
 */
interface PeerRegistry {
    fun list(): List<PeerDaemon>
    fun kill(pid: Long, force: Boolean): KillOutcome
}

/**
 * Argv parsing, kept separate from process enumeration so it can be tested without spawning
 * anything.
 */
internal object PeerArgv {

    /**
     * The identifying token.
     *
     * Matched by **exact argv token equality**, never as a substring of the whole command line.
     * That distinction is the entire point: `pkill -f inspector` also matches the editor's MCP
     * connection, and `pkill -f "inspector.*serve"` matches it too, because the classpath contains
     * `ktor-server-cio-*.jar` and "server" contains "serve". Both traps are documented in
     * `docs/DAEMON.md`; this class exists so the UI cannot fall into either.
     */
    const val MAIN_CLASS = "dev.inspector.daemon.MainKt"

    /** The subcommand, or null when these arguments are not an inspector launch at all. */
    fun roleOf(arguments: List<String>): String? {
        val index = arguments.indexOf(MAIN_CLASS)
        if (index < 0) return null
        return arguments.getOrNull(index + 1)?.takeIf { !it.startsWith("-") } ?: "unknown"
    }

    /** The value of `--name`, or null when absent or unvalued. */
    fun flag(arguments: List<String>, name: String): String? {
        val index = arguments.indexOf("--$name")
        if (index < 0) return null
        return arguments.getOrNull(index + 1)?.takeIf { !it.startsWith("-") }
    }

    /**
     * The port a `serve` peer is on, resolved through [DaemonConfig.load] so it accounts for that
     * peer's `config.json` exactly as the peer itself did. Null for roles that do not listen —
     * `mcp` speaks over stdio and holds no port at all, which is worth showing rather than hiding.
     */
    fun portOf(role: String, arguments: List<String>): Int? {
        if (role != "serve") return null
        val dataDir = flag(arguments, "data")?.let { Path(it) } ?: DaemonConfig.defaultDataDir()
        return runCatching {
            DaemonConfig.load(dataDir = dataDir, port = flag(arguments, "port")?.toIntOrNull()).port
        }.getOrNull()
    }
}

/** The real registry, over [ProcessHandle]. */
class ProcessHandlePeerRegistry : PeerRegistry {

    private val selfPid = ProcessHandle.current().pid()
    private val selfUser: String? = ProcessHandle.current().info().user().orElse(null)

    override fun list(): List<PeerDaemon> =
        ProcessHandle.allProcesses()
            .toList()
            .mapNotNull { describe(it) }
            .sortedWith(compareBy({ !it.self }, { it.role }, { it.pid }))

    override fun kill(pid: Long, force: Boolean): KillOutcome {
        if (pid == selfPid) {
            return KillOutcome.Refused(
                "that is this daemon — use Stop, which shuts down cleanly after replying",
            )
        }

        val handle = ProcessHandle.of(pid).orElse(null) ?: return KillOutcome.NoSuchProcess
        if (!handle.isAlive) return KillOutcome.NoSuchProcess

        // Re-verified here rather than trusting the pid the client sent back. Between listing and
        // killing, the process can exit and the OS can reuse its pid for something unrelated, so a
        // pid alone is not evidence of what it now identifies.
        val info = handle.info()
        val arguments = info.arguments().orElse(null)?.toList()
        if (arguments == null || PeerArgv.roleOf(arguments) == null) {
            return KillOutcome.Refused(
                "pid $pid is not an inspector process — it may have exited and had its pid reused",
            )
        }

        val owner = info.user().orElse(null)
        if (selfUser != null && owner != null && owner != selfUser) {
            return KillOutcome.Refused("pid $pid belongs to $owner, not $selfUser")
        }

        val signalled = if (force) handle.destroyForcibly() else handle.destroy()
        return if (signalled) {
            KillOutcome.Signalled
        } else {
            KillOutcome.Refused("the OS declined to signal pid $pid")
        }
    }

    private fun describe(handle: ProcessHandle): PeerDaemon? {
        val info = handle.info()
        val arguments = info.arguments().orElse(null)?.toList() ?: return null
        val role = PeerArgv.roleOf(arguments) ?: return null
        return PeerDaemon(
            pid = handle.pid(),
            role = role,
            port = PeerArgv.portOf(role, arguments),
            dataDir = PeerArgv.flag(arguments, "data"),
            startedEpochMs = info.startInstant().orElse(null)?.toEpochMilli(),
            self = handle.pid() == selfPid,
        )
    }
}
