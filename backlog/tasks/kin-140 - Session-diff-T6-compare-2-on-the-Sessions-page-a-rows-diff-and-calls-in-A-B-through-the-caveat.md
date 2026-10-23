---
id: KIN-140
title: >-
  Session diff T6: compare (2) on the Sessions page, a row's diff, and calls in
  A/B through the caveat
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 19:26'
updated_date: '2026-10-23 07:23'
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
- [x] #1 With exactly two sessions ticked, compare (2) opens #/diff?a=<older>&b=<newer>; with any other number it is disabled (diff sessions compare, red with older and newer swapped)
- [x] #2 Every Sessions row, a recording one included, has diff, which opens #/diff?b=<id> (diff row action); a recording row still cannot be ticked, and 1a's deletes disabled, sessions bulk and session presets pass unchanged
- [x] #3 calls in B opens Traffic on B's session with the filter box equal to b.filterTerm and the caveat reading From Diff; back to Diff returns to the diff address; stats caveat shown and stats caveat clears still pass (diff opens calls)
- [x] #4 afterSessionChange re-reads a shown Diff page, so a deleted session reads as the daemon's 404
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Build on T5 (design §9.2, §9.4, §12 T6), criteria #1-#4 as the plan:
1. Sessions page: #sessions-compare, compare (n), enabled at exactly two ticked; opens #/diff?a=<older>&b=<newer> by (startedAt, sessionId). Each row gains diff (.row-diff) opening #/diff?b=<id>, recording rows included; ticking rules unchanged.
2. statsOpenRoute(row, { from, back, sessionId }): switch first, caveat with from/back, navigate, filter; #filter-caveat-from names the page; back returns to the diff address.
3. afterSessionChange calls diffSessionsChanged().
4. Probes: diff sessions compare, diff row action, diff opens calls, diff restored; stats caveat shown/clears re-run.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B (web) with KIN-139, not committed yet.

Built: #sessions-compare (compare (n), enabled at exactly two ticked; opens #/diff?a=<older>&b=<newer>, older by startedAt then id as strings, pushed). Every Sessions row gains diff (.row-diff) opening #/diff?b=<id>, recording rows included; ticking unchanged. statsOpenRoute(row, { from, back, sessionId }): switches first, then the caveat { term, label, sessionId, from, back }, then navigate, then the filter. #filter-caveat-from names the page from PAGES' label; back to <page> navigates to caveat.back. A diff route's detail has calls in A / calls in B (only on a side the route is on). afterSessionChange calls diffSessionsChanged(). session-state-checks.js's afterSessionChange harness gains a diffSessionsChanged stub. DAEMON.md's sentence completed with compare (2) and a row's diff; web-ui.md paragraph gains the Sessions and caveat sentences.

Probe lines (live 317 → 320): diff sessions compare (compare (1) disabled, (2) enabled, (3) disabled, (2) enabled; opens a=base&b=probe), diff row action (#/diff?b=<probe>, A the long session), diff opens calls (calls in B and calls in A on stock: Traffic on that session with its filterTerm, caveat 'From Diff: GET /v3/shop/stock', back to Diff returns to the pair's address). The A side exercises the session switch, beyond the design's B-only line.

Red proofs (each restored and seen green):
- diff sessions compare: older and newer swapped in sessionsToCompare → NO - compare opened #/diff?a=<probe>…, <base> as A due
- diff row action: the row's diff sends a= instead of b= → NO - opened #/diff?a=<probe>, A undefined
- diff opens calls: the caveat's page fixed as Stats → NO - caveat "From Stats: GET /v3/shop/stock…", button "back to Stats" (B and A)
- diff restored: the probe's filter clean-up removed → NO - filter "method:GET host:api.example.invalid path:*/v3/shop/stock"
- Pace: chip marks active (the probe after diff's) guard broken → false, failed: chip marks active.
- stats caveat shown and stats caveat clears pass unchanged ("From Stats: GET /v3/shop/stock…"); deletes disabled, sessions bulk and session presets pass in the same run.

Closed 2026-10-23 by the delivery tracker. PR #185, merge 1b7a45b. QA report: qa/session-diff-pr-b-report.md (run folder of 2026-10-20), verdict close - yes.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug) on 1b7a45b: check-web-static 139/0; live probe 321/0; archive 61/0; Chrome 350 readings, 0 failed on head, 6 failed on base (intended movers: shell <w> page diff fits, polish page targets). compare (2) opens older as A, red D5 (swapped). Scope limits: the diff button on a recording row is not exercised (no recording session in the fixture); AC 4, a deleted session under a shown Diff page, is verified by reading 96-diff.js only. Closed on QA's verdict with that exact scope. Merge 1b7a45b (#185). QA report session-diff-pr-b-report.md.
<!-- SECTION:FINAL_SUMMARY:END -->
