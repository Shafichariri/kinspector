---
id: KIN-52
title: 'Web: a whitespace-only filter is no filter on the page, as on the daemon'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 15:16'
updated_date: '2026-10-19 17:20'
labels:
  - 'item:web-live-rows-filtered'
dependencies: []
type: bug
ordinal: 52000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap: docs/ROADMAP.md section 3, Bugs found not fixed, entry 'Live rows ignore the filter until the next reload.' (item:web-live-rows-filtered). The design choice is left to the engineer's plan, written into this ticket before building. The plan must say whether it changes the daemon's live WebSocket message shape, any :inspector-model type, or a recorded decision in docs/decisions/; any of those is a human gate, stop and ask before building. This ticket is the one 'is there a filter' test the page needs, ordered first because the 'no filter, no request' criterion of the live-rows ticket depends on it.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 A whitespace-only filter is no filter on the page, as it is on the daemon. (Added 2026-10-19, from the same panel.) The daemon treats a blank filter as none (SessionRepository, filterText.isBlank()), so filter=%20 returns the whole session. The page reportedly treats it as a filter and draws the filtered N of M calls shape. This was read from the code and a curl, not driven in the page, so the architect confirms it first. The criterion holds either way: with only spaces typed, the header, the notes and the live path behave as with nothing typed, and a live row costs no request. The page decides "is there a filter" in one place, and a check fails with a space typed if it disagrees with the daemon.
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

Confirmed by driving the page, not only by reading code: scripts/lib/live-filter-probes.js `blank filter header`, run through render-web-ui.js against the CI probe fixture on :8231, before any web change: "NO - \"1000 of 1203 calls\" with only spaces typed, \"newest 1000 of 1203 shown\" with nothing; the daemon reads both as no filter". So the page does treat a whitespace-only filter as a filter. Cause: applyFilter stores the box text as typed, and every reader asks `state.filter` for truthiness.

Fix: applyFilter (25-traffic-list.js), the one funnel all six ways of setting a filter pass through, stores '' when the box holds only whitespace. That is the single place the page decides "is there a filter"; every other reader keeps its truthiness test. The text sent to the daemon is then '' instead of spaces, which the daemon answers identically (filterText.isBlank()). JS trim() and Kotlin isBlank() differ on a few exotic code points (U+FEFF is blank to JS only; U+001C..U+001F to Kotlin only); the criterion is about spaces, and the probe pins spaces. Not a mirror case: the page decides only whether to ask, never which rows match, and the probe compares against the live daemon's own answer to filter=%20%20%20.

Checks: `blank filter header` (long session header, red today) and `blank filter free` (a live row with only spaces typed reads no list route; goes red only once KIN-53's re-read exists and this fix is taken out).

Gate: none. No :inspector-model type, no wire protocol, no api/ golden, no daemon change, no decision rewritten. Size: S as part of the item.

Built (bob), not committed. Changed: web/js/25-traffic-list.js (applyFilter stores '' for a whitespace-only box). Probes: scripts/lib/live-filter-probes.js `blank filter header`, `blank filter free`.
Proof, against the CI probe fixture on :8231 (render-web-ui.js): with the fix, `blank filter header: yes ("newest 1000 of 1203 shown" with only spaces typed, as with nothing ...)` and `blank filter free: yes (... no list route was read)`. With `state.filter = typed` put back: `blank filter header: NO - "1000 of 1203 calls" with only spaces typed, "newest 1000 of 1203 shown" with nothing` and `blank filter free: NO - 1 list reads, call drawn: true`. Restored, green.
Known residue, by design: JS trim() and Kotlin isBlank() disagree on U+FEFF and U+001C..U+001F; the criterion and the probe are about spaces.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), round 1 and re-verified round 2 (snapshot of the uncommitted diff on base 4e5491c, JDK 21). AC1 Verified: probe 'blank filter header: yes ("newest 1000 of 1203 shown" with only spaces typed, as with nothing...)' and 'blank filter free: yes'; curl filter=%20%20%20 returns 16 of 16 as with no filter; headless Chrome with spaces typed: filter "", header '83 of 83 calls', 0 list reads after 2 s. Red: state.filter = typed put back -> 'blank filter header: NO' and 'blank filter free: NO', 195 passed 2 failed; restored 197/0. DoD2 guards proven both ways; DoD3 client-name scan 'no match for 8 term(s)'. Not verified: Safari, Firefox, a phone. Page and daemon disagree on U+FEFF (page: no filter) and U+001C (page: a filter); noted, not in the criterion.
<!-- SECTION:FINAL_SUMMARY:END -->
