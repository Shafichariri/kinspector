package dev.inspector.daemon.service

/**
 * Body shape compared across two sessions, for one route and direction: the rule that a body capture
 * did not keep whole is unknown, and a key is never called removed because of one.
 *
 * Shape comes only from complete bodies that parse strictly ([DiffSideAccumulator.strictJsonOrNull]);
 * a truncated body is never opened, because a lenient walk of a cut prefix reports every key after the
 * cut as removed, on exactly the large responses people diff. On top of the roadmap's rule, two
 * tightenings, both of which only move claims into `unsure` (decided 2026-10-22 by the maintainer's
 * proxy): a path is removed or added only when **every** body on the other side is known, so one
 * truncated large response holding the key cannot be outvoted by small complete ones; and nothing at
 * or under a redacted path is compared, presence included, because the Redactor replaces a value or a
 * whole object with a placeholder string. A path is only claimed missing from a side where its parent
 * was there to hold it (observable): a key under arrays that were all empty is not removed.
 */
internal object ShapeCompare {

    class Result(val diff: ShapeDiff, val changed: Boolean)

    fun compare(a: DiffSideAccumulator.ShapeTally, b: DiffSideAccumulator.ShapeTally): Result {
        val tallyA = tally(a)
        val tallyB = tally(b)
        // A JSON response that became empty is a change; a side whose bodies are unknown says nothing.
        val kindsChanged = tallyA.kind != tallyB.kind && tallyA.kind != UNKNOWN && tallyB.kind != UNKNOWN
        val why = whyNot(a, "A") + whyNot(b, "B")
        if (why.isNotEmpty()) {
            val diff = ShapeDiff(
                compared = false, why = why.joinToString("; "), a = tallyA, b = tallyB,
                added = emptyList(), removed = emptyList(), typeChanged = emptyList(), unsure = emptyList(),
                notCompared = emptyList(),
            )
            return Result(diff, kindsChanged)
        }

        val excluded = a.redacted + b.redacted
        val added = ArrayList<ShapePath>()
        val removed = ArrayList<ShapePath>()
        val typeChanged = ArrayList<ShapeTypeChange>()
        val unsure = ArrayList<ShapeUnsure>()
        val notCompared = ArrayList<String>()
        // Every path claimed removed or added so far, sure or not, so a subtree lists only its top.
        val claimedRemoved = HashSet<String>()
        val claimedAdded = HashSet<String>()
        for (path in (a.paths.keys + b.paths.keys).sorted()) {
            if (JsonPaths.isAtOrUnder(path, excluded)) {
                notCompared += path
                continue
            }
            val inA = a.paths[path]
            val inB = b.paths[path]
            fun unsure(would: String) = ShapeUnsure(
                path, would, inA?.types?.toList(), inB?.types?.toList(),
                inA?.samples ?: 0, a.complete, inB?.samples ?: 0, b.complete,
            )
            when {
                inA != null && inB != null -> if (inA.types != inB.types) {
                    if (a.known && b.known) typeChanged += ShapeTypeChange(path, inA.types.toList(), inB.types.toList())
                    else unsure += unsure("typeChanged")
                }
                inA != null -> if (observable(b, path) && !underAny(path, claimedRemoved)) {
                    claimedRemoved += path
                    if (b.known) removed += ShapePath(path, inA.types.toList(), inA.samples, a.complete)
                    else unsure += unsure("removed")
                }
                inB != null -> if (observable(a, path) && !underAny(path, claimedAdded)) {
                    claimedAdded += path
                    if (a.known) added += ShapePath(path, inB.types.toList(), inB.samples, b.complete)
                    else unsure += unsure("added")
                }
            }
        }
        val max = SessionDiff.MAX_PATHS
        val diff = ShapeDiff(
            compared = true, why = null, a = tallyA, b = tallyB,
            added = added.take(max), removed = removed.take(max), typeChanged = typeChanged.take(max),
            unsure = unsure.take(max), notCompared = notCompared.take(max),
            more = SessionDiff.cutCounts(
                "added" to added.size, "removed" to removed.size, "typeChanged" to typeChanged.size,
                "unsure" to unsure.size, "notCompared" to notCompared.size, max = max,
            ),
        )
        val changed = kindsChanged || added.isNotEmpty() || removed.isNotEmpty() || typeChanged.isNotEmpty()
        return Result(diff, changed)
    }

    /**
     * Whether [path]'s parent was there to hold it in at least one complete sample of [side]: an
     * object for a member, a non-empty array for `[*]`. `$` is observable in every sample.
     */
    private fun observable(side: DiffSideAccumulator.ShapeTally, path: String): Boolean {
        if (path == ROOT) return side.complete > 0
        val parent = side.paths[parentOf(path)] ?: return false
        return if (path.endsWith(ITEMS)) parent.itemsIn > 0 else parent.objectIn > 0
    }

    /** Whether a strict ancestor of [path] is in [claimed]: a subtree lists only its top-most path. */
    private fun underAny(path: String, claimed: Set<String>): Boolean {
        var current = path
        while (current != ROOT) {
            current = parentOf(current)
            if (current in claimed) return true
        }
        return false
    }

    /** `$.a.b` → `$.a`, `$.a[*]` → `$.a`, `$.a` → `$`. A key holding `.` was already ambiguous in this syntax. */
    private fun parentOf(path: String): String =
        if (path.endsWith(ITEMS)) path.dropLast(ITEMS.length) else path.substring(0, path.lastIndexOf('.').coerceAtLeast(1))

    private fun tally(side: DiffSideAccumulator.ShapeTally): BodyTally {
        fun n(count: BodyCount) = side.counts[count.ordinal]
        val unknown = BodyCount.entries.any { it.unknown && n(it) > 0 }
        val kind = when {
            n(BodyCount.COMPLETE) > 0 -> "json"
            unknown -> UNKNOWN
            n(BodyCount.NOT_JSON) > 0 -> "notJson"
            else -> "empty"
        }
        return BodyTally(
            kind = kind, complete = n(BodyCount.COMPLETE), empty = n(BodyCount.EMPTY),
            truncated = n(BodyCount.TRUNCATED), omitted = n(BodyCount.OMITTED), missing = n(BodyCount.MISSING),
            notJson = n(BodyCount.NOT_JSON), unparsed = n(BodyCount.UNPARSED), unread = n(BodyCount.UNREAD),
        )
    }

    /** Why [side] gives this direction nothing to compare, in words printed verbatim, or none. */
    private fun whyNot(side: DiffSideAccumulator.ShapeTally, name: String): List<String> = buildList {
        if (side.complete == 0) {
            val held = BodyCount.entries.filter { side.counts[it.ordinal] > 0 }
                .joinToString(", ") { "${side.counts[it.ordinal]} ${WORDS.getValue(it)}" }
            add("$name kept no complete JSON body here: ${held.ifEmpty { "no response" }}")
        }
        when (side.wide) {
            Wide.DEPTH -> add("$name's JSON here nests deeper than ${JsonPaths.MAX_DEPTH} levels, past what the diff walks")
            Wide.PATHS -> add("$name's JSON here holds more distinct key paths than the diff keeps")
            null -> Unit
        }
    }

    private const val ROOT = "$"
    private const val ITEMS = "[*]"
    private const val UNKNOWN = "unknown"

    private val WORDS = mapOf(
        BodyCount.COMPLETE to "complete", BodyCount.EMPTY to "empty", BodyCount.TRUNCATED to "truncated",
        BodyCount.OMITTED to "omitted", BodyCount.MISSING to "missing", BodyCount.NOT_JSON to "not JSON",
        BodyCount.UNPARSED to "unparsed", BodyCount.UNREAD to "unread",
    )
}
