package dev.inspector.daemon

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real registry re-verifies a pid's identity at kill time, against a real process.
 *
 * The kill endpoint takes a pid from the client, and between listing and killing that process can
 * exit and the OS can hand its pid to something unrelated. Without the re-check the endpoint is a
 * "kill any pid you own" facility on an unauthenticated loopback port. `PeerRegistryTest` covers
 * the argv parsing; this aims the real [ProcessHandlePeerRegistry] at a process that is not an
 * inspector and expects it to survive.
 */
class PeerKillIdentityTest {

    private var bystander: Process? = null

    @AfterTest
    fun tearDown() {
        bystander?.destroyForcibly()?.waitFor()
    }

    @Test
    fun `a live process that is not an inspector is refused and left running`() {
        val process = ProcessBuilder("sleep", "30").start().also { bystander = it }
        assertTrue(process.isAlive, "the bystander did not start")

        val outcome = ProcessHandlePeerRegistry().kill(process.pid(), false)

        val refused = assertIs<KillOutcome.Refused>(outcome)
        assertContains(refused.reason, "not an inspector process")
        // `destroy` is asynchronous, so give a wrongly delivered signal time to land.
        Thread.sleep(200)
        assertTrue(process.isAlive, "the registry signalled a process it should have refused")
    }
}
