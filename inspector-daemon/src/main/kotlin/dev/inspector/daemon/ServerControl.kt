package dev.inspector.daemon

import kotlin.system.exitProcess

/**
 * How the daemon carries out a stop or restart requested over HTTP.
 *
 * An interface rather than a direct `exitProcess` call because the daemon tests run a daemon
 * inside the test JVM: hardcoding the exit would kill the test runner instead of failing a test.
 */
interface ServerControl {

    /**
     * Whether this process can relaunch itself.
     *
     * False when the OS declines to report this process's argv, which happens on some platforms
     * and under some launchers. The endpoint then refuses the restart rather than exiting and
     * leaving nothing behind — losing the daemon is a worse outcome than not restarting it.
     */
    val canRestart: Boolean

    /** Starts a fresh copy of this process. Called after the port has been released. */
    fun spawnReplacement()

    /** Ends this process. */
    fun exit(): Nothing
}

/** The real one: re-execs this JVM's own command line, then exits. */
class ProcessServerControl : ServerControl {

    /**
     * This process's argv, or null when the OS will not report it.
     *
     * Resolved once at construction: after `spawnReplacement` has stopped the server there is no
     * recovering from discovering the command line is unavailable, so the endpoint needs to know
     * up front whether a restart can be honoured at all.
     */
    private val argv: List<String>? = run {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null) ?: return@run null
        val arguments = info.arguments().orElse(null) ?: return@run null
        listOf(command) + arguments
    }

    override val canRestart: Boolean get() = argv != null

    override fun spawnReplacement() {
        val command = checkNotNull(argv) { "no argv for this process; canRestart should have been checked" }
        // inheritIO so the replacement keeps printing to the terminal that launched the original.
        ProcessBuilder(command).inheritIO().start()
    }

    override fun exit(): Nothing = exitProcess(0)
}
