package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.TimelineEntry
import dev.inspector.model.InspectorJsonPretty
import kotlinx.serialization.json.JsonObject

/**
 * One call, with the joins an agent would otherwise do by hand.
 *
 * Everything here is derivable from the existing tools, and that is the problem: it takes
 * `get_transaction`, two windowed `timeline` calls, a scan for the shared `callId`, and
 * interval arithmetic nothing exposes at all. Four calls and a page of reasoning to answer
 * "why did this one fail", every time.
 *
 * Traffic and signals are recorded by different mechanisms that share only the device clock,
 * so this is the only place the two are put together.
 */
internal fun TransactionTools.explain(args: JsonObject): ToolOutcome {
    val dir = resolve(args) ?: return noSuchSession(args)
    val id = args.string("id") ?: return ToolOutcome.Failed("'id' is required")
    val window = args.int("windowMs", default = 5_000, min = 0, max = 60_000).toLong()

    val txn = repository.readTransaction(dir, id)
        ?: return ToolOutcome.Failed(explainNotFound(dir, id))

    val signals = repository.readSignals(dir)
    val transactions = repository.readTransactions(dir)
    val notes = mutableListOf<String>()

    // The screen showing when the call *started*. A screen signal is a point observation —
    // "we arrived here" — so the one in force is the latest arrival at or before that moment.
    val arrival = signals
        .filter { it.tag.equals("screen", ignoreCase = true) && it.mono <= txn.mono }
        .maxByOrNull { it.mono }
    val screen = arrival?.let {
        ScreenAt(name = it.name, arrivedMsBefore = txn.mono - it.mono, signalId = it.id)
    }
    if (screen == null) {
        notes += if (signals.none { it.tag.equals("screen", ignoreCase = true) }) {
            "no screen signals in this session, so there is no screen context to give"
        } else {
            "this call happened before the first screen signal, so the screen is not known"
        }
    }

    // Attempts share a callId. A retry read as the only try is a wrong answer, not a missing
    // one, so the whole chain is always reported when there is more than one.
    val chain = transactions.filter { it.callId == txn.callId }.sortedBy { it.attempt }
    val attempts = if (chain.size > 1) chain.map { TimelineEntry.of(it) } else emptyList()
    if (chain.size > 1) {
        notes += "this is attempt ${txn.attempt} of ${chain.size} for one logical call"
    }

    // Overlap, which nothing else can answer: a call is an interval, and every other tool
    // treats it as the instant it started.
    val txnEnd = txn.mono + (txn.ms ?: 0)
    val concurrent = transactions
        .filter { it.id != txn.id && it.mono <= txnEnd && it.mono + (it.ms ?: 0) >= txn.mono }
        .sortedBy { it.mono }
        .map { TimelineEntry.of(it) }
    if (concurrent.isNotEmpty()) {
        notes += "${concurrent.size} other call(s) were in flight while this one ran"
    }

    val sameEndpoint = transactions.filter { it.method == txn.method && it.path == txn.path }
    val repeats = Repeats(
        total = sameEndpoint.size,
        monos = sameEndpoint.map { it.mono }.sorted().take(50),
    )

    val context = repository.timelineWindow(
        sessionDir = dir,
        filterText = "",
        since = txn.mono - window,
        until = txn.mono + window,
    )
    // Unclamped, unlike the `timeline` tool. Its clamp keeps the oldest 2000, so with 2000 entries
    // before the call a clamp drops the call and everything after it — `after` comes back
    // empty, which reads as "nothing followed" on exactly the busiest windows.
    val (before, after) = splitAround(context, txn.id)
        ?: return ToolOutcome.Failed(
            "call ${txn.id} was not found in its own context window; the session changed " +
                "while it was read — retry"
        )

    if (txn.redacted.isNotEmpty()) {
        // Removed, not absent — otherwise the reader concludes no credentials were sent.
        notes += "redacted at capture: ${txn.redacted.joinToString(", ")}"
    }
    for (side in listOf("req", "res")) {
        bodyNote(txn, side)?.let { notes += it }
    }
    notes += "headers are not inlined here; get_transaction('${txn.id}') returns them"

    return ToolOutcome.Ok(
        InspectorJsonPretty.encodeToString(
            Explanation.serializer(),
            Explanation(
                call = CallLine(
                    id = txn.id,
                    ts = txn.ts,
                    mono = txn.mono,
                    method = txn.method,
                    url = "${txn.scheme}://${txn.host}${txn.path}",
                    status = txn.status,
                    error = txn.error,
                    ms = txn.ms,
                    reqBytes = txn.reqBytes,
                    resBytes = txn.resBytes,
                    attempt = txn.attempt,
                    callId = txn.callId,
                ),
                screen = screen,
                attempts = attempts,
                concurrent = concurrent,
                repeats = repeats,
                before = before,
                after = after,
                notes = notes,
            ),
        )
    )
}

/**
 * Why an id did not resolve.
 *
 * Signal ids and transaction ids come from the same generator and look identical, so "no
 * transaction with that id" is the wrong answer roughly as often as it is the right one.
 */
private fun TransactionTools.explainNotFound(dir: java.nio.file.Path, id: String): String =
    if (repository.readSignal(dir, id) != null) {
        "'$id' is a signal, not a transaction. Read it with get_signal; explain joins " +
            "context around a call."
    } else {
        "no transaction '$id' in this session"
    }

/**
 * Splits [context] around the call [txnId] into what led to it and what followed, each capped to
 * the entries nearest the call.
 *
 * Split rather than one list: "what led to this" and "what it caused" are different questions, and
 * a single window makes the reader do the splitting. Split by **position** in the shared order,
 * never by `mono`: a marker dropped at the call's own millisecond sits before the call there, as
 * both UIs draw it, and a `mono < call` test would report it as something the call caused.
 *
 * Null when the call is not in [context]. That is reachable — retention or a delete can remove the
 * session between reading the call and reading its window — and two empty lists would read as
 * "nothing happened either side", a confident answer about a session that was not there.
 */
internal fun splitAround(
    context: List<TimelineEntry>,
    txnId: String,
): Pair<List<TimelineEntry>, List<TimelineEntry>>? {
    val at = context.indexOfFirst { it.kind == "txn" && it.id == txnId }
    if (at < 0) return null
    return context.subList(0, at).takeLast(EXPLAIN_CONTEXT_CAP) to
        context.subList(at + 1, context.size).take(EXPLAIN_CONTEXT_CAP)
}

/**
 * How many context lines either side of the call `explain` returns.
 *
 * A busy five seconds can hold hundreds of state observations. The point of this tool is that its
 * answer fits in a reply; an unbounded window would defeat that on exactly the sessions where the
 * question is hardest.
 */
private const val EXPLAIN_CONTEXT_CAP = 25

/**
 * The call itself, compactly.
 *
 * Deliberately not the whole [NetworkTransaction]. Inlining it put every header in the reply —
 * 3 KB of a 11 KB answer on a real call — which is both redundant with `get_transaction` and a
 * direct contradiction of why this tool exists. What is here is what you read to decide whether
 * the headers are worth a second call.
 */
@kotlinx.serialization.Serializable
data class CallLine(
    val id: String,
    val ts: String,
    val mono: Long,
    val method: String,
    val url: String,
    val status: Int? = null,
    val error: String? = null,
    val ms: Long? = null,
    val reqBytes: Long = 0,
    val resBytes: Long = 0,
    val attempt: Int = 1,
    val callId: String,
)

/** The screen in force when a call started, and how long it had been. */
@kotlinx.serialization.Serializable
data class ScreenAt(
    val name: String,
    val arrivedMsBefore: Long,
    val signalId: String,
)

/** How often this session hit the same method and path. `total` is at least 1 — this call. */
@kotlinx.serialization.Serializable
data class Repeats(
    val total: Int,
    /** Device `mono` of each, oldest first, capped. */
    val monos: List<Long>,
)

/**
 * One call with its context joined — the answer to "why", where the other tools answer "what".
 *
 * Every field is derivable from the existing tools; none of them is cheap. `concurrent` is not
 * derivable at all: a call is an interval and every other tool treats it as the instant it began.
 */
@kotlinx.serialization.Serializable
data class Explanation(
    val call: CallLine,
    /**
     * Null only when nothing is known. Whenever it is, [notes] says why — an agent cannot tell
     * an absent field from an unsupported one, and guessing wrong either way is a wrong answer.
     */
    val screen: ScreenAt? = null,
    /*
     * None of the fields below carries a default, deliberately.
     *
     * The shared Json sets `encodeDefaults = false`, which is right for `index.jsonl` where every
     * byte is a token an agent spends. It is wrong here: an empty `concurrent` means "nothing was
     * in flight alongside this", which is a finding, and omitting it leaves an agent unable to
     * tell that from "this daemon does not report overlap". The archive already lost a count this
     * way once, when a zero `txnCount` vanished and the UI rendered "undefined calls".
     */
    /** Every attempt of this logical call. Empty when it was not retried. */
    val attempts: List<TimelineEntry>,
    /** Calls whose own window overlapped this one's. Empty means this one ran alone. */
    val concurrent: List<TimelineEntry>,
    val repeats: Repeats,
    val before: List<TimelineEntry>,
    val after: List<TimelineEntry>,
    /** Things the fields above state only implicitly, and one of them not at all. */
    val notes: List<String>,
)
