package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The daemon classes decoded from outside input keep the serial names they had before phase D.
 *
 * kotlinx-serialization quotes the serial name in a `MissingFieldException`, and the replay and
 * signal-pull routes echo that message into their 400 body; `config.json`'s reader prints it to
 * stderr. A serial name defaults to the class's fully qualified name, so moving one of these
 * classes to another package would change what a caller reads with no other symptom. These are
 * the only three daemon-owned types ever decoded; encode-only types never write their serial name.
 */
class SerialNameTest {

    @Test
    fun `ReplayRequest keeps its pre-move serial name`() {
        assertEquals("dev.inspector.daemon.ReplayRequest", ReplayRequest.serializer().descriptor.serialName)
    }

    @Test
    fun `SignalPullRequest keeps its pre-move serial name`() {
        assertEquals("dev.inspector.daemon.SignalPullRequest", SignalPullRequest.serializer().descriptor.serialName)
    }

    @Test
    fun `DaemonConfig FileConfig keeps its pre-move serial name`() {
        assertEquals(
            "dev.inspector.daemon.DaemonConfig.FileConfig",
            DaemonConfig.FileConfig.serializer().descriptor.serialName,
        )
    }
}
