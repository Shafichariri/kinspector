package dev.inspector.daemon

import dev.inspector.daemon.archive.ConfigFile
import dev.inspector.daemon.archive.ConfigFileState
import dev.inspector.daemon.archive.ConfigMergeResult
import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.model.SignalTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `config.json` written by `PUT /api/config`: merged, validated in its own words, replaced whole.
 *
 * The file is also edited by hand, so the rules that matter are the ones about what a save must not
 * do: drop a key it does not know, overwrite a file it could not read, or leave half a file behind.
 */
class ConfigFileTest {

    private lateinit var tmp: Path
    private lateinit var file: ConfigFile

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-config-file")
        file = ConfigFile(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val path get() = tmp.resolve("config.json")

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun written(patch: String): JsonObject =
        assertIs<ConfigMergeResult.Written>(file.merge(json(patch)), "merge of $patch").json

    private fun invalid(patch: String): String =
        assertIs<ConfigMergeResult.Invalid>(file.merge(json(patch)), "merge of $patch").message

    @Test
    fun `a missing file reads as missing and the daemon starts on defaults`() {
        assertEquals(ConfigFileState.Missing, file.read())
        val config = DaemonConfig.load(dataDir = tmp)
        assertEquals(DaemonConfig.DEFAULT_PORT, config.port)
        assertEquals(DaemonConfig.DEFAULT_MAX_SESSIONS, config.maxSessions)
    }

    @Test
    fun `a save creates the file and the daemon's own reader reads it back`() {
        written("""{"port":9123,"maxSessions":7,"maxTotalMb":42,"signalCaps":{"cache":10,"state":null}}""")

        val config = DaemonConfig.load(dataDir = tmp)
        assertEquals(9123, config.port)
        assertEquals(7, config.maxSessions)
        assertEquals(42L * 1024 * 1024, config.maxTotalBytes)
        assertEquals(10, config.signalCaps[SignalTags.CACHE])
        assertFalse(SignalTags.STATE in config.signalCaps, "a null inside signalCaps means uncapped and must survive")
        assertEquals(setOf<String>(), config.flags, "nothing here came from a flag")
    }

    @Test
    fun `a key the file holds and this build does not know is kept as it was`() {
        path.writeText("""{"findings":{"rules":[{"when":"status:5xx","weight":2.50}]},"maxSessions":3}""")

        val merged = written("""{"maxSessions":9}""")

        val expected = json("""{"findings":{"rules":[{"when":"status:5xx","weight":2.50}]},"maxSessions":9}""")
        assertEquals(expected, merged)
        assertEquals(expected, json(path.readText()))
        // The number's text as written, not a re-rendering of it.
        assertContains(path.readText(), "2.50")
    }

    @Test
    fun `an unknown key in a request is refused by name and nothing is written`() {
        path.writeText("""{"maxSessions":3}""")
        val before = path.readBytes()

        val message = invalid("""{"maxSesions":9,"port":9000}""")

        assertContains(message, "'maxSesions'")
        assertContentEquals(before, path.readBytes(), "a refused save changed the file")
    }

    @Test
    fun `each invalid value is refused with its own message`() {
        val cases = mapOf(
            """{"port":0}""" to "port must be a whole number from 1 to 65535",
            """{"port":65536}""" to "port must be a whole number from 1 to 65535",
            """{"port":"8099"}""" to "port must be a whole number from 1 to 65535",
            """{"port":80.5}""" to "port must be a whole number from 1 to 65535",
            """{"maxSessions":0}""" to "maxSessions must be a whole number of at least 1",
            """{"maxSessions":true}""" to "maxSessions must be a whole number of at least 1",
            """{"maxTotalMb":0}""" to "maxTotalMb must be a whole number of megabytes, at least 1",
            """{"signalCaps":5}""" to "signalCaps must be an object of signal tag to cap",
            """{"signalCaps":{" ":5}}""" to "signalCaps has a blank tag",
            """{"signalCaps":{"cache":-1}}""" to "signalCaps 'cache' must be null (keep every row) or a whole number",
            """{"signalCaps":{"cache":"10"}}""" to "signalCaps 'cache' must be null (keep every row) or a whole number",
        )
        val wrong = cases.mapNotNull { (patch, expected) ->
            val result = file.merge(json(patch))
            if (result is ConfigMergeResult.Invalid && expected in result.message) null else "$patch → $result"
        }
        assertEquals(emptyList(), wrong)
        assertFalse(path.exists(), "an invalid save wrote a file")
    }

    @Test
    fun `a top-level null removes the key so the default applies again`() {
        path.writeText("""{"maxSessions":3,"port":9001}""")

        assertEquals(json("""{"port":9001}"""), written("""{"maxSessions":null}"""))
        assertEquals(DaemonConfig.DEFAULT_MAX_SESSIONS, DaemonConfig.load(dataDir = tmp).maxSessions)
    }

    @Test
    fun `a file that cannot be read is reported and left byte for byte as it was`() {
        for (text in listOf("""{"maxSessions":3,""", """{"port":"not a number"}""", "[1,2]")) {
            path.writeText(text)
            val before = path.readBytes()

            assertIs<ConfigFileState.Malformed>(file.read(), text)
            val result = file.merge(json("""{"maxSessions":9}"""))

            assertIs<ConfigMergeResult.Unreadable>(result, text)
            assertContentEquals(before, path.readBytes(), "a save overwrote a file it could not read: $text")
        }
    }

    /**
     * Replaced, not rewritten. A new file id is what a rename over the old file gives and what
     * writing into it does not; a reader of the old file, or a crash mid-write, then sees one whole
     * file or the other. And the temporary file is gone afterwards.
     */
    @Test
    fun `a save writes a temporary file beside the old one and renames it over`() {
        path.writeText("""{"maxSessions":3}""")
        val before = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()

        written("""{"maxSessions":9}""")

        val after = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
        assertTrue(before != null && after != null, "this file system reports no file key; the check means nothing here")
        assertNotEquals(before, after, "config.json was written in place rather than replaced")
        assertEquals(listOf("config.json"), tmp.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `a config kept behind a symlink stays a symlink`() {
        val real = Files.createDirectories(tmp.resolve("dotfiles")).resolve("inspector.json")
        real.writeText("""{"maxSessions":3}""")
        Files.createSymbolicLink(path, real)

        written("""{"maxSessions":9}""")

        assertTrue(path.isSymbolicLink(), "the save replaced the link with a plain file")
        assertEquals(json("""{"maxSessions":9}"""), json(real.readText()))
    }

    @Test
    fun `saves from two threads keep each other's keys`() {
        val threads = listOf("maxSessions", "maxTotalMb").map { key ->
            Thread { repeat(25) { n -> file.merge(json("""{"$key":${n + 1}}""")) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(json("""{"maxSessions":25,"maxTotalMb":25}""").keys, json(path.readText()).keys)
    }

    // --- routeTemplates ----------------------------------------------------------------------------

    @Test
    fun `a valid routeTemplates list is written and the daemon's reader reads it back`() {
        written("""{"routeTemplates":["/reports/2024","/articles/{slug}"]}""")

        assertEquals(listOf("/reports/2024", "/articles/{slug}"), DaemonConfig.load(dataDir = tmp).routeTemplates.entries)
    }

    @Test
    fun `a bad routeTemplates value is refused in this file's words and nothing is written`() {
        path.writeText("""{"maxSessions":3}""")
        val before = path.readBytes()
        val cases = mapOf(
            """{"routeTemplates":["/ok/{id}","/a/{bad name}"]}""" to "routeTemplates entry '/a/{bad name}': segment '{bad name}' must be a whole placeholder",
            """{"routeTemplates":["articles"]}""" to "routeTemplates entry 'articles': a template starts with '/'",
            """{"routeTemplates":["/a?b=1"]}""" to "routeTemplates entry '/a?b=1': a template is a path only",
            """{"routeTemplates":"/articles/{slug}"}""" to "routeTemplates must be a list of path templates",
            """{"routeTemplates":["/a",5]}""" to "routeTemplates entry 5 must be a string",
            """{"routeTemplates":[null]}""" to "routeTemplates entry null must be a string",
        )
        val wrong = cases.mapNotNull { (patch, expected) ->
            val result = file.merge(json(patch))
            if (result is ConfigMergeResult.Invalid && expected in result.message) null else "$patch → $result"
        }
        assertEquals(emptyList(), wrong)
        assertContentEquals(before, path.readBytes(), "a refused save changed the file")
    }

    @Test
    fun `a null removes routeTemplates and a save of other keys leaves it byte for byte`() {
        written("""{"routeTemplates":["/articles/{slug}","/reports/2024"]}""")
        val list = path.readText().substringAfter("\"routeTemplates\": ").substringBefore("]") + "]"

        written("""{"maxSessions":9}""")
        assertContains(path.readText(), "\"routeTemplates\": $list")

        assertEquals(json("""{"maxSessions":9}"""), written("""{"routeTemplates":null}"""))
        assertEquals(RouteTemplates.NONE, DaemonConfig.load(dataDir = tmp).routeTemplates)
    }
}
