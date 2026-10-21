---
id: KIN-77
title: >-
  The Stats page: table, sort, row to Traffic with the caveat, live refresh,
  export mode
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 16:25'
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
- [x] #1 PAGES row stats and rail link #/stats; route parse holds #/stats; route pages and rail is navigation pass
- [x] #2 One row per route with the §6.6 columns; sort headers send sort=; a session switch reloads it
- [x] #3 A row opens Traffic with the row's filterTerm and the caveat; caveat gone after an edit or switch
- [x] #4 Live: at most 2 stats requests per burst, never two in flight, none while paused or hidden
- [x] #5 Export mode draws from the embedded report with no network attempt
- [x] #6 No sideways scroll on #/stats at 1440/1000/600/400
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
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

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR B, d256174, base 31790f8; endpoint-latency-stats-report.md), Verified, headless Chrome only. AC1: Stats between Traffic and Sessions, href #/stats, aria-current page; check-web-static.js 107, 0 failed (102 at base) including route parse. AC2: at 1440, 1000 and 400 every cell equals GET .../stats ('all cells agree'), inspector stats --session equals REST routes; p50 click sends one request with sort=p50, aria-sort descending, order equals REST ?sort=p50. AC3: /orders/{id} -> #/traffic/network, filter 'method:GET host:api.qa.invalid path:/orders/*' (the daemon's filterTerm), 19 rows, caveat 'From Stats: GET /orders/{id}. A filter cannot say a route exactly...', back to Stats returns, editing the filter hides it. AC4: 30 calls over ~1 s cost 1 /stats read; paused 5 calls, 0 reads; 'hidden' is held by the static check 'stats refresh hidden' in the 107-green run (not driven in a hidden Chrome tab). AC5: file:// export at 1440 and 400: 10 rows, every cell equals REST, 0 open and 0 sort buttons, archive probe 48 passed, no network attempt. AC6: docScroll equals the window at 1440/1000/600/400, 'shell … page stats fits' pass, table scrolls inside #stats-scroll at 400. Also render-web-ui.js 251 passed 0 failed 1 n/a; measure-web-ui --base: 270 readings, 2 failed (KIN-42's, both sides). QA red: m1 withheld cell drawn as a dash -> 'stats n<20: NO - p95 "—"'; m2 oweStats per row -> 'stats refresh burst: NO - 100 owed rows over 1294 ms cost 100 requests'; m3 (export /stats branch) not a valid proof and not counted. DoD2: the other reds (stats long session whole, caveat clears, archive offline and rail, 600/400 fit) are the engineer's record, not repeated. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
