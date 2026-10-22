package dev.inspector.daemon

import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSettingsSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The live read of `config.json`'s `findings` key: an edit is seen on the next read with no restart,
 * and an unchanged file is not parsed again. Shown red both ways: a source that parses once and keeps
 * it misses the edit, and one that parses on every call fails the counter.
 */
class FindingSettingsSourceTest {

    private lateinit var tmp: Path
    private val file get() = tmp.resolve("config.json")
    private var parses = 0
    private val logged = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-finding-settings")
        parses = 0
        logged.clear()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** A source whose parser counts its calls. */
    private fun source() = FindingSettingsSource(
        file,
        { text -> parses++; FindingSettingsSource.parseFile(text) },
        { logged += it },
    )

    /** Writes [text] and moves the modified time on, so an edit of the same length is still an edit. */
    private fun write(text: String) {
        val before = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(maxOf(before + 1_000, System.currentTimeMillis())))
    }

    private fun window(source: FindingSettingsSource) = source.current().first.payment.duplicateWindowMs

    @Test
    fun `no file reads as the defaults with no error`() {
        val (settings, error) = source().current()
        assertEquals(FindingSettings.DEFAULT, settings)
        assertNull(error)
    }

    @Test
    fun `an edit is seen on the next read, with no restart`() {
        val source = source()
        write("""{"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        assertEquals(5_000L, window(source))

        write("""{"findings":{"payment":{"duplicateWindowMs":7000}}}""")
        assertEquals(7_000L, window(source))

        Files.delete(file)
        assertEquals(3_000L, window(source), "a removed file is the defaults again")
    }

    @Test
    fun `an unchanged file is not parsed again`() {
        val source = source()
        write("""{"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        repeat(5) { source.current() }
        assertEquals(1, parses)
    }

    @Test
    fun `a broken hand edit keeps the last good settings and says so, once`() {
        val source = source()
        write("""{"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        assertEquals(5_000L, window(source))

        write("""{"findings":{"payment":{"duplicateWindowMs":""")
        val (settings, error) = source.current()
        assertEquals(5_000L, settings.payment.duplicateWindowMs)
        assertContains(error!!, "not a JSON object")
        source.current()
        assertEquals(1, logged.size, "named once per change of the file: $logged")

        write("""{"findings":"all"}""")
        assertEquals(5_000L, window(source))
        assertContains(source.current().second!!, "findings must be an object")
    }

    @Test
    fun `with no good settings yet a broken file uses the defaults`() {
        write("""not json""")
        val (settings, error) = source().current()
        assertEquals(FindingSettings.DEFAULT, settings)
        assertContains(error!!, "not a JSON object")
    }

    @Test
    fun `a bad entry is skipped and named, and the rest applies`() {
        write("""{"findings":{"payment":{"duplicateWindowMs":5000,"paymentPaths":["path:/x","checkout"]}}}""")
        val (settings, error) = source().current()
        assertEquals(5_000L, settings.payment.duplicateWindowMs)
        assertEquals(listOf("checkout"), settings.payment.paymentPaths)
        assertContains(error!!, "write the pattern without path:")
    }

    @Test
    fun `an edit to another key changes nothing`() {
        val source = source()
        write("""{"maxSessions":3,"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        val before = source.current()
        write("""{"maxSessions":40,"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        assertEquals(before, source.current())
    }
}
