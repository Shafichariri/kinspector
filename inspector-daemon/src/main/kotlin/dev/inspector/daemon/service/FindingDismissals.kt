package dev.inspector.daemon.service

import dev.inspector.daemon.archive.DismissalEntry

/**
 * The reply-time step that marks dismissed findings: after the cache, so a dismissal never rescans a
 * body and never moves `ruleVersion`.
 *
 * A dismissal names a finding's rule, route, location and path, and matches only when all four are
 * equal, a null only a null, so a payment finding with no location dismisses the same way a sensitive
 * one does. Nothing that exists on one machine only takes part, so a list moves between machines and
 * outlives retention. A route renamed by a `routeTemplates` edit stops matching and the finding comes
 * back: the safe direction. A dismissed finding is still returned, marked, because silence would read
 * as "nothing found".
 */
object FindingDismissals {

    private data class Key(val ruleId: String, val route: String?, val location: String?, val path: String?)

    /** [findings], in the same order, each one a dismissal matches carrying its reason. */
    fun apply(findings: List<Finding>, dismissals: List<DismissalEntry>): List<Finding> {
        if (dismissals.isEmpty()) return findings
        val byKey = HashMap<Key, DismissalEntry>()
        for (entry in dismissals) byKey.putIfAbsent(Key(entry.ruleId, entry.route, entry.location, entry.path), entry)
        return findings.map { finding ->
            val entry = byKey[Key(finding.ruleId, finding.route, finding.location, finding.path)]
            if (entry == null) finding else finding.copy(dismissed = Dismissal(entry.reason, entry.at))
        }
    }
}
