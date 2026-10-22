package dev.inspector.daemon.service

import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.NetworkTransaction
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One direction of a call. [key] is the string `SessionRepository.readBody` takes and the `side` the
 * Redactor writes into `redacted[]` (`body:req:$.pin`).
 */
enum class BodySide(val key: String) {
    REQ("req"),
    RES("res"),
}

/**
 * How much of one body the archive holds: the one answer to "did capture keep this body whole".
 *
 * Findings, the session diff and sensitive-data detection each need it, and answered three times it
 * would disagree: a row one reads as complete and another as unknown is the false claim
 * `BodyOmission` exists to prevent. [key] is the word every surface prints, so "body unknown:
 * truncated" reads the same in a finding, the diff and an export.
 */
enum class BodyState(val key: String) {
    /** Kept whole, declared JSON, and (once a consumer parses it) strict UTF-8 and strict JSON. */
    COMPLETE("complete"),

    /** There was no body: nothing to keep. */
    EMPTY("empty"),

    /** Capture kept a prefix (`bodyCaptureMaxBytes`). Never opened: a prefix is not the body. */
    TRUNCATED("truncated"),

    /** Capture kept nothing on purpose: content type, streaming, discarded. */
    OMITTED("omitted"),

    /** The row counts bytes the archive does not hold. */
    MISSING("missing"),

    /** Kept whole, not declared JSON. */
    NOT_JSON("notJson"),

    /** Declared JSON and kept whole, but not strict UTF-8 or not strict JSON. */
    UNPARSED("unparsed"),
}

/**
 * [BodyState] for a row, and its bytes when the archive holds them whole.
 *
 * [state] reads the row and never the disk, so a truncated or omitted body is classified without
 * its file being opened. Two states only a read can reach are left to the reader: a ref whose file
 * is gone reads as [BodyState.MISSING] (here, [read] returns null), and a JSON body that does not
 * parse strictly is [BodyState.UNPARSED], decided by whichever consumer parses (the diff does;
 * findings compare bytes and never parse). What counts as known is the consumer's: the diff needs a
 * shape, so only [BodyState.COMPLETE] and [BodyState.EMPTY] are, while a byte comparison can use
 * every body [read] returns.
 */
object BodyEvidence {

    /** The state of [side] of [txn], from the row alone. Never [BodyState.UNPARSED]. */
    fun state(txn: NetworkTransaction, side: BodySide): BodyState {
        val omitted = if (side == BodySide.REQ) txn.reqBodyOmitted else txn.resBodyOmitted
        val truncated = if (side == BodySide.REQ) txn.reqBodyTruncated else txn.resBodyTruncated
        val ref = if (side == BodySide.REQ) txn.reqBodyRef else txn.resBodyRef
        val bytes = if (side == BodySide.REQ) txn.reqBytes else txn.resBytes
        val type = if (side == BodySide.REQ) txn.reqContentType else txn.resContentType
        return when {
            omitted != null -> BodyState.OMITTED
            truncated -> BodyState.TRUNCATED
            bytes == 0L -> BodyState.EMPTY
            ref == null -> BodyState.MISSING
            // The test `Redactor.body` uses, so redaction and every reader agree on which bodies are JSON.
            type?.contains("json", ignoreCase = true) != true -> BodyState.NOT_JSON
            else -> BodyState.COMPLETE
        }
    }

    /**
     * The bytes of [side] of [txn] when the archive holds the body whole, of any content type, and
     * null otherwise: for every unknown state, and for a ref whose file is gone. An empty body is an
     * empty array, read from nothing. A truncated body's prefix is never returned, so no consumer can
     * mistake it for the body.
     */
    fun read(repository: SessionRepository, dir: Path, txn: NetworkTransaction, side: BodySide): ByteArray? =
        read(state(txn, side)) { repository.readBody(dir, txn.id, side.key) }

    /**
     * [read] for a caller that kept only the row's [state], as a fold does: the bytes from [file]
     * when the state is whole, an empty array when there was no body, otherwise null.
     */
    fun read(state: BodyState, file: () -> ByteArray?): ByteArray? = when (state) {
        BodyState.EMPTY -> ByteArray(0)
        BodyState.COMPLETE, BodyState.NOT_JSON -> file()
        else -> null
    }
}

/**
 * JSON paths in the Redactor's own syntax (`$`, `$.key`, `$.items[2]`).
 *
 * The names and signatures here are a contract between the session diff and sensitive-data
 * detection: both read the same paths the same way, so "skipped because redacted" and "not compared
 * because redacted" cannot disagree. Do not rename them.
 */
object JsonPaths {

    /** How deep [walk] goes by default: deeper elements are not visited. */
    const val MAX_DEPTH = 32

    /**
     * Visits [element] and everything under it, each with its concrete path exactly as the Redactor
     * writes it after `body:<side>:`: `$` for the root, `$.key` for a member (the key as written),
     * `$.items[2]` for an array element. So a walked path and a `redacted[]` entry compare as strings.
     *
     * An element more than [maxDepth] steps below the root is not visited. Returns false when
     * anything was left out for that reason, so a caller can say it did not see the whole body.
     */
    fun walk(element: JsonElement, maxDepth: Int = MAX_DEPTH, visit: (path: String, element: JsonElement) -> Unit): Boolean =
        walkFrom(element, "$", 0, maxDepth, visit)

    private fun walkFrom(
        element: JsonElement,
        path: String,
        depth: Int,
        maxDepth: Int,
        visit: (String, JsonElement) -> Unit,
    ): Boolean {
        visit(path, element)
        val children: List<Pair<String, JsonElement>> = when (element) {
            is JsonObject -> element.entries.map { (key, value) -> "$path.$key" to value }
            is JsonArray -> element.mapIndexed { index, value -> "$path[$index]" to value }
            else -> return true
        }
        if (children.isEmpty()) return true
        if (depth >= maxDepth) return false
        var whole = true
        for ((childPath, child) in children) {
            if (!walkFrom(child, childPath, depth + 1, maxDepth, visit)) whole = false
        }
        return whole
    }

    /** [path] with every array index written `[*]`: `$.items[3].id` is `$.items[*].id`. */
    fun group(path: String): String = INDEX.replace(path, "[*]")

    /**
     * [path] with every object key the route heuristic reads as an id written `{id}`:
     * `$.accounts.12345.balance` is `$.accounts.{id}.balance`. The session diff compares paths after
     * this, so a map keyed by account ids is one path rather than one per account, and two runs with
     * different accounts do not read as every key removed and added.
     *
     * The heuristic is [RouteTemplates]'s, called and not copied, so a key and a path segment are
     * judged alike; among what it misses, a map keyed by currency codes or by e-mail addresses keeps
     * its keys literal. A key holding `.` or `[` was already ambiguous in the Redactor's syntax, and
     * is judged piece by piece. Detection need not call this: its paths name the field a value was in.
     */
    fun foldIdKeys(path: String): String = MEMBER.replace(path) { match ->
        if (RouteTemplates.isIdLike(match.groupValues[1])) ".{id}" else match.value
    }

    private val MEMBER = Regex("\\.([^.\\[]*)")

    /**
     * True when [path] is one of [prefixes] or lies under one: under means the next character after
     * the prefix starts a member (`.`) or an index (`[`). A bare `startsWith` would put
     * `$.cardholder` under `$.card`, and a redacted card would hide its holder's name.
     */
    fun isAtOrUnder(path: String, prefixes: Collection<String>): Boolean = prefixes.any { prefix ->
        path == prefix ||
            (path.length > prefix.length && path.startsWith(prefix) && (path[prefix.length] == '.' || path[prefix.length] == '['))
    }

    private val INDEX = Regex("\\[\\d+]")

    /**
     * The paths of [side] of [txn] that `redacted[]` says capture replaced, as the Redactor wrote them
     * after `body:<side>:`. The older form, `body:$.x`, named no side and counts for both, because
     * `docs/schema.md` still shows it and an archive written that way must not read as unredacted.
     */
    fun redactedPaths(txn: NetworkTransaction, side: BodySide): Set<String> {
        if (txn.redacted.isEmpty()) return emptySet()
        val prefix = "body:${side.key}:"
        val paths = LinkedHashSet<String>()
        for (entry in txn.redacted) {
            when {
                entry.startsWith(prefix) -> paths += entry.substring(prefix.length)
                entry.startsWith(OLDER_PREFIX) -> paths += entry.substring(OLDER_PREFIX.length - 1)
            }
        }
        return paths
    }

    private const val OLDER_PREFIX = "body:$"
}
