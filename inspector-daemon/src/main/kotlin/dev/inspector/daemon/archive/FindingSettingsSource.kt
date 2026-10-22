package dev.inspector.daemon.archive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The `findings` and `findingDismissals` keys of `config.json`, read on the next findings read rather
 * than at start.
 *
 * The one exception to "nothing applies until the next start". The daemon and `inspector mcp` are two
 * processes, and a key read once at start left an MCP server started before an edit disagreeing with
 * a restarted daemon about what counts as a finding, with nothing telling either of them; and a
 * dismissal that waited for a restart would read as a button that did nothing. So every [live]
 * stats the file, and re-parses the two keys only when its size, modified time or `fileKey()`
 * changed: an unchanged file costs one `stat`. Every other key, `routeTemplates` included, is still
 * read at start by [DaemonConfig.load].
 *
 * A hand edit that does not parse keeps the last good settings and dismissals and says so in the
 * error [live] returns; a bad entry inside a good key is named, skipped, and the rest applies. With
 * nothing good read yet, the defaults stand in. Each complaint goes to stderr once per change of the
 * file.
 */
class FindingSettingsSource internal constructor(
    private val file: Path,
    private val parse: (String) -> Parsed,
    private val log: (String) -> Unit,
) {
    constructor(dataDir: Path) : this(dataDir.resolve(FILE_NAME), Companion::parseFile, { System.err.println("inspector: $it") })

    /**
     * What one read of the file made of the keys: each to use, or null to keep the last good one.
     * Dismissals stay apart from [settings], so they never reach `ruleVersion` and never rescan a body.
     */
    internal class Parsed(
        val settings: FindingSettings?,
        val problems: List<String>,
        val dismissals: List<DismissalEntry>? = null,
    )

    /** The keys as of one read, and the complaint about the file when it had one. */
    data class Live(val settings: FindingSettings, val dismissals: List<DismissalEntry>, val error: String?)

    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)

    private var stamp: Stamp? = null
    private var live = Live(FindingSettings.DEFAULT, emptyList(), null)

    /** The settings to use now, and the complaint about the file when it had one. */
    fun current(): Pair<FindingSettings, String?> = live().let { it.settings to it.error }

    /** Both keys as they apply now. */
    @Synchronized
    fun live(): Live {
        val now = stampOf(file)
        if (now == stamp) return live
        stamp = now
        if (now == null) {
            live = Live(FindingSettings.DEFAULT, emptyList(), null)
            return live
        }
        val text = runCatching { Files.readString(file) }.getOrElse {
            val error = "could not read $file: ${it.message}"
            log(error)
            live = live.copy(error = error)
            return live
        }
        val parsed = parse(text)
        live = Live(
            settings = parsed.settings ?: live.settings,
            dismissals = parsed.dismissals ?: live.dismissals,
            error = parsed.problems.firstOrNull(),
        )
        parsed.problems.forEach { log("$FILE_NAME: $it") }
        return live
    }

    internal companion object {
        const val FILE_NAME = "config.json"

        fun parseFile(text: String): Parsed {
            val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return Parsed(null, listOf("$FILE_NAME is not a JSON object"))
            val problems = ArrayList<String>()
            val value = json["findings"]
            val settings = if (value != null && value !is JsonObject && value !is JsonNull) {
                problems += FindingSettings.problem(value) ?: "findings must be an object"
                null
            } else {
                FindingSettings.read(value) { problems += it }
            }
            val listed = json["findingDismissals"]
            val dismissals = if (listed != null && listed !is JsonArray && listed !is JsonNull) {
                problems += SensitiveConfig.dismissalsProblem(listed) ?: "findingDismissals must be a list"
                null
            } else {
                SensitiveConfig.readDismissals(listed) { problems += it }
            }
            return Parsed(settings, problems, dismissals)
        }

        private fun stampOf(file: Path): Stamp? = runCatching {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }.getOrNull()
    }
}
