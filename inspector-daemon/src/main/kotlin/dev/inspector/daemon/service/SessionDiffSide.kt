package dev.inspector.daemon.service

import dev.inspector.daemon.archive.RouteKey
import dev.inspector.model.NetworkTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How much body reading one diff may do. The defaults are the design's; tests lower them.
 *
 * A body not read because of a cap is `unread`, which is unknown, so a cap can only ever move a claim
 * into `unsure` and never make one. Truncated and omitted bodies are never opened, so the largest
 * responses cost nothing.
 */
data class DiffCaps(
    /** Complete bodies read per route, direction and side, in file order. */
    val bodiesPerRoute: Int = 500,
    /** Body bytes read per diff, both sides together. */
    val bytesBudget: Long = 256L * 1024 * 1024,
    /** Distinct key paths kept per route, direction and side; past it the direction is not compared. */
    val pathsPerRoute: Int = 2000,
)

/** The byte budget both sides of one diff draw on. Not thread-safe: one compute owns it. */
class DiffBudget(val limitBytes: Long) {
    var bodiesRead = 0
        private set
    var bytesRead = 0L
        private set
    var exhausted = false
        private set

    /** Whether [bytes] more may be read; once refused, the budget reads as exhausted. */
    fun allows(bytes: Long): Boolean {
        if (bytesRead + bytes <= limitBytes) return true
        exhausted = true
        return false
    }

    fun spend(bytes: Int) {
        bodiesRead++
        bytesRead += bytes
    }
}

/**
 * One side of a diff, folded row by row: per route, what route stats do not keep.
 *
 * It sees only the rows the side's [RouteStatsAccumulator] kept, under the key that accumulator
 * counted each under, so the stats row and these tallies always describe the same attempts. Bodies
 * are read here, inside the fold and in file order, and only when the row says the body is whole.
 */
class DiffSideAccumulator(
    private val caps: DiffCaps,
    private val budget: DiffBudget,
    private val body: (NetworkTransaction, BodySide) -> ByteArray?,
) {
    val routes = HashMap<RouteKey, RouteDiffTally>()

    fun add(key: RouteKey, txn: NetworkTransaction) {
        val route = routes.getOrPut(key) { RouteDiffTally(caps) }
        val status = txn.status
        val outcome = status?.toString() ?: if (txn.error != null) TRANSPORT else null
        // An in-flight row is not an outcome, as in route stats.
        if (outcome != null) route.outcomes[outcome] = (route.outcomes[outcome] ?: 0) + 1
        route.requestHeaders.add(txn.reqHeaders.keys)
        route.request.add(txn, BodySide.REQ)
        // Only an attempt that got a response has response headers or a response body to speak of: a
        // transport failure counted as an empty body would make a JSON route read as "became empty".
        if (status != null) {
            route.responseHeaders.add(txn.resHeaders.keys)
            route.response.add(txn, BodySide.RES)
        }
    }

    /** What one route holds on one side. */
    inner class RouteDiffTally(caps: DiffCaps) {
        /** Attempts per exact status code, and [TRANSPORT] for a failure with no status. */
        val outcomes = HashMap<String, Int>()
        val requestHeaders = HeaderNames()
        val responseHeaders = HeaderNames()
        val request = ShapeTally(caps)
        val response = ShapeTally(caps)
    }

    /** One direction's bodies on one route and side: the state of each, and the shape of the whole ones. */
    inner class ShapeTally(private val caps: DiffCaps) {
        /** Attempts per [BodyCount] word. */
        val counts = IntArray(BodyCount.entries.size)

        /** Grouped, folded path to what the complete samples say of it. Capped at [DiffCaps.pathsPerRoute]. */
        val paths = HashMap<String, PathStat>()

        /** Grouped, folded paths `redacted[]` names on this side's rows, and leaves holding the placeholder. */
        val redacted = HashSet<String>()

        /** Why this direction's shape cannot be compared at all, or null. */
        var wide: Wide? = null

        private var read = 0

        val complete: Int get() = counts[BodyCount.COMPLETE.ordinal]

        /** No body on this side and direction is unknown: every one was read whole, or there was none. */
        val known: Boolean get() = BodyCount.entries.none { it.unknown && counts[it.ordinal] > 0 }

        fun add(txn: NetworkTransaction, side: BodySide) {
            for (path in JsonPaths.redactedPaths(txn, side)) redacted += fold(path)
            counts[classify(txn, side).ordinal]++
        }

        private fun classify(txn: NetworkTransaction, side: BodySide): BodyCount {
            val state = BodyEvidence.state(txn, side)
            if (state != BodyState.COMPLETE) return BodyCount.of(state)
            val declared = if (side == BodySide.REQ) txn.reqBytes else txn.resBytes
            if (wide != null || read >= caps.bodiesPerRoute || !budget.allows(declared)) return BodyCount.UNREAD
            val bytes = BodyEvidence.read(state) { body(txn, side) } ?: return BodyCount.MISSING
            read++
            budget.spend(bytes.size)
            val element = strictJsonOrNull(bytes) ?: return BodyCount.UNPARSED
            sample(element)
            return BodyCount.COMPLETE
        }

        /** Merges one complete body's paths: each path counts once per sample, however often it occurs. */
        private fun sample(element: JsonElement) {
            val seen = HashMap<String, Seen>()
            val whole = JsonPaths.walk(element) { path, value ->
                val folded = fold(path)
                val entry = seen.getOrPut(folded) { Seen() }
                entry.types += typeOf(value)
                if (value is JsonObject) entry.objectHere = true
                if (value is JsonArray && value.isNotEmpty()) entry.itemsHere = true
                if (value is JsonPrimitive && value.isString && value.content == JsonPaths.PLACEHOLDER) redacted += folded
            }
            if (!whole) wide = Wide.DEPTH
            for ((path, entry) in seen) {
                val stat = paths[path] ?: if (paths.size < caps.pathsPerRoute) PathStat().also { paths[path] = it } else {
                    wide = wide ?: Wide.PATHS
                    continue
                }
                stat.samples++
                stat.types += entry.types
                if (entry.objectHere) stat.objectIn++
                if (entry.itemsHere) stat.itemsIn++
            }
        }
    }

    private class Seen {
        val types = HashSet<String>(2)
        var objectHere = false
        var itemsHere = false
    }

    companion object {
        /** The outcome key of an attempt that failed with no status. */
        const val TRANSPORT = "transport"

        private val STRICT = Json

        /** The path the diff compares: indices as `[*]`, then id-like keys as `{id}`. */
        fun fold(path: String): String = JsonPaths.foldIdKeys(JsonPaths.group(path))

        fun typeOf(value: JsonElement): String = when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.content == "true" || value.content == "false" -> "boolean"
                else -> "number"
            }
        }

        /**
         * [bytes] as JSON when they are strict UTF-8 and strict JSON, else null.
         *
         * kotlinx's default parser refuses an unquoted key and a trailing comma, but takes any unquoted
         * value as a literal (`NaN`, `01`, a bare word), so every literal is checked against JSON's own
         * grammar too. Without that, a body a server wrote with `NaN` in it would be a shape sample.
         */
        fun strictJsonOrNull(bytes: ByteArray): JsonElement? {
            val text = bytes.decodeUtf8OrNull() ?: return null
            val element = runCatching { STRICT.parseToJsonElement(text) }.getOrNull() ?: return null
            return if (literalsStrict(element)) element else null
        }

        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

        private fun literalsStrict(element: JsonElement): Boolean = when (element) {
            is JsonObject -> element.values.all(::literalsStrict)
            is JsonArray -> element.all(::literalsStrict)
            JsonNull -> true
            is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" ||
                NUMBER.matches(element.content)
        }
    }
}

/** What the complete samples on one side say of one path. */
class PathStat {
    /** Samples the path is present in. */
    var samples = 0

    /** Samples where the path is an object, so its members are observable there. */
    var objectIn = 0

    /** Samples where the path is a non-empty array, so `path[*]` is observable there. */
    var itemsIn = 0

    val types = sortedSetOf<String>()
}

/** Lowercased header names, and how many attempts carried each. Values are never kept. */
class HeaderNames {
    val counts = HashMap<String, Int>()

    fun add(names: Collection<String>) {
        for (name in names.mapTo(HashSet()) { it.lowercase() }) counts[name] = (counts[name] ?: 0) + 1
    }
}

/** Why a direction's shape is not compared even with complete samples on both sides. */
enum class Wide { DEPTH, PATHS }

/**
 * The words a body tally uses: [BodyState]'s, plus `unread` for a whole body a cap left unread.
 * [unknown] is what makes a side not known: everything but complete, empty and notJson.
 */
enum class BodyCount(val key: String, val unknown: Boolean) {
    COMPLETE("complete", false),
    EMPTY("empty", false),
    TRUNCATED("truncated", true),
    OMITTED("omitted", true),
    MISSING("missing", true),
    NOT_JSON("notJson", false),
    UNPARSED("unparsed", true),
    UNREAD("unread", true);

    companion object {
        fun of(state: BodyState): BodyCount = when (state) {
            BodyState.COMPLETE -> COMPLETE
            BodyState.EMPTY -> EMPTY
            BodyState.TRUNCATED -> TRUNCATED
            BodyState.OMITTED -> OMITTED
            BodyState.MISSING -> MISSING
            BodyState.NOT_JSON -> NOT_JSON
            BodyState.UNPARSED -> UNPARSED
        }
    }
}
