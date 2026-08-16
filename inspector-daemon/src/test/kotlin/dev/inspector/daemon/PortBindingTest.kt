package dev.inspector.daemon

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A second daemon on a taken port must refuse to start.
 *
 * It previously printed "listening on …" and stayed alive serving nothing, so the terminal that
 * launched it showed success while every request to it hung.
 */
class PortBindingTest {

    private lateinit var tmp: Path
    private var first: InspectorDaemon? = null

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-port")
    }

    @AfterTest
    fun tearDown() {
        first?.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun a_second_daemon_on_the_same_port_fails_loudly_instead_of_lingering() {
        val port = ServerSocket(0).use { it.localPort }
        val config = DaemonConfig(port = port, dataDir = tmp)

        first = InspectorDaemon(config).also { it.start(wait = false) }

        val failure = assertFailsWith<PortUnavailableException> {
            InspectorDaemon(DaemonConfig(port = port, dataDir = tmp)).start(wait = false)
        }
        // The CLI prints this port back with the command that finds the process holding it.
        assertEquals(port, failure.port)
    }
}
