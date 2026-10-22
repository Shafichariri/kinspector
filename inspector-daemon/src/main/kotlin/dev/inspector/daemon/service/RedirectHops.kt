package dev.inspector.daemon.service

import dev.inspector.model.NetworkTransaction

/**
 * Which later attempts of a call are redirect hops and which are retries.
 *
 * The schema records neither, so it is inferred: an attempt after a 3xx attempt of the same call is a
 * hop, and any other attempt after the first is a retry, a missing predecessor included. Every row of
 * the session goes through [add], filtered or not, because `status:200` would otherwise drop the 302
 * before a hop and turn the redirect into a retry. Route stats and the payment rules both ask here,
 * so "retry" means one thing across the stats table and the findings.
 *
 * Answers are as of the rows added so far: ask after the fold, not as rows arrive, so a fold that
 * met an attempt before the one it follows still classifies it right.
 */
class RedirectHops {
    /** `callId#attempt` of every 3xx row. */
    private val hops = HashSet<String>()

    fun add(txn: NetworkTransaction) {
        val status = txn.status
        if (status != null && status / 100 == 3) hops += hop(txn.callId, txn.attempt)
    }

    /** True for an attempt after the first whose predecessor in the same call was a 3xx. */
    fun isRedirect(callId: String, attempt: Int): Boolean = attempt > 1 && hop(callId, attempt - 1) in hops

    /** True for an attempt after the first whose predecessor was not a 3xx, or is not in the archive. */
    fun isRetry(callId: String, attempt: Int): Boolean = attempt > 1 && !isRedirect(callId, attempt)

    fun isRedirect(txn: NetworkTransaction): Boolean = isRedirect(txn.callId, txn.attempt)

    fun isRetry(txn: NetworkTransaction): Boolean = isRetry(txn.callId, txn.attempt)

    private companion object {
        fun hop(callId: String, attempt: Int) = "$callId#$attempt"
    }
}
