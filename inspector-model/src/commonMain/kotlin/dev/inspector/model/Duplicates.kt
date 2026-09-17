package dev.inspector.model

/** Default window for [duplicateGroups]. Three seconds, tunable per surface. */
const val DEFAULT_DUPLICATE_WINDOW_MS: Long = 3_000

/** Two or more separate calls that asked the same question inside the window. */
data class DuplicateGroup(
    /** Transaction ids, oldest first. */
    val ids: List<String>,
    /** First to last, in milliseconds. */
    val spanMs: Long,
    /** How many distinct logical calls are involved — always at least 2. */
    val callCount: Int,
)

/**
 * Finds requests that were sent more than once, close together, by *different* calls.
 *
 * The motivating case: an app fetched `/v3/accounts/profile/status` twice, 1.9s apart, with
 * byte-identical responses. Nothing was failing, so nothing drew attention to it — it was simply a
 * wasted round trip on the startup path, and it was only noticed by reading the rows by hand.
 *
 * Two rules decide what counts, and both matter:
 *
 * **Different `callId` is required.** Redirect hops and retry attempts share a `callId` — they are
 * one logical call, already shown as an attempt chain. Without this rule every retry would light up
 * as a duplicate, which is both noise and a false description of what happened.
 *
 * **Headers are not part of the key.** Apps that sign their requests put a fresh nonce, timestamp
 * and signature on every one by design, so a key including headers would never match twice and this
 * would find nothing on exactly the apps it is most useful for.
 *
 * What the key *is*: method, full URL, status, and both byte counts. The byte counts stand in for
 * comparing the payloads themselves, which are not on the row and would cost a fetch per candidate
 * — so this identifies requests that are **indistinguishable at row level**, which is a slightly
 * weaker claim than byte-identical bodies. Two calls that agree on all of that are worth a look;
 * confirming they are truly identical means opening them.
 *
 * Timing uses [NetworkTransaction.mono], never `ts`: `ts` is a wall clock that can step, and the
 * device and host clocks are unrelated. The gap is measured between *consecutive* members, so a
 * steady poll reads as one group rather than fragmenting.
 */
fun duplicateGroups(
    transactions: List<NetworkTransaction>,
    windowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
): List<DuplicateGroup> {
    if (windowMs <= 0 || transactions.size < 2) return emptyList()

    return transactions
        .groupBy { it.duplicateKey() }
        .values
        .flatMap { sameRequest ->
            val ordered = sameRequest.sortedBy { it.mono }
            val runs = mutableListOf<MutableList<NetworkTransaction>>()
            for (txn in ordered) {
                val current = runs.lastOrNull()
                if (current != null && txn.mono - current.last().mono <= windowMs) {
                    current += txn
                } else {
                    runs += mutableListOf(txn)
                }
            }
            runs.mapNotNull { run ->
                val callIds = run.map { it.callId }.distinct()
                if (callIds.size < 2) return@mapNotNull null
                DuplicateGroup(
                    ids = run.map { it.id },
                    spanMs = run.last().mono - run.first().mono,
                    callCount = callIds.size,
                )
            }
        }
        .sortedBy { group -> transactions.first { it.id == group.ids.first() }.mono }
}

/**
 * Each duplicated transaction's id mapped to the group it belongs to, for surfaces that show
 * *how many* and *over how long* rather than only that a row is one.
 *
 * The count worth showing is [DuplicateGroup.callCount] and not `ids.size`: a group whose members
 * include retry attempts has more rows than calls, and "sent 5 times" would be wrong about the
 * thing the reader cares about, which is how many times the app asked.
 */
fun duplicatesById(
    transactions: List<NetworkTransaction>,
    windowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
): Map<String, DuplicateGroup> = buildMap {
    for (group in duplicateGroups(transactions, windowMs)) {
        for (id in group.ids) put(id, group)
    }
}

/** Every id that belongs to some duplicate group, for surfaces that only need to highlight. */
fun duplicateIds(
    transactions: List<NetworkTransaction>,
    windowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
): Set<String> = duplicateGroups(transactions, windowMs).flatMapTo(mutableSetOf()) { it.ids }

/**
 * What makes two rows "the same request".
 *
 * Deliberately excludes headers and `callId`; see [duplicateGroups] for why.
 */
internal fun NetworkTransaction.duplicateKey(): String = buildString {
    append(method).append(' ')
    append(url).append(' ')
    append(status ?: -1).append(' ')
    append(reqBytes).append('/').append(resBytes)
}
