---
id: KIN-55
title: >-
  Live-row probe leaves the newest path visit open, so `failures unfolded` and
  `expanded unfolded` pass vacuously
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 16:44'
updated_date: '2026-10-19 17:20'
labels:
  - 'item:web-live-rows-filtered'
dependencies:
  - KIN-53
type: bug
ordinal: 55000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA on the KIN-52..54 diff. Reproduction: in inspector-daemon/src/main/resources/web/js/50-path.js (summarised branch) set chip.dataset.failed = "0" and run INSPECTOR_UI_ORIGIN=http://127.0.0.1:<port> node scripts/render-web-ui.js on the CI fixture. Base gives 'failures unfolded: NO' and 'expanded unfolded: NO'; head gives 197/0 with '0 summarised lines'. Removing the twisty and pv-more presses in probeLiveRows (scripts/lib/live-filter-probes.js) brings back '1 summarised line'. The same leftovers move 'marker dividers' from 4 to 5 and switch the lastCapture check from the 'newest row in this session' text to the 'last capture' text. Roadmap: docs/ROADMAP.md section 3, entry 'Live rows ignore the filter until the next reload.' (item:web-live-rows-filtered). Same human-gate note as KIN-53: the plan must say if it changes the daemon's live WebSocket message shape, any :inspector-model type, or a recorded decision in docs/decisions/.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 With chip.dataset.failed = "0" in 50-path.js, 'failures unfolded' and 'expanded unfolded' report NO on head, as on base; restored, they pass. 'marker dividers' and the lastCapture check see the same page state they did at base 4e5491c.
- [x] #2 The live-row probe leaves the page as it found it (or runs where its state cannot reach a later probe), and the live probes still go red with the push-everything line put back in ws.onmessage.
- [x] #3 The two rules QA found unguarded each get a check that fails without them: the signals-ticket test inside rereadLive, and the session clause of stands() (both in web/js/15-session-data.js). Proven red then green.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, engineer). Cause, measured by dumping the page's state before and after probeLiveRows on the CI fixture: the probe leaves state.expandedVisits holding the newest visit's key (the twisty press) and state.lastCaptureTs set (any live row). Nothing else a reader sees differs (only the monotonic load/ticket counters move). No control undoes either, so the probe snapshots both at its start and puts them back in a finally, through a new ctx.pageState() (vm.runInContext('state', vmContext), added to the existing filterCtx line in render-web-ui.js, no line added). Static guards in scripts/lib/live-reread-checks.js: checkRereadLandsLate runs for signals as well as calls ('older reread signals'), and a new 'reread session changed' (S's re-read held, the session switched to T with T's load still out, same filter, so only the session clause can drop it). Gate: none (no daemon, model, wire or decision change; one label reference in docs/decisions/web-ui.md widened from 'older reread dropped' to 'older reread …').

Built (bob), not committed. Results on the CI fixture (:8231): report identical to base 4e5491c except the ten new live lines and the count: marker dividers 4, lastCapture 'newest row in this session', failures unfolded '1 summarised line'. Snapshot HTML identical to base apart from arm-timer ids and uptime.
Red proofs, each restored: chip.dataset.failed = "0" at 50-path.js:444 -> 'expanded unfolded: NO - an expanded line hides a failure', 'failures unfolded: NO - Checkout hide a failure' (195/2); same break with the restore taken out -> both yes, '0 summarised lines', marker dividers 5, 'last capture' (the QA reproduction). Push-everything put back in ws.onmessage (calls and signals) -> live header plain, live rows list/timeline/path, live header filter, live paused no read NO (191/6). Signals ticket test out of rereadLive -> 'older reread signals: NO - the list holds s1'. Session clause out of stands() -> 'reread session changed: NO - the list held c2,c1 on session T'.
Counts: render-web-ui.js 197 (UI_EXPECT_CHECKS unchanged), check-web-static.js 87 checks 0 failed (was 85), archive 37, repo-checks 35/0. render-web-ui.js stays 2758 lines, budget unchanged.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), round 2. AC1: with chip.dataset.failed = "0": 'expanded unfolded: NO', 'failures unfolded: NO - Checkout hide a failure' (195/2); restored: 'failures unfolded: yes (1 summarised line...)', marker dividers 4, lastCapture 'newest row in this session', as at base 4e5491c. With the probe's restore taken out: back to 0 summarised lines, 197/0, marker dividers 5, so the restore is what fixes it. AC2: report and HTML identical to base apart from the ten live lines and timer ids; push-everything put back -> live header plain, live rows list/timeline/path, live header filter, live paused no read NO (191/6). AC3: signals ticket test removed from rereadLive -> 'older reread signals: NO' (87/1); session clause removed from stands() -> 'reread session changed: NO' (87/1); each restored to 87/0. Not guarded: the restore in the probe has no check of its own (197/0 if deleted alone), so a later probe that opens a visit could empty 'failures unfolded' again without failing.
<!-- SECTION:FINAL_SUMMARY:END -->
