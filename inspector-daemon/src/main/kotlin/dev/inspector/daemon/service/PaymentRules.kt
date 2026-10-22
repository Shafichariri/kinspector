package dev.inspector.daemon.service

import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingRuleInfo
import dev.inspector.daemon.archive.PaymentSettings
import dev.inspector.daemon.archive.RouteKey
import dev.inspector.model.NetworkTransaction
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Payment safety: six rules over POST, PUT, PATCH and DELETE calls, about idempotency keys, retries
 * and resends. Bodies are compared byte for byte by digest and never shown; a key is held as a digest
 * and shown as its masked tail.
 */
class PaymentSource(private val settings: PaymentSettings) : FindingSource {
    override val category: String = FindingRuleCatalog.PAYMENT
    override val version: String = "payment-1"
    override val rules: List<FindingRuleInfo> = FindingRuleCatalog.PAYMENT_RULES

    override fun accumulator(session: FindingSession): FindingAccumulator = PaymentAccumulator(settings, session)

    override fun note(ruleId: String): String? =
        if (ruleId == FindingRuleCatalog.KEY_MISSING_PAYMENT_PATH.id && settings.paymentPaths.isEmpty()) {
            "no payment paths configured"
        } else {
            null
        }

    companion object {
        /**
         * Failures that happen before a byte reaches a server, by simple class name. Going offline and
         * trying again is the commonest mobile case, and is not an ambiguous resubmit. iOS Darwin
         * transport errors were not checked against this list on a device; they lean toward a false
         * positive, not a miss.
         */
        val NEVER_SENT: Set<String> = setOf(
            "UnknownHostException",
            "UnresolvedAddressException",
            "ConnectException",
            "NoRouteToHostException",
            "ConnectTimeoutException",
        )
    }
}

/** One session's payment fold, and the six rules run over it on each collect. */
internal class PaymentAccumulator(
    private val settings: PaymentSettings,
    private val session: FindingSession,
) : FindingAccumulator {
    private val ledger = PaymentLedger(settings, session)

    /** Coverage counts as of the last [findings]. */
    private var coverage: JsonObject? = null

    override fun add(row: NetworkTransaction) = ledger.add(row)

    override fun coverage(): JsonObject? = coverage ?: run { findings(); coverage }

    override fun findings(): List<Finding> {
        val calls = ledger.rows.groupBy { it.callId }.values.map { Call(it.sortedWith(ATTEMPT_ORDER)) }
        val tally = Tally()
        val found = buildList {
            addAll(duplicateMutations(calls, tally))
            addAll(keyReusedBodyChanged(tally))
            addAll(retries(calls))
            addAll(ambiguousResubmits(calls))
            addAll(keyMissingOnPaymentPath(calls))
        }
        coverage = tally.coverage(calls.size, ledger.ignoredCalls.count { id -> calls.none { it.callId == id } })
        return found
    }

    // --- R1: Duplicate mutation -----------------------------------------------------------------

    private fun duplicateMutations(calls: List<Call>, tally: Tally): List<Finding> {
        val rule = FindingRuleCatalog.DUPLICATE_MUTATION
        val window = settings.duplicateWindowMs
        // Calls with no key on any row; a body is read only for one with another such call on its route
        // within the window, and one that cannot be read is counted, not compared.
        val keyless = calls.filter { call -> call.rows.all { it.key == KeyState.Absent } }
        val found = ArrayList<Finding>()
        for ((route, onRoute) in keyless.groupBy { it.route }) {
            val sorted = onRoute.sortedBy { it.first.mono }
            val known = ArrayList<Pair<Call, BodyKnowledge.Known>>()
            for ((i, call) in sorted.withIndex()) {
                val near = (i > 0 && call.first.mono - sorted[i - 1].first.mono <= window) ||
                    (i < sorted.size - 1 && sorted[i + 1].first.mono - call.first.mono <= window)
                if (!near) continue
                when (val body = tally.compare(ledger, call.first)) {
                    is BodyKnowledge.Known -> known += call to body
                    is BodyKnowledge.Unknown -> Unit
                }
            }
            if (!session.enabled(rule.id)) continue
            for (same in known.groupBy { it.second.digest }.values) {
                // Chained as duplicateGroups chains: each gap to the previous member within the window.
                var run = ArrayList<Pair<Call, BodyKnowledge.Known>>()
                for (member in same) {
                    if (run.isNotEmpty() && member.first.first.mono - run.last().first.first.mono > window) {
                        if (run.size >= 2) found += duplicate(rule, route, run)
                        run = ArrayList()
                    }
                    run += member
                }
                if (run.size >= 2) found += duplicate(rule, route, run)
            }
        }
        return found
    }

    private fun duplicate(rule: FindingRuleInfo, route: RouteKey, run: List<Pair<Call, BodyKnowledge.Known>>): Finding {
        val start = run.first().first.first
        val span = run.last().first.first.mono - start.mono
        val evidence = ArrayList<Evidence>()
        evidence += Evidence(start.id, location = LOCATION_HEADER, note = "no ${headerNames()} header on any call")
        run.forEachIndexed { i, (call, body) ->
            val first = call.first
            val head = "call ${i + 1}: attempt ${first.attempt}, ${outcome(call.last)}, +${first.mono - start.mono} ms"
            evidence += if (i == 0) {
                Evidence(first.id, note = head)
            } else {
                Evidence(first.id, location = LOCATION_BODY, note = "$head; same request body as call 1, ${body.size} B")
            }
        }
        return session.finding(
            rule, session.severity(rule.id), groupKey = run.first().first.callId, route = route,
            rows = run.map { it.first.first.row },
            message = "${session.routeLabel(route)} was sent ${run.size} times in ${seconds(span)} as separate calls " +
                "with the same request body and no idempotency key.",
            evidence = evidence, location = LOCATION_BODY,
        )
    }

    // --- R2: Key reused, body changed -----------------------------------------------------------

    private fun keyReusedBodyChanged(tally: Tally): List<Finding> {
        val rule = FindingRuleCatalog.KEY_REUSED_BODY_CHANGED
        val found = ArrayList<Finding>()
        val byKey = ledger.rows.filter { it.key is KeyState.Present }.groupBy { (it.key as KeyState.Present).digest }
        for (group in byKey.values) {
            if (group.size < 2) continue
            val ordered = group.sortedWith(ROW_TIMELINE)
            val bodies = ordered.map { it to tally.compare(ledger, it) }
            val letters = LinkedHashMap<String, Char>()
            bodies.forEach { (_, body) -> if (body is BodyKnowledge.Known) letters.getOrPut(body.digest) { 'A' + letters.size } }
            if (letters.size < 2 || !session.enabled(rule.id)) continue
            val first = ordered.first()
            val key = first.key as KeyState.Present
            val routes = ordered.map { it.route }.distinct()
            val on = if (routes.size > 1) " on " + joinAnd(routes.map(session::routeLabel)) else ""
            val callCount = ordered.map { it.callId }.distinct().size
            val evidence = ArrayList<Evidence>()
            evidence += Evidence(first.id, location = LOCATION_HEADER, path = key.header, masked = key.masked)
            bodies.forEach { (row, body) ->
                evidence += Evidence(row.id, note = when (body) {
                    is BodyKnowledge.Known -> "body ${letters.getValue(body.digest)}, ${body.size} B"
                    is BodyKnowledge.Unknown -> "body unknown: ${body.reason}"
                })
            }
            found += session.finding(
                rule, session.severity(rule.id), groupKey = first.id, route = routes.singleOrNull(),
                rows = ordered.map { it.row },
                message = "One ${displayName(key.header)} was sent with ${letters.size} different request bodies " +
                    "across $callCount ${plural(callCount, "call")}$on.",
                evidence = evidence, location = LOCATION_HEADER, path = headerPath(),
            )
        }
        return found
    }

    // --- R3 and R6: Automatic retry without a key, Retry with a key ------------------------------

    private fun retries(calls: List<Call>): List<Finding> {
        val without = FindingRuleCatalog.RETRY_WITHOUT_KEY
        val with = FindingRuleCatalog.RETRY_WITH_KEY
        val found = ArrayList<Finding>()
        for (call in calls) {
            val retries = call.rows.filter { ledger.hops.isRetry(it.callId, it.attempt) }
            if (retries.isEmpty()) continue
            var unsafe = false
            val notes = HashMap<String, Evidence>()
            for (retry in retries) {
                val before = call.rows.lastOrNull { it.attempt < retry.attempt }
                val changed = before != null && retry.key is KeyState.Present && before.key is KeyState.Present &&
                    (retry.key as KeyState.Present).digest != (before.key as KeyState.Present).digest
                if (retry.key == KeyState.Absent || before?.key == KeyState.Absent || changed) unsafe = true
                if (changed) notes[retry.id] = attemptLine(retry, "key changed from attempt ${before!!.attempt}")
            }
            val rule = if (unsafe) without else with
            if (!session.enabled(rule.id)) continue
            val attempts = call.rows.maxOf { it.attempt }
            val evidence = ArrayList<Evidence>()
            if (call.rows.none { it.attempt == 1 }) {
                evidence += Evidence(call.first.id, note = "attempt 1 is not in the archive")
            }
            for (row in call.rows) evidence += notes[row.id] ?: attemptLine(row, null)
            val message = if (unsafe) {
                "${session.routeLabel(call.route)} was retried automatically ($attempts attempts) without an " +
                    "idempotency key, so the server cannot tell the retry from a new request."
            } else {
                val header = call.rows.firstNotNullOfOrNull { (it.key as? KeyState.Present)?.header }
                    ?: call.rows.firstNotNullOfOrNull { (it.key as? KeyState.Redacted)?.header }
                "${session.routeLabel(call.route)} was retried automatically ($attempts attempts) with the same " +
                    "${displayName(header)} on every attempt."
            }
            found += session.finding(
                rule, session.severity(rule.id), groupKey = call.callId, route = call.route,
                rows = call.rows.map { it.row }, message = message, evidence = evidence,
                location = LOCATION_HEADER, path = headerPath(),
            )
        }
        return found
    }

    /** `attempt 2: 503; no key`, with the masked key when there is one. */
    private fun attemptLine(row: MutationRow, keyNote: String?): Evidence {
        val key = row.key
        val note = keyNote ?: when (key) {
            KeyState.Absent -> "no key"
            is KeyState.Redacted -> "key redacted at capture; cannot confirm it was the same"
            is KeyState.Present -> "key"
        }
        return Evidence(
            row.id,
            location = LOCATION_HEADER,
            path = (key as? KeyState.Present)?.header ?: (key as? KeyState.Redacted)?.header,
            masked = (key as? KeyState.Present)?.masked,
            note = "attempt ${row.attempt}: ${outcome(row)}; $note",
        )
    }

    // --- R4: Ambiguous resubmit -----------------------------------------------------------------

    private fun ambiguousResubmits(calls: List<Call>): List<Finding> {
        val rule = FindingRuleCatalog.AMBIGUOUS_RESUBMIT
        if (!session.enabled(rule.id)) return emptyList()
        val found = ArrayList<Finding>()
        val byRoute = calls.groupBy { it.route }
        for (failed in calls) {
            val last = failed.last
            if (!last.failed || last.errorClass in PaymentSource.NEVER_SENT) continue
            val end = last.mono + (last.ms ?: 0)
            val resend = byRoute.getValue(failed.route)
                .filter { it.callId != failed.callId && it.first.mono in end..(end + settings.resubmitWindowMs) }
                .minWithOrNull(compareBy<Call> { it.first.mono }.thenComparator { a, b -> ROW_TIMELINE.compare(a.first, b.first) })
                ?: continue
            val next = resend.first
            val (lastKey, nextKey) = last.key to next.key
            if (lastKey is KeyState.Present && nextKey is KeyState.Present && lastKey.digest == nextKey.digest) continue
            val failure = last.errorClass ?: "a transport failure"
            val gap = seconds(next.mono - end)
            val redacted = lastKey is KeyState.Redacted || nextKey is KeyState.Redacted
            val (before, after) = ledger.body(last) to ledger.body(next)
            val bodyNote = when {
                before is BodyKnowledge.Unknown -> "body unknown: ${before.reason}"
                after is BodyKnowledge.Unknown -> "body unknown: ${after.reason}"
                (before as BodyKnowledge.Known).digest == (after as BodyKnowledge.Known).digest -> "the same request body"
                else -> "a different request body"
            }
            val evidence = listOf(
                Evidence(last.id, note = "transport failure: $failure"),
                Evidence(next.id, note = "sent again $gap after the failure"),
                Evidence(next.id, location = LOCATION_BODY, note = bodyNote),
                keyLine(last, "the failed call"),
                keyLine(next, "the new call"),
            )
            val tail = if (redacted) {
                "; the key was redacted at capture, so whether it was reused is unknown."
            } else {
                " without the same idempotency key."
            }
            found += session.finding(
                rule, session.severity(rule.id), groupKey = failed.callId, route = failed.route,
                rows = listOf(last.row, next.row),
                message = "${session.routeLabel(failed.route)} ended in $failure, so the server may have processed it, " +
                    "and was sent again $gap later as a new call$tail",
                evidence = evidence,
            )
        }
        return found
    }

    private fun keyLine(row: MutationRow, which: String): Evidence = when (val key = row.key) {
        KeyState.Absent -> Evidence(row.id, location = LOCATION_HEADER, note = "$which: no key")
        is KeyState.Redacted -> Evidence(row.id, location = LOCATION_HEADER, path = key.header, note = "$which: key redacted at capture")
        is KeyState.Present -> Evidence(row.id, location = LOCATION_HEADER, path = key.header, masked = key.masked, note = "$which: key")
    }

    // --- R5: Key missing on a payment path ------------------------------------------------------

    private fun keyMissingOnPaymentPath(calls: List<Call>): List<Finding> {
        val rule = FindingRuleCatalog.KEY_MISSING_PAYMENT_PATH
        if (!session.enabled(rule.id)) return emptyList()
        return calls.mapNotNull { call ->
            val first = call.first
            val pattern = first.paymentPathMatch ?: return@mapNotNull null
            if (first.key != KeyState.Absent) return@mapNotNull null
            session.finding(
                rule, session.severity(rule.id), groupKey = call.callId, route = call.route,
                rows = listOf(first.row),
                message = "${session.routeLabel(call.route)} matches the payment path pattern \"$pattern\" and " +
                    "carried no idempotency key.",
                evidence = listOf(
                    Evidence(first.id, note = "matched findings.payment.paymentPaths \"$pattern\""),
                    Evidence(first.id, location = LOCATION_HEADER, note = "no ${headerNames()} header"),
                ),
                location = LOCATION_HEADER, path = headerPath(),
            )
        }
    }

    // --- shared ---------------------------------------------------------------------------------

    /** One call's mutation rows, by attempt. */
    private class Call(val rows: List<MutationRow>) {
        val first: MutationRow get() = rows.first()
        val last: MutationRow get() = rows.last()
        val callId: String get() = first.callId
        val route: RouteKey get() = first.route
    }

    /** What Duplicate mutation and Key reused could and could not compare, for `coverage.payment`. */
    private class Tally {
        private val compared = HashSet<String>()
        private val unknown = LinkedHashMap<String, String>()

        fun compare(ledger: PaymentLedger, row: MutationRow): BodyKnowledge = ledger.body(row).also {
            when (it) {
                is BodyKnowledge.Known -> compared += row.id
                is BodyKnowledge.Unknown -> unknown[row.id] = it.reason
            }
        }

        fun coverage(mutationCalls: Int, ignoredCalls: Int): JsonObject {
            val reasons = listOf(BodyState.TRUNCATED.key, BodyState.OMITTED.key, BodyState.MISSING.key, PaymentLedger.REDACTED)
            val counts = reasons.associateWith { reason -> unknown.values.count { it == reason } }
            val unknownTotal = unknown.size
            val line = buildString {
                append("$mutationCalls mutation ${plural(mutationCalls, "call")} looked at")
                if (ignoredCalls > 0) append(", $ignoredCalls ignored by ignorePaths")
                append("; ${compared.size} request ${if (compared.size == 1) "body" else "bodies"} compared")
                if (unknownTotal > 0) {
                    append(", $unknownTotal unknown (")
                    append(counts.filterValues { it > 0 }.entries.joinToString(", ") { (reason, n) -> "$n $reason" })
                    append(")")
                }
                append(".")
            }
            return buildJsonObject {
                put("line", line)
                put("mutationCalls", mutationCalls)
                put("ignoredCalls", ignoredCalls)
                put("bodiesCompared", compared.size)
                putJsonObject("bodiesUnknown") { counts.forEach { (reason, n) -> put(reason, n) } }
            }
        }
    }

    private fun outcome(row: MutationRow): String = when {
        row.status != null -> row.status.toString()
        row.failed -> "transport failure (${row.errorClass ?: "unknown class"})"
        else -> "no response yet"
    }

    /** `Idempotency-Key or X-Idempotency-Key`, as configured. */
    private fun headerNames(): String = joinOr(settings.idempotencyHeaders)

    /** The configured spelling of [header], which is stored lowercased. */
    private fun displayName(header: String?): String =
        settings.idempotencyHeaders.firstOrNull { it.equals(header, ignoreCase = true) } ?: settings.idempotencyHeaders.first()

    private fun headerPath(): String = settings.idempotencyHeaders.first().lowercase()

    private companion object {
        const val LOCATION_HEADER = "reqHeader"
        const val LOCATION_BODY = "reqBody"

        val ATTEMPT_ORDER: Comparator<MutationRow> = compareBy<MutationRow> { it.attempt }.thenBy { it.mono }
        val ROW_TIMELINE: Comparator<MutationRow> = Comparator { a, b -> FindingSession.TIMELINE.compare(a.row, b.row) }

        fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

        fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

        fun joinOr(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
        }

        fun joinAnd(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
