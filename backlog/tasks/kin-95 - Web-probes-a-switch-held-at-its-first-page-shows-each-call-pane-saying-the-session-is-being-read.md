---
id: KIN-95
title: >-
  Web probes: a switch held at its first page shows each call pane saying the
  session is being read
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 22:17'
updated_date: '2026-10-21 23:21'
labels:
  - 'item:web-switch-clears-call-panes'
dependencies:
  - KIN-94
ordinal: 95000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 20. Design: the architect's note for item:web-switch-clears-call-panes (run of 2026-10-20). T2; ships in one PR with T1, T1's commit first.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Nine live-probe lines (switch list … switch counts), each red with the break the note names, and each failing with 'found no …' when the session left drew nothing in that pane
- [ ] #2 UI_EXPECT_CHECKS is 260
- [ ] #3 traffic session switch window in measure-web-ui.js --only traffic, red with renderList() removed from followSession, and green
- [ ] #4 live timeline plain re-proven red with drawLive() removed (Pace rule)
- [ ] #5 now switch shows none stays green, unchanged
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. New scripts/lib/switch-call-probes.js: probeCallsAfterSwitch(ctx, pair), nine lines (switch list/timeline/waterfall/path/browser/endpoints/tabs/detail/counts), each holding the switch's first page through a fetch stub and failing 'found no …' (scan-verdicts emptyScan) when the session left drew nothing there.
2. Called at the end of probeCurrentAfterSwitch in current-guard-probes.js; render-web-ui.js unchanged (2354).
3. ci.yml UI_EXPECT_CHECKS 251 -> 260.
4. live-traffic-cases.js: 'traffic session switch window' inside the old-lands-first session race (9 -> 10 traffic readings).
5. Red proofs per the note's table, Pace rule (live timeline plain red with drawLive() removed), now switch shows none unchanged.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on origin/main 5b21593 (bob). Uncommitted, ready for QA once committed as T2 after T1.
Files (T2): scripts/lib/switch-call-probes.js (new, 207), scripts/lib/current-guard-probes.js 290->293, scripts/lib/live-traffic-cases.js 284->332, .github/workflows/ci.yml UI_EXPECT_CHECKS 251->260. render-web-ui.js unchanged at 2354.
Live probe on the CI fixture, port 8102: 260 passed, 0 failed, 1 n/a (facet filter, as base). Archive: 48 passed.
Reds (each a separate jsdom run, tree restored after): renderList() out of followSession: switch list 'NO - the list draws 16 of <probe>'s rows' (and switch endpoints). 25 back to renderListEmpty(): '#list-empty reads "no calls were recorded in this session"'. applyView() out: timeline/waterfall/path/browser NO (16 calls, 16 rows, 6 stations, 2 keys), plus detail and now switch shows none. 70 guard out: '#timeline-empty reads "nothing was recorded in this session"'. 45 guard out: '#waterfall-empty reads "no calls were recorded in this session"'. 60 sessionPending() out: '#browser-list-empty reads "no state signals in this session"'. allTransactions clear out: 'NO - endpoints ▾ 10 with stock, Balance, …'. renderTabs back to availableViews(): 'NO - the bar lost all, waterfall, cache, screen, state, analytics, tap, …'. selectedId line out: 'NO - the detail shows <probe>'s GET /v3/shop/stock'. markers clear out: 'NO - markers · 2'. renderCounts() out: 'NO - #counts reads "16 of 16 calls"'.
Path deviation: the note's '|| sessionPending()' break stays green (260 passed); the wrapper saysBeingRead(...) unwrapped gives 'NO - .pv-empty reads "nothing recorded in this session yet"'.
Found-nothing: one run with every pane's shape taken out of from (row/wf-row/pv-stop/bkey renamed, endpoints button forced hidden, tab-count renamed, select() drawing nothing, renderCounts always empty): all nine read 'NO - found no …, so this checked nothing'.
Pace: drawLive() out of 05's call branch: 'live timeline plain: NO - liveprobe-c5 not on the timeline 3 s after it arrived with no filter set'. now switch shows none green throughout (unchanged).
Chrome: measure --only traffic with renderList() out: 'FAIL traffic session switch window: old rows (the list drew 10 of …_main_measure_debug's rows while its answer was held)'. Green against a base worktree of 5b21593 with --only traffic,layout,run,live: 192 readings each side, head 0 failed; moved against base: only 'traffic session switch window: old rows -> being read'.
<!-- SECTION:NOTES:END -->
