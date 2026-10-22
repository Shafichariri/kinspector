package dev.inspector.daemon.archive

import dev.inspector.model.Filter
import dev.inspector.model.FilterParser
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.defaultPortFor

/** Where a route's template came from: an entry in `routeTemplates`, or the segment heuristic. */
enum class RouteSource(val label: String) {
    CONFIG("config"),
    HEURISTIC("heuristic"),
}

/**
 * A path template: [text] as displayed, with [placeholders] marking which of its segments stand
 * for any value.
 *
 * The mask is kept rather than re-read from [text], because a captured path may hold a literal
 * `{id}` segment, and that segment must stay literal in the filter term or the term stops matching
 * the very path it came from.
 */
data class Route(val text: String, val placeholders: List<Boolean>, val source: RouteSource)

/**
 * What a route row is keyed on: method, origin and route.
 *
 * The origin is in the key because one template on two servers is two routes behaving differently,
 * and the diff and the findings that reuse this key must not call two servers' calls one route.
 * [port] is null when it is the scheme's default, as `NetworkTransaction.hostPort` decides. [host]
 * is lowercased, and an IPv6 literal is bare whichever client stored it (see `serverHost`).
 */
data class RouteKey(
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val route: Route,
) {
    /**
     * The one definition of "mutation": POST, PUT, PATCH and DELETE. The payment rules and the
     * session diff read it rather than each listing methods. A computed property, outside the
     * constructor, so key equality and every call building a key are unchanged.
     */
    val mutation: Boolean get() = method in MUTATION_METHODS

    private companion object {
        val MUTATION_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

/**
 * The route rule: which paths are one route, and the filter term that finds a route's calls.
 *
 * A path's segments are compared as captured, percent escapes included. A segment reads as `{id}`
 * when it is numeric, a UUID, hex of 8 or more with a digit, or a base64-like run of 20 or more
 * with a digit and both cases. That is a heuristic, and among what it misses are a long camelCase
 * name with a digit (`listAccountsForUserV2` reads as `{id}`), slugs, short or lowercase-only
 * tokens, and ids with an extension (all stay literal). `RouteTemplatesTest` pins each miss so
 * nobody "fixes" one silently; the fix for any of them is an entry in `routeTemplates`.
 *
 * Here, in `archive/`, and not in `:inspector-model`: the page never derives a route, so the rule
 * needs no web mirror, and `ConfigFile` validates `routeTemplates` with [problem] and may import no
 * other daemon layer.
 *
 * Templates are matched segment by segment, never as regular expressions, so no config entry can
 * make a stats read slow.
 */
class RouteTemplates private constructor(
    /** The valid entries, in the order they are tried. What `GET /api/config` reports as loaded. */
    val entries: List<String>,
) {
    private val compiled: List<List<String?>> = entries.map { entry ->
        segmentsOf(entry).map { segment -> if (isPlaceholder(segment)) null else segment }
    }

    /**
     * The route of [path]. Query strings never reach here. Trailing slashes and case are kept: a
     * server that redirects `/a` to `/a/` makes two attempts with different outcomes, and RFC 3986
     * paths are case-sensitive.
     */
    fun routeOf(path: String): Route {
        val segments = segmentsOf(path.ifEmpty { "/" })
        for ((index, template) in compiled.withIndex()) {
            if (matches(template, segments)) {
                return Route(entries[index], template.map { it == null }, RouteSource.CONFIG)
            }
        }
        val placeholders = segments.map { isIdLike(it) }
        val text = segments.withIndex().joinToString("/", prefix = "/") { (i, segment) ->
            if (placeholders[i]) "{id}" else segment
        }
        return Route(text, placeholders, RouteSource.HEURISTIC)
    }

    /**
     * The one place a route key is built; the stats, the exports, MCP, and the diff and findings
     * after them all key through here.
     */
    fun keyOf(txn: NetworkTransaction): RouteKey = RouteKey(
        method = txn.method.uppercase(),
        scheme = txn.scheme.lowercase(),
        host = serverHost(txn.host),
        port = txn.port?.takeIf { it != defaultPortFor(txn.scheme) },
        route = routeOf(txn.path),
    )

    override fun equals(other: Any?): Boolean = other is RouteTemplates && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "RouteTemplates($entries)"

    companion object {
        /** Heuristic only: what a daemon with no `routeTemplates` runs on. */
        val NONE = RouteTemplates(emptyList())

        /** Bounds what every row costs to match; a list this long is a list nobody reads. */
        const val MAX_ENTRIES = 200

        const val MAX_ENTRY_LENGTH = 512

        /**
         * The valid entries of [entries], in order, up to [MAX_ENTRIES]. Each one skipped is passed
         * to [report] with the reason, so the rest still apply, as a negative `signalCaps` entry is
         * treated: a daemon that groups nine templates of ten is better than one that refuses to
         * start, or one that silently drops all ten.
         */
        fun compile(entries: List<String>, report: (String) -> Unit = {}): RouteTemplates {
            val valid = ArrayList<String>()
            for (entry in entries) {
                val problem = problem(entry)
                when {
                    problem != null -> report("ignoring routeTemplates entry '$entry': $problem")
                    valid.size == MAX_ENTRIES -> report("ignoring routeTemplates entry '$entry': only the first $MAX_ENTRIES are read")
                    else -> valid += entry
                }
            }
            return RouteTemplates(valid)
        }

        /** What is wrong with one template, or null when it is valid. In words a person can act on. */
        fun problem(entry: String): String? {
            if (!entry.startsWith("/")) return "a template starts with '/', as in '/articles/{slug}'"
            if (entry.length > MAX_ENTRY_LENGTH) return "a template is at most $MAX_ENTRY_LENGTH characters"
            if ('?' in entry || '#' in entry) return "a template is a path only; the query never takes part in a route"
            for (segment in segmentsOf(entry)) {
                if ('{' !in segment && '}' !in segment) continue
                if (!isPlaceholder(segment)) {
                    return "segment '$segment' must be a whole placeholder like {name} (letters, digits " +
                        "and _, not starting with a digit) or a literal with no braces"
                }
            }
            return null
        }

        /** The first thing wrong with [entries] as a whole list, or null. What `ConfigFile` refuses with. */
        fun problems(entries: List<String>): List<String> = buildList {
            if (entries.size > MAX_ENTRIES) add("routeTemplates holds ${entries.size} entries; the most is $MAX_ENTRIES")
            for (entry in entries) {
                problem(entry)?.let { add("routeTemplates entry '$entry': $it") }
            }
        }

        /**
         * The filter term that finds a route's calls: always a **superset**, never exact.
         *
         * The grammar is frozen and its `*` crosses `/`, so `path:/users/&#42;/profile` also matches
         * `/users/1/x/profile`. What makes the superset provable is replacing whole segments: the
         * term's path is the route's with each placeholder segment, and each segment the tokenizer
         * cannot carry, replaced by `*`, and `globMatches` matches any value its pattern was cut
         * from that way. A path left with no `*` gets one in front, the endpoint chips' shape,
         * because a glob with no star is an exact match and a plain value a substring one. The
         * root route gets no path term: no glob matches both an empty path and `/` without
         * matching every path. Normalising trailing slashes would break the proof, which is the
         * second reason they are kept. `RouteFilterTermTest` parses and matches every term.
         */
        fun filterTerm(key: RouteKey): String {
            val terms = ArrayList<String>(3)
            if (carriable(key.method)) terms += "method:${key.method}"
            if (carriable(key.host)) terms += "host:${key.host}"
            val segments = segmentsOf(key.route.text)
            if (!(segments.size == 1 && segments[0].isEmpty())) {
                val glob = segments.withIndex().joinToString("/", prefix = "/") { (i, segment) ->
                    if (key.route.placeholders[i] || !carriable(segment, allowEmpty = true)) "*" else segment
                }
                terms += "path:" + if ('*' in glob) glob else "*$glob"
            }
            return terms.joinToString(" ")
        }

        /**
         * [routeTerm] with the stats [filter] in front of it when the parser says the filter has no
         * top-level `|`, and whether it was carried.
         *
         * The grammar has no parentheses, so `a | b` followed by a term reads as `a | (b AND term)`.
         * Asked of the parser and not of the text, as the marker chips do, because a `|` inside
         * `since:marker("…")` quotes is not an OR. [filter] must already have parsed.
         */
        fun compose(filter: String, parsed: Filter, routeTerm: String): Pair<String, Boolean> {
            val trimmed = filter.trim()
            if (trimmed.isEmpty()) return routeTerm to true
            if (parsed is Filter.Or) return routeTerm to false
            val composed = if (routeTerm.isEmpty()) trimmed else "$trimmed $routeTerm"
            // Cannot fail for a filter that parsed, but a term that does not parse is no link at all.
            return if (FilterParser.parse(composed).isSuccess) composed to true else routeTerm to false
        }

        /**
         * [host] as one server reads in a key: lowercased, and an IPv6 literal without its brackets.
         *
         * OkHttp stores `::1` and Ktor `[::1]` for the same server, and capture keeps them as they
         * come (`capture.md` brackets on read), so a key on the stored host made one server two route
         * rows with half the calls each. Bare rather than bracketed, because the row's `host:` term is
         * a substring match: `::1` finds both stored forms, `[::1]` only Ktor's. The page and the
         * Markdown export bracket it again on read, through `hostPortOf` and `hostPort`. Only a
         * bracketed host holding a `:` is touched, so nothing that is not an IPv6 literal moves.
         */
        internal fun serverHost(host: String): String {
            val lower = host.lowercase()
            val bracketed = lower.length > 2 && lower.startsWith('[') && lower.endsWith(']') && ':' in lower
            return if (bracketed) lower.substring(1, lower.length - 1) else lower
        }

        /** `/`-separated, empty segments kept: `/` is `[""]`, `/users/` is `["users", ""]`. */
        internal fun segmentsOf(path: String): List<String> = path.substring(1).split('/')

        private val PLACEHOLDER = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")
        private val NUMERIC = Regex("[0-9]+")
        private val UUID = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
        private val LONG_HEX = Regex("[0-9A-Fa-f]{8,}")
        private val BASE64ISH = Regex("[A-Za-z0-9_-]{20,}={0,2}")

        private fun isPlaceholder(segment: String) = PLACEHOLDER.matches(segment)

        /**
         * The heuristic. Hex needs a digit so all-letter hex words (`deadbeefcafe`, `facade`) stay
         * literal; 8 is the shortest common opaque id, this archive's own ids included. Base64-like
         * needs a digit and both cases so lowercase slugs (`notification-preferences`) stay literal.
         * An empty segment is never an id.
         */
        internal fun isIdLike(segment: String): Boolean {
            if (segment.isEmpty()) return false
            if (NUMERIC.matches(segment) || UUID.matches(segment)) return true
            if (LONG_HEX.matches(segment) && segment.any { it in '0'..'9' }) return true
            return BASE64ISH.matches(segment) &&
                segment.any { it in '0'..'9' } && segment.any { it in 'A'..'Z' } && segment.any { it in 'a'..'z' }
        }

        private fun matches(template: List<String?>, segments: List<String>): Boolean {
            if (template.size != segments.size) return false
            for (i in template.indices) {
                val literal = template[i]
                if (literal == null) {
                    if (segments[i].isEmpty()) return false
                } else if (literal != segments[i]) {
                    return false
                }
            }
            return true
        }

        /**
         * True when the tokenizer passes [value] through as one term's value: it splits on
         * whitespace and `|` and toggles quoting on `"`, and the grammar has no escape.
         */
        private fun carriable(value: String, allowEmpty: Boolean = false): Boolean =
            (allowEmpty || value.isNotEmpty()) && value.none { it.isWhitespace() || it == '|' || it == '"' }
    }
}
