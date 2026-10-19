---
id: KIN-54
title: 'Web: the header and the tab count follow live rows'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 15:16'
updated_date: '2026-10-19 17:20'
labels:
  - 'item:web-live-rows-filtered'
dependencies:
  - KIN-53
type: bug
ordinal: 54000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap: docs/ROADMAP.md section 3, Bugs found not fixed, entry 'Live rows ignore the filter until the next reload.' (item:web-live-rows-filtered). The design choice (daemon says whether the row matches, or a throttled re-read of the filtered set) is left to the engineer's plan, written into this ticket before building. The plan must say whether it changes the daemon's live WebSocket message shape, any :inspector-model type, or a recorded decision in docs/decisions/; any of those is a human gate, stop and ask before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The header and the tab count follow live rows. (Added 2026-10-19, from the first verify panel on item:web-transactions-paged.) Today renderCounts runs on a load and from setTimeRange, never when a live row arrives, so 16 of 16 calls stays while 20 rows are drawn, and the tab reads traffic 1000 while the list grows. After this item, once a live row has been admitted or refused, the header and the tab count say what the list and the session now hold, under a filter and without one. A live-row probe asserts the header after two live rows and fails with the counts left to the next load.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, engineer; no architect note on this small track).

Cause: renderCounts runs on a load and from setTimeRange only, and renderTabs runs on a live signal but not a live call, so the header and the traffic tab (state.transactions.length) lag the list.

Fix, on KIN-53's design:
- No filter: a live call is admitted at once, so the socket handler calls renderCounts and renderTabs after pushing it (the header's sessionTotal already moves). A live signal already rebuilds the tabs.
- Under a filter: on arrival only the session changed, so renderCounts runs then (sessionTotal + 1, list unchanged); when rereadLive lands, the route's total and matched are written and renderCounts and renderTabs run, so the header and the traffic tab say what the list and the session hold once the daemon has admitted or refused the row.
The traffic tab keeps counting the list, as today; only when it is redrawn changes.

Checks (scripts/lib/live-filter-probes.js via render-web-ui.js): `live header plain` (two live calls, no filter: "18 of 18 calls", traffic 18 on the 16-call fixture) and `live header filter` (under status>=500 | tag:<tag>, one matching and one refused call: "5 of 18 calls", traffic 5). Both red before the change: "16 of 16 calls", traffic 17; "4 of 16 calls", traffic 6.

Gate: none (web only). Size: a few lines on top of KIN-53.

Built (bob), not committed. Changed: web/js/05-network.js (renderCounts and renderTabs after a live call, filter or not), 15-session-data.js (renderCounts and renderTabs when a re-read writes).
Green: `live header plain: yes ("18 of 18 calls", traffic 18, after two live calls)`, `live header filter: yes ("5 of 18 calls", traffic 5, once the daemon answered)`.
Red with both redraws removed from the handler and the re-read: `live header plain: NO - "16 of 16 calls", traffic 17` (17 because a live signal already rebuilt the tabs), `live header filter: NO - "4 of 16 calls", traffic 4`. Restored, green.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), rounds 1 and 2. AC1: 'live header plain: yes ("18 of 18 calls", traffic 18)' and 'live header filter: yes ("5 of 18 calls", traffic 5)'; Chrome: '2 of 27' with traffic 2 at arrival, '3 of 27' once settled, '82 of 82' with no filter. Red: redraw removed from the handler -> plain NO; from the re-read -> filter NO; from both -> both NO. Run: INSPECTOR_UI_EXPECT_CHECKS=197 node scripts/render-web-ui.js, exit 0, 197 passed 0 failed.
<!-- SECTION:FINAL_SUMMARY:END -->
