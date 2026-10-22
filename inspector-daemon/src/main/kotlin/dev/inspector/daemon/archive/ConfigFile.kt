package dev.inspector.daemon.archive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What is on disk at `config.json`. */
sealed interface ConfigFileState {
    data object Missing : ConfigFileState
    data class Found(val json: JsonObject) : ConfigFileState
    /** [message] says what is wrong; the file is not touched while it says it. */
    data class Malformed(val message: String) : ConfigFileState
}

sealed interface ConfigMergeResult {
    data class Written(val json: JsonObject) : ConfigMergeResult
    /** The request: an unknown key, or a value [DaemonConfig] would not take. */
    data class Invalid(val message: String) : ConfigMergeResult
    /** The file: it could not be read, so it was left exactly as it was. */
    data class Unreadable(val message: String) : ConfigMergeResult
}

/**
 * `config.json` as a file to edit, for `PUT /api/config`. [DaemonConfig.load] stays the reader the
 * daemon starts from; nothing written here applies before the next start, except the `findings` key,
 * which [FindingSettingsSource] reads on the next findings read.
 *
 * A save merges into the file rather than replacing it. The file is also edited by hand and will
 * carry settings this build does not know, so a key it does not recognise is kept as it was; a
 * page that rewrote the file from the fields it shows would delete them on every save. The new
 * file is written beside the old one and renamed over it, so a crash mid-write leaves one whole
 * file or the other, never a half that [DaemonConfig.load] would then ignore as malformed.
 */
class ConfigFile(private val dataDir: Path) {

    val path: Path get() = dataDir.resolve(FILE_NAME)

    /** Read, merge and write under one lock, so two saves cannot each drop the other's key. */
    private val lock = Any()

    /**
     * Malformed means what [DaemonConfig.load] would ignore: not a JSON object, or a known key
     * holding the wrong type. A file the daemon cannot start from is not one a save may build on.
     */
    fun read(): ConfigFileState {
        if (!path.exists()) return ConfigFileState.Missing
        val text = runCatching { path.readText() }.getOrElse {
            return ConfigFileState.Malformed("could not read $path: ${it.message}")
        }
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return ConfigFileState.Malformed("$path is not a JSON object")
        // From the text, as load decodes it, so "malformed" here and "ignored" there cannot differ.
        runCatching { InspectorDaemonJson.decodeFromString(DaemonConfig.FileConfig.serializer(), text) }
            .onFailure { return ConfigFileState.Malformed("$path has a key holding the wrong type: ${it.message}") }
        return ConfigFileState.Found(json)
    }

    /**
     * Merges the known keys in [patch] into the file. A top-level `null` removes that key, so the
     * default applies again; a `null` inside `signalCaps` is kept, because there it means "uncap".
     * Any key this file does not know is refused by name, not dropped: a misspelt key silently
     * ignored is a setting the user believes they made.
     */
    fun merge(patch: JsonObject): ConfigMergeResult = synchronized(lock) {
        validate(patch)?.let { return ConfigMergeResult.Invalid(it) }

        val existing = when (val state = read()) {
            ConfigFileState.Missing -> JsonObject(emptyMap())
            is ConfigFileState.Found -> state.json
            is ConfigFileState.Malformed -> return ConfigMergeResult.Unreadable(
                "${state.message}; fix or remove it by hand, a save will not overwrite settings it could not read",
            )
        }

        // Existing keys keep their place and unknown ones their value; new ones go at the end.
        val merged = LinkedHashMap<String, JsonElement>(existing)
        for ((key, value) in patch) {
            if (value is JsonNull) merged -= key else merged[key] = value
        }
        val json = JsonObject(merged)
        write(buildString { indent(json, 0) }.plus("\n"))
        ConfigMergeResult.Written(json)
    }

    private fun write(text: String) {
        // Through a symlink to the file it names, so a config kept in a dotfiles repository stays
        // linked: renaming over the link itself would replace it with a plain file.
        val target = if (path.isSymbolicLink()) path.toRealPath() else path
        val dir = target.parent
        Files.createDirectories(dir)
        // Same directory, so the rename never crosses a file system and can be atomic.
        val temp = dir.resolve("$FILE_NAME.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(temp, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Two-space indented JSON, by hand. `prettyPrint` goes through the serializer, which re-encodes
     * every number by its type, so a hand-written `2.50` came back `2.5`; a key this file only keeps
     * must come back as it was. A leaf's `toString()` is its text as parsed.
     */
    private fun StringBuilder.indent(element: JsonElement, depth: Int) {
        val pad = "  ".repeat(depth + 1)
        when (element) {
            is JsonObject -> if (element.isEmpty()) append("{}") else {
                append("{\n")
                element.entries.forEachIndexed { i, (key, value) ->
                    append(pad).append(JsonPrimitive(key)).append(": ")
                    indent(value, depth + 1)
                    append(if (i < element.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append('}')
            }
            is JsonArray -> if (element.isEmpty()) append("[]") else {
                append("[\n")
                element.forEachIndexed { i, value ->
                    append(pad)
                    indent(value, depth + 1)
                    append(if (i < element.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append(']')
            }
            else -> append(element.toString())
        }
    }

    /** The first thing wrong with [patch], in this file's words, or null when it is all valid. */
    private fun validate(patch: JsonObject): String? {
        val unknown = patch.keys.filter { it !in KNOWN_KEYS }
        if (unknown.isNotEmpty()) {
            return "unknown config key${if (unknown.size > 1) "s" else ""} " +
                unknown.joinToString { "'$it'" } + "; the keys are ${KNOWN_KEYS.joinToString()}"
        }
        for ((key, value) in patch) {
            if (value is JsonNull) continue
            val problem = when (key) {
                "port" -> value.wholeNumber()?.takeIf { it in 1..65_535 }
                    .let { if (it == null) "port must be a whole number from 1 to 65535" else null }
                "maxSessions" -> value.wholeNumber()?.takeIf { it in 1..Int.MAX_VALUE }
                    .let { if (it == null) "maxSessions must be a whole number of at least 1" else null }
                "maxTotalMb" -> value.wholeNumber()?.takeIf { it in 1..MAX_TOTAL_MB }
                    .let { if (it == null) "maxTotalMb must be a whole number of megabytes, at least 1" else null }
                "signalCaps" -> validateSignalCaps(value)
                "routeTemplates" -> validateRouteTemplates(value)
                "findings" -> FindingSettings.problem(value)
                "findingDismissals" -> SensitiveConfig.dismissalsProblem(value)
                else -> null
            }
            if (problem != null) return problem
        }
        return null
    }

    private fun validateSignalCaps(value: JsonElement): String? {
        val caps = value as? JsonObject
            ?: return "signalCaps must be an object of signal tag to cap, for example {\"cache\": 500}"
        for ((tag, cap) in caps) {
            if (tag.isBlank()) return "signalCaps has a blank tag; name the signal tag a cap applies to"
            if (cap is JsonNull) continue
            if ((cap.wholeNumber() ?: -1) !in 0..Int.MAX_VALUE) {
                return "signalCaps '$tag' must be null (keep every row) or a whole number of at least 0"
            }
        }
        return null
    }

    /**
     * A list of strings that [RouteTemplates.problem] accepts, every one. Refused whole, unlike the
     * file at start, where a bad entry is skipped: here somebody is looking at the reply, and a save
     * that kept nine entries of ten would report success for the tenth.
     */
    private fun validateRouteTemplates(value: JsonElement): String? {
        val entries = value as? JsonArray
            ?: return "routeTemplates must be a list of path templates, for example [\"/articles/{slug}\"]"
        val strings = entries.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return "routeTemplates entry $element must be a string, as in \"/articles/{slug}\""
        }
        return RouteTemplates.problems(strings).firstOrNull()
    }

    /** A JSON number with no fraction or exponent; `"8099"`, `true` and `1.5` are not. */
    private fun JsonElement.wholeNumber(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    private companion object {
        const val FILE_NAME = "config.json"

        /** [DaemonConfig.FileConfig]'s fields. */
        /** The most megabytes [DaemonConfig.maxTotalBytes] can hold as bytes. */
        const val MAX_TOTAL_MB = Long.MAX_VALUE / (1024L * 1024L)

        val KNOWN_KEYS = listOf("port", "maxSessions", "maxTotalMb", "signalCaps", "routeTemplates", "findings", "findingDismissals")
    }
}
