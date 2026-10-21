---
id: KIN-77
title: >-
  The Stats page: table, sort, row to Traffic with the caveat, live refresh,
  export mode
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 15:42'
labels:
  - 'item:endpoint-latency-stats'
dependencies:
  - KIN-75
  - KIN-76
  - KIN-66
  - KIN-70
  - KIN-82
  - KIN-85
ordinal: 77000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:endpoint-latency-stats (run of 2026-10-20), §13. PR B, after 1b. Also depends on 1b's one-row toolbar and count-words tickets (not yet filed when this was created).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 PAGES row stats and rail link #/stats; route parse holds #/stats; route pages and rail is navigation pass
- [ ] #2 One row per route with the §6.6 columns; sort headers send sort=; a session switch reloads it
- [ ] #3 A row opens Traffic with the row's filterTerm and the caveat; caveat gone after an edit or switch
- [ ] #4 Live: at most 2 stats requests per burst, never two in flight, none while paused or hidden
- [ ] #5 Export mode draws from the embedded report with no network attempt
- [ ] #6 No sideways scroll on #/stats at 1440/1000/600/400
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B built (uncommitted, branch shafic/endpoint-latency-stats-b), ready for QA after review.

Built: web/js/95-stats.js and web/css/85-stats.css (new, both manifests); PAGES row stats (live: false, enter: () => statsEnter()) and applyRoute calling enter; the stats rail link, #page-stats and #filter-caveat (after #filter-error, outside .toolbar-row) in index.html; archiveSource answers /stats from archive.routeStats (filter 400, sort other than attempts 400, limit cut as the daemon cuts); oweStats() in ws.onmessage's txn and marker branches after the liveTail check; statsSessionChanged() in switchSession, afterSessionChange and onopen's wasAway branch; statsFilterChanged() where applyFilter's timer writes state.filter; statsReload() on resume and after init's first load.

Checks and what holds each:
- static stats refresh burst / one flight / paused / hidden / stale dropped (scripts/lib/stats-refresh-checks.js), each red with its check removed; route parse gains #/stats and #/stats?session=a%2Fb, red with the PAGES row dropped.
- live probe, 15 stats lines (scripts/lib/stats-probes.js): stats n<20 and stats long session whole red against a daemon with TAIL_MIN_SAMPLES 0 and its fold cut at 1000 rows; stats caveat clears red with the clearing line removed; Pace rule: chip marks active red (renderEndpointChips toggle removed) in the same run as stats restored green.
- archive probe: stats offline drawn / n<20 / inert / filter disabled, stats rail in export; red with the /stats branch removed from archiveSource and with the PAGES row live: true. pages hidden in export unchanged.
- Chrome: shell <w> page stats fits at 1440/1000/600/400, red at 600 and 400 with .stats-scroll overflow-x removed; stats huge first draw reported, not judged.
Counts: static 102 -> 107, UI_EXPECT_CHECKS 236 -> 251, ARCHIVE_EXPECT_CHECKS 43 -> 48. render-web-ui.js budget tightened 2398 -> 2395 (seven doubled blank lines removed to make room for the wiring).
Deviation: no column offers sort=errors (the note's column list has none for it).
<!-- SECTION:NOTES:END -->
