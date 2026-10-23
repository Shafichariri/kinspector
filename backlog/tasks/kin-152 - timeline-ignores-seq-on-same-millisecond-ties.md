---
id: KIN-152
title: timeline() ignores seq on same-millisecond ties
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 08:38'
updated_date: '2026-10-23 09:19'
labels:
  - 'item:timeline-seq-ties'
dependencies: []
ordinal: 152000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 35 (§3 → Bugs found). Design: designs/timeline-seq-ties.md (run of 2026-10-20); decision 2026-10-23-timeline-device-marker-seq (a device marker carrying seq sorts by it within its millisecond). One PR: the model's timeline() and the web timelineEntries mirror, both run by the shared mirror case. The note's three side findings stay out. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:timeline-seq-ties holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Roadmap criteria (item:timeline-seq-ties):
1. A signal and a call in one millisecond sort by seq where both carry it, on the overlay, the web UI, MCP timeline and every export.
2. Two signals in one millisecond do too.
3. Rows without seq keep today's order.
4. The rule has shared mirror cases in mirror-cases/ that both the Kotlin tests and check-web-static.js run, each proven red.
Out of scope: what seq means or when capture assigns it; visits() itself.
Plan (design note timeline-seq-ties.md): L — Timeline.kt orderTiesBySeq after the merge, before newestFirst; TimelineTest + mirror-cases/timeline.json six cases + newest-first; SignalTools description + MCP golden; SignalToolsTest and ExportTest tie cases; schema.md and shared-rules.md prose. W — 45-visits.js timelineEntries + timelineOrderTies inside the visits mirror block only.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in worktree structure-phase-i-6f380a, branch shafic/timeline-seq-ties, uncommitted (the coordinator commits: L = model, mirror cases, daemon, docs; W = 45-visits.js + 70-timeline.js comment).
Rule: Timeline.kt orderTiesBySeq after the merge and before the newestFirst reverse; web timelineOrderTies at the end of timelineEntries, inside the visits mirror block. No public API change: api/ goldens unchanged. MCP golden mcp-tools-list.json moves for the timeline description sentence only.
Red proofs: stable sort alone (5 timeline cases + 1 visits case, both sides); seq ?: Long.MIN_VALUE and ?? -Infinity (signal-without-seq case); marker seq ?: MAX (host-marker case); Timeline.kt reverted -> MirrorCasesTest red and check-web-static green, 45-visits.js reverted -> the reverse; timelineWindow sortedBy mono -> SignalToolsTest red; Timeline.kt reverted -> ExportTest JSON and Markdown halves red each.
Overlay has no test of its own: one call to timeline(), covered by commonTest TimelineTest on every target.
UNRELEASED line for the coordinator: Overlay, web UI, MCP timeline and exports: rows in one millisecond follow capture order (seq); a marker the host posted still comes first.
<!-- SECTION:NOTES:END -->
