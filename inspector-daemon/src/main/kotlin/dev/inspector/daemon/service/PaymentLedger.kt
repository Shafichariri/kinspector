package dev.inspector.daemon.service

import dev.inspector.daemon.archive.PaymentSettings
import dev.inspector.daemon.archive.RouteKey
import dev.inspector.model.NetworkTransaction

/** Whether a row carried an idempotency key, as the payment rules read it. Never the value. */
internal sealed interface KeyState {
    /** No configured header with a non-blank value. */
    data object Absent : KeyState

    /** Present, but `redacted[]` says capture replaced the value, so equal placeholders prove nothing. */
    data class Redacted(val header: String) : KeyState

    /** [digest] is SHA-256 hex of the trimmed value; [masked] its `•••• abcd`. [header] lowercased. */
    data class Present(val header: String, val digest: String, val masked: String) : KeyState
}

/** A request body as a rule may compare it: whole bytes, by digest, or why not. */
internal sealed interface BodyKnowledge {
    data class Known(val digest: String, val size: Int) : BodyKnowledge

    /** [reason] is a [BodyState] word, or `redacted` for a body with a redacted path. */
    data class Unknown(val reason: String) : BodyKnowledge
}

/** One mutation attempt, as compact as the rules allow: no headers, no body, no message. */
internal class MutationRow(
    val id: String,
    val callId: String,
    val attempt: Int,
    val mono: Long,
    val seq: Long?,
    val ms: Long?,
    val status: Int?,
    /** The exception's simple class name, never its message. */
    val errorClass: String?,
    val failed: Boolean,
    val route: RouteKey,
    val key: KeyState,
    val paymentPathMatch: String?,
    val reqState: BodyState,
    /** True when `redacted[]` names a request-body path: equal after redaction is not equal. */
    val reqRedacted: Boolean,
) {
    val row: FindingRow get() = FindingRow(id, callId, mono, seq)
}

/**
 * The payment source's fold: every row feeds [hops], and each mutation row that is not ignored
 * becomes one [MutationRow]. Nothing else is kept. Request bodies are hashed later, only for the rows a
 * rule needs, and memoised by id: a body file never changes once its row is written.
 */
internal class PaymentLedger(
    private val settings: PaymentSettings,
    private val session: FindingSession,
) {
    val hops = RedirectHops()
    val rows = ArrayList<MutationRow>()

    /** Calls with a mutation row that `ignorePaths` took out of every rule. */
    val ignoredCalls = HashSet<String>()

    private val digests = HashMap<String, BodyKnowledge>()

    fun add(txn: NetworkTransaction) {
        hops.add(txn)
        val route = session.routeOf(txn)
        if (!route.mutation) return
        if (settings.ignored(txn)) {
            ignoredCalls += txn.callId
            return
        }
        rows += MutationRow(
            id = txn.id,
            callId = txn.callId,
            attempt = txn.attempt,
            mono = txn.mono,
            seq = txn.seq,
            ms = txn.ms,
            status = txn.status,
            errorClass = errorClassOf(txn.error),
            failed = txn.status == null && txn.error != null,
            route = route,
            key = keyOf(txn),
            paymentPathMatch = settings.paymentPathMatch(txn),
            reqState = BodyEvidence.state(txn, BodySide.REQ),
            reqRedacted = JsonPaths.redactedPaths(txn, BodySide.REQ).isNotEmpty(),
        )
    }

    /** The request body of [row] for comparison, read and hashed at most once. */
    fun body(row: MutationRow): BodyKnowledge = digests.getOrPut(row.id) {
        when {
            row.reqRedacted -> BodyKnowledge.Unknown(REDACTED)
            else -> {
                val bytes = BodyEvidence.read(row.reqState) { session.readBody(row.id, BodySide.REQ.key) }
                when {
                    bytes != null -> BodyKnowledge.Known(FindingSession.sha256Hex(bytes), bytes.size)
                    // A whole state with no file on disk is a ref whose file is gone.
                    row.reqState == BodyState.COMPLETE || row.reqState == BodyState.NOT_JSON ->
                        BodyKnowledge.Unknown(BodyState.MISSING.key)
                    else -> BodyKnowledge.Unknown(row.reqState.key)
                }
            }
        }
    }

    /** True when the row's own fields already say the body cannot be compared, without reading it. */
    fun bodyUnknownByRow(row: MutationRow): Boolean =
        row.reqRedacted || row.reqState !in WHOLE_STATES

    private fun keyOf(txn: NetworkTransaction): KeyState {
        for ((name, values) in txn.reqHeaders) {
            if (settings.idempotencyHeaders.none { it.equals(name, ignoreCase = true) }) continue
            val value = values.firstOrNull()?.trim()
            if (value.isNullOrEmpty()) continue
            val header = name.lowercase()
            if ("header:$header" in txn.redacted) return KeyState.Redacted(header)
            return KeyState.Present(header, FindingSession.sha256Hex(value), FindingMask.tail(value, 4))
        }
        return KeyState.Absent
    }

    companion object {
        const val REDACTED = "redacted"

        private val WHOLE_STATES = setOf(BodyState.COMPLETE, BodyState.NOT_JSON, BodyState.EMPTY)

        private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_$.]{0,99}")

        /**
         * The simple class name `describe()` in `InspectorPlugin.kt` writes before the colon. The rest
         * is a message, which can carry a URL with its query, so it is never kept; anything before the
         * colon that is not a class name is dropped too.
         */
        fun errorClassOf(error: String?): String? {
            val name = error?.substringBefore(':')?.trim() ?: return null
            return name.takeIf { CLASS_NAME.matches(it) }?.substringAfterLast('.')
        }
    }
}
