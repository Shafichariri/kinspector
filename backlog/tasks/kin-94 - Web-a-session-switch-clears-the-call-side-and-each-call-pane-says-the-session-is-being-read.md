---
id: KIN-94
title: >-
  Web: a session switch clears the call side, and each call pane says the
  session is being read
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 22:17'
updated_date: '2026-10-22 06:42'
labels:
  - 'item:web-switch-clears-call-panes'
dependencies: []
ordinal: 94000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 20. Design: the architect's note for item:web-switch-clears-call-panes (run of 2026-10-20). Overturns the web-ui.md sentence on keeping the left session's calls during a switch (maintainer approved). T1.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 With the new session's first page held, the list, timeline, waterfall, path and a tag browser draw none of the left session's rows, and each reads exactly 'this session is being read…'
- [x] #2 In the same window the endpoints button is hidden and its popover holds no chip; the header count is empty; the markers button reads 'markers' and its popover reads 'this session is being read…'
- [x] #3 The tab bar keeps the tabs it had, with no count on any tab or views item
- [x] #4 A call the left session had open is closed and the glance reads 'this session is being read…'; a call an address opened on the new session stays open
- [x] #5 beingRead in js/16-session-load.js is the only place the wording lives (being read wording, red both ways); switch clears calls red with the copy clear and with the callLoad bump removed
- [x] #6 The web-ui.md paragraph is replaced by the note's text, word for word
- [x] #7 No web/js file past 600 lines; no budget added or raised; 50-path.js stays at 600 and render-web-ui.js at 2354
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Build from the architect's note (web-switch-clears-call-panes.md), against origin/main 5b21593 (after #129, #132, #133).
1. New web/js/16-session-load.js (beingRead, sessionPending, saysBeingRead, followSession moved from 15 and extended: callLoad++, call side cleared, the left session's open call closed unless selectedId, then renderCounts/renderTabs/renderMarkers/renderList/applyView). Registered in web/scripts.txt after 15.
2. Pane guards: 20 renderTabs (state.tabViews), 00 state.tabViews, 25 renderList/renderMarkers, 45 renderWaterfall, 70 renderTimeline, 50 renderPath (five lines, file stays 600), 60 renderBrowser, 65 renderSessionGlance.
3. Static harness: add 16 to every vm context that runs loadTransactions or a pane file; stub states get signalSessionId; count back at the base (110, the note's 107 moved with #133) before new checks.
4. New scripts/lib/being-read-checks.js: 'being read wording' and 'switch clears calls' (110 -> 112), each proven red the note's ways.
5. docs/decisions/web-ui.md: the 'signal side follows' paragraph replaced by the note's text word for word.
Budgets: no web/js past 600, none added or raised; 50-path.js at 600; render-web-ui.js at 2354.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on origin/main 5b21593 (bob). Uncommitted, ready for QA once committed as T1.
Files (T1): web/js/16-session-load.js (new, 70), 15-session-data.js 533->506, 00-core.js 314->316, 20-navigation.js 436->438, 25-traffic-list.js 591, 45-visits.js 279->280, 50-path.js 600, 60-browser.js 316, 65-browser-detail.js 347->346, 70-timeline.js 379->380, web/scripts.txt (+16), scripts/lib/being-read-checks.js (new, 118), scripts/check-web-static.js 161->163, session-state-checks.js / live-reread-checks.js / follow-checks.js / long-session-checks.js (+-0, file lists and stubs in place), docs/decisions/web-ui.md 662->683 (note's paragraph verbatim; it is 42 lines for 21, so +21, not the note's ~+12).
Static: 110 (base moved from the note's 107 with #133) -> back at 110 after the mechanical edits -> 112, 0 failed.
Red: being read wording: 'NO - js/65-browser-detail.js holds its own "being read" text' and 'NO - beingRead() reads "this session is being read...", where ... was due'. switch clears calls: 'NO - allTransactions still holds 3 of A's calls (the chips and the axis read it)' and 'NO - callLoad 5, so a load of A in flight still writes'; also the kept-call branch: 'NO - a call an address opened on B was closed by the switch'.
Deviations: 16 is added to stubPage's list (long-session-checks), not also to follow-checks' before, which passes through stubPage: loading it twice redeclares sessionPending. Two stubs gained paintDetailTools (the delete and live-reread contexts now reach the real followSession). live-reread's session-changed comment said the list stands until the load answers; reworded. 00-core's signalSessionId comment reworded (being read, not loading).
Finding: the 50-path '|| sessionPending()' clause matters only when visits are non-empty in the window (a live call); with the switch's clear the list is empty, so the note's second break for 'switch path' stays green. The text is proven by unwrapping saysBeingRead instead (see KIN-95).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #135 merge 4645c43, round 1, no failures, no blocking findings. Criteria 1 to 4: `render-web-ui.js` on head, CI fixture, 260 passed, 0 failed, 1 n/a, all nine `switch ...` lines `yes` with the new session's first page held; QA's own Chrome case (A on /alpha/*, B on /beta/*) 15 of 15 clean at 1440, 1000 and 400 (no row, call, station or key of A; no chips, endpoints button or tab counts; empty #counts; open call closed; every empty line reads `this session is being read...`; no `nothing recorded` anywhere); the same driver on base 117aeee 0 of 15. Criterion 5: check-web-static.js 112/0 (110 at base); red with `state.callLoad++` removed (`NO - callLoad 5, so a load of A in flight still writes`) and with `state.allTransactions = []` removed (`NO - allTransactions still holds 3 of A's calls`). The `being read wording` red-both-ways and the kept-address-call branch are the builder's proofs; QA ran those checks green. Criterion 6: the web-ui.md paragraph equals the design note's block, 629 words each, no difference; the overturned sentence and the `loading this session...` wording are gone; DecisionsTest green. Criterion 7: :repo-checks:test --rerun green incl. FileBudgetTest; no budget added or raised. Not verified: Safari and Firefox; the delete and clear path (afterSessionChange); the `|| sessionPending()` clause in 50-path.js (no probe drives a live call in the window).
<!-- SECTION:FINAL_SUMMARY:END -->
