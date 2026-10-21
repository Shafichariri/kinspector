package dev.inspector.daemon.service

import dev.inspector.daemon.archive.DaemonConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A probe leaves no process behind.
 *
 * Every probe spawns a real `inspector mcp`, and one that outlived it would sit in the peer list as
 * an `mcp` process, the kind that list warns people not to kill because it may be the editor's.
 * The only thing stopping it is `finally { process.destroyForcibly() }` in [ProcessMcpControl.probe].
 *
 * The stub child here **ignores EOF**, and that is the point of it. The real server stops when its
 * stdin closes, and the probe closes stdin before it reads anything, so a test against the real
 * server passes with the `finally` deleted: the child was going to exit anyway. Only a child that
 * keeps running after EOF tells a probe that kills it from one that got lucky.
 *
 * `McpControlTest`'s "either answers or explains why it cannot" stays beside this. It covers the
 * probe that cannot build a command line at all, which the launch seam used here skips.
 */
class McpProbeCleanupTest {

    /** Every stub started, so teardown can stop the ones a red run leaves behind. */
    private val started = mutableListOf<Process>()

    @AfterTest
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
        started.forEach { it.waitFor(5, TimeUnit.SECONDS) }
    }

    /** A child JVM running [McpProbeStubChild] with this test's own classpath. */
    private fun stub(mode: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path")
        return ProcessBuilder(java, "-cp", classpath, McpProbeStubChild::class.java.name, mode)
            .start()
            .also { started += it }
    }

    private fun control(launch: () -> Process) =
        ProcessMcpControl(DaemonConfig(dataDir = Files.createTempDirectory("mcp-probe-cleanup")), launch)

    /**
     * `destroyForcibly` sends the kill and returns; the JVM's reaper sees the exit a moment later.
     * So "not alive once probe returns" is read as "dead within a grace", which a child nobody
     * killed cannot meet: every mode of the stub runs until it is killed.
     */
    private fun assertStopped(child: Process) {
        if (!child.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
            fail("the probe returned and left its child running: pid ${child.pid()} is still alive")
        }
    }

    @Test
    fun `a probe that succeeds stops its child`() {
        lateinit var child: Process
        val result = control { stub(McpProbeStubChild.ANSWER).also { child = it } }.probe(timeoutMs = 20_000)

        assertTrue(result.ok, "the stub answers tools/list, so the probe should succeed: $result")
        assertEquals(McpProbeStubChild.TOOL_COUNT, result.toolCount)
        assertStopped(child)
    }

    @Test
    fun `a probe that times out stops its child`() {
        lateinit var child: Process
        val result = control { stub(McpProbeStubChild.CHATTER).also { child = it } }.probe(timeoutMs = 1_500)

        assertFalse(result.ok)
        assertContains(result.error.orEmpty(), "did not answer a tools/list")
        assertStopped(child)
    }

    @Test
    fun `a probe whose write to the child throws stops its child`() {
        lateinit var child: Process
        val result = control {
            stub(McpProbeStubChild.CLOSE_STDIN).also { process ->
                child = process
                // Hand the child over only once its stdin is closed, so the probe's write meets a
                // broken pipe rather than landing in the pipe's buffer. Without the wait the write
                // succeeds and the probe blocks in readLine on a child that never writes.
                val said = process.errorStream.bufferedReader().readLine()
                check(said == McpProbeStubChild.STDIN_CLOSED) { "the stub said $said" }
            }
        }.probe(timeoutMs = 20_000)

        assertFalse(result.ok)
        // The catch-all's form, not the timeout's: this is the path where the write threw.
        assertContains(result.error.orEmpty(), "IOException")
        assertStopped(child)
    }

    private companion object {
        const val GRACE_SECONDS = 5L
    }
}

/**
 * The child for [McpProbeCleanupTest]. In every mode it runs until it is killed, whatever happens
 * to its stdin.
 */
object McpProbeStubChild {
    const val ANSWER = "answer"
    const val CHATTER = "chatter"
    const val CLOSE_STDIN = "close-stdin"
    const val STDIN_CLOSED = "stdin closed"
    const val TOOL_COUNT = 3

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            // Answers both requests, reads to EOF as the real server would, and then does not stop.
            ANSWER -> {
                println("""{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"stub","version":"1"}}}""")
                println("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{},{},{}]}}""")
                System.out.flush()
                while (readlnOrNull() != null) Unit
            }
            // Keeps talking without ever answering, so the probe's loop reaches its deadline
            // instead of blocking in readLine on a child that is silent.
            CHATTER -> while (true) {
                println("""{"jsonrpc":"2.0","method":"notifications/message"}""")
                System.out.flush()
                Thread.sleep(50)
            }
            CLOSE_STDIN -> {
                System.`in`.close()
                System.err.println(STDIN_CLOSED)
                System.err.flush()
            }
        }
        Thread.sleep(Long.MAX_VALUE)
    }
}
