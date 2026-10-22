---
id: KIN-140
title: >-
  Session diff T6: compare (2) on the Sessions page, a row's diff, and calls in
  A/B through the caveat
status: To Do
assignee: []
created_date: '2026-10-22 19:26'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-139
  - KIN-67
modified_files:
  - inspector-daemon/src/main/resources/web/js/90-sessions.js
  - inspector-daemon/src/main/resources/web/js/95-stats.js
  - inspector-daemon/src/main/resources/web/js/96-diff.js
  - inspector-daemon/src/main/resources/web/index.html
  - scripts/lib/diff-probes.js
type: feature
ordinal: 140000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR B (lane W), after T5. Design: session-diff note §9.2, §9.4 and §12 T6. A recording session still cannot be ticked (1a's rule is kept), so every row also has diff. The Stats caveat is generalised by one field, from, through one statsOpenRoute(row, { from, back, sessionId }).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With exactly two sessions ticked, compare (2) opens #/diff?a=<older>&b=<newer>; with any other number it is disabled (diff sessions compare, red with older and newer swapped)
- [ ] #2 Every Sessions row, a recording one included, has diff, which opens #/diff?b=<id> (diff row action); a recording row still cannot be ticked, and 1a's deletes disabled, sessions bulk and session presets pass unchanged
- [ ] #3 calls in B opens Traffic on B's session with the filter box equal to b.filterTerm and the caveat reading From Diff; back to Diff returns to the diff address; stats caveat shown and stats caveat clears still pass (diff opens calls)
- [ ] #4 afterSessionChange re-reads a shown Diff page, so a deleted session reads as the daemon's 404
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
