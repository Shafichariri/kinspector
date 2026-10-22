package dev.inspector.daemon.archive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The `findings` key of `config.json`, read on the next findings read rather than at start.
 *
 * The one exception to "nothing applies until the next start". The daemon and `inspector mcp` are two
 * processes, and a key read once at start left an MCP server started before an edit disagreeing with
 * a restarted daemon about what counts as a finding, with nothing telling either of them. So every
 * [current] stats the file, and re-parses the key only when its size, modified time or `fileKey()`
 * changed: an unchanged file costs one `stat`. Every other key, `routeTemplates` included, is still
 * read at start by [DaemonConfig.load].
 *
 * A hand edit that does not parse keeps the last good settings and says so in the error [current]
 * returns; a bad entry inside a good key is named, skipped, and the rest applies. With no good
 * settings read yet, the defaults stand in. Each complaint goes to stderr once per change of the file.
 */
class FindingSettingsSource internal constructor(
    private val file: Path,
    private val parse: (String) -> Parsed,
    private val log: (String) -> Unit,
) {
    constructor(dataDir: Path) : this(dataDir.resolve(FILE_NAME), Companion::parseFile, { System.err.println("inspector: $it") })

    /** What one read of the file made of the key: settings to use, or null to keep the last good ones. */
    internal class Parsed(val settings: FindingSettings?, val problems: List<String>)

    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)

    private var stamp: Stamp? = null
    private var settings: FindingSettings = FindingSettings.DEFAULT
    private var error: String? = null

    /** The settings to use now, and the complaint about the file when it had one. */
    @Synchronized
    fun current(): Pair<FindingSettings, String?> {
        val now = stampOf(file)
        if (now == stamp) return settings to error
        stamp = now
        if (now == null) {
            settings = FindingSettings.DEFAULT
            error = null
            return settings to error
        }
        val text = runCatching { Files.readString(file) }.getOrElse {
            error = "could not read $file: ${it.message}"
            log(error!!)
            return settings to error
        }
        val parsed = parse(text)
        parsed.settings?.let { settings = it }
        error = parsed.problems.firstOrNull()
        parsed.problems.forEach { log("$FILE_NAME: $it") }
        return settings to error
    }

    internal companion object {
        const val FILE_NAME = "config.json"

        fun parseFile(text: String): Parsed {
            val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return Parsed(null, listOf("$FILE_NAME is not a JSON object"))
            val value = json["findings"]
            if (value != null && value !is JsonObject && value !is JsonNull) {
                return Parsed(null, listOf(FindingSettings.problem(value) ?: "findings must be an object"))
            }
            val problems = ArrayList<String>()
            val settings = FindingSettings.read(value) { problems += it }
            return Parsed(settings, problems)
        }

        private fun stampOf(file: Path): Stamp? = runCatching {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }.getOrNull()
    }
}
