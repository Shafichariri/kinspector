package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionStamp
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What a whole-result cache compares: it moves on a row, a marker and a replaced file, and on nothing else. */
class SessionStampTest {

    private lateinit var tmp: Path
    private lateinit var dir: Path
    private lateinit var writer: SessionWriter

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-stamp")
        dir = DaemonConfig(dataDir = tmp).sessionsDir.resolve("s1")
        writer = SessionWriter(dir, clientInfo().toSessionMeta("s1", "2026-01-01T00:00:00.000Z"))
        writer.append(txn("a0000001"), null, null)
    }

    @AfterTest
    fun tearDown() {
        writer.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `a stamp moves on an appended row, an appended marker and a replaced index, and only then`() {
        val first = SessionStamp.of(dir)
        assertEquals(first, SessionStamp.of(dir), "nothing was written")

        writer.append(txn("a0000002"), null, null)
        val afterRow = SessionStamp.of(dir)
        assertNotEquals(first.indexSize, afterRow.indexSize, "a row")

        writer.append(Marker(ts = "2026-01-01T00:00:01.000Z", mono = 1, label = "x", source = MarkerSource.USER))
        val afterMarker = SessionStamp.of(dir)
        assertNotEquals(afterRow.markersSize, afterMarker.markersSize, "a marker")
        assertEquals(afterRow.indexSize, afterMarker.indexSize)

        // The same bytes in a new file: the size cannot tell, the file key can.
        val index = SessionLayout.indexFile(dir)
        val copy = index.resolveSibling("index.copy")
        Files.copy(index, copy)
        Files.move(copy, index, StandardCopyOption.REPLACE_EXISTING)
        val replaced = SessionStamp.of(dir)
        assertEquals(afterMarker.indexSize, replaced.indexSize)
        assertNotEquals(afterMarker, replaced, "a replaced index")
    }
}
