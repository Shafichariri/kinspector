---
id: KIN-105
title: >-
  Polish A4: empty states say why — one noCallsWhy, Stats, Sessions, the detail
  pane
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-23 07:23'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 105000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The note's §9 A4 criteria hold, each guard shown red; 25-traffic-list.js at most 589 lines
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A4 criteria:
1. Stats on an ended session with no calls reads 'no calls were recorded in this session', and on a recording one 'the app is connected — no calls yet' (stats empty why, red with the old literal).
2. With no session it reads the list's 'waiting for an app with inspector-stream to connect'.
3. The Sessions page with none reads the §3 P7 sentence (sessions empty why).
4. The detail pane reads 'select a call'.
5. no calls why (static) passes and was shown red.
6. 25-traffic-list.js is at most 589 lines.
7. At 400 and 1440 each sentence is whole in the window.
8. The empty-state sentence of §8 is in.
Pace rule: chip marks active re-proven red with the new shared-page lines present.
Files: web/js/15-session-data.js, 16-session-load.js (loadTransactions moved there since the note), 25-traffic-list.js, 95-stats.js, 90-sessions.js, 65-browser-detail.js (the same pane's fallback), web/index.html, scripts/lib/polish-checks.js, polish-probes.js, .github/workflows/ci.yml, docs/decisions/web-ui.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Rebased onto 5253bf3 and finished (part 3b). Ready for QA as patch 1 of 3 (A4, A5, A6) under the run's scratch; not committed.
Done before the park: noCallsWhy() in 15-session-data.js (413 lines); renderListEmpty asks it (25-traffic-list.js 587, at most 589); statsEmptyWhy asks it for no session and total 0; Sessions empty sentence (90-sessions.js); detail pane 'select a call' (index.html and the glance fallback in 65-browser-detail.js); callsLanded in 16-session-load.js, which Stats joins from wireStatsPage. Static 'no calls why', live 'stats empty why' and 'sessions empty why'. web-ui.md empty-state sentence in.
Added now: Chrome 'polish <w> empty whole' in scripts/lib/polish-cases.js: served at 1440 and 400, Stats in its three cases, Sessions, and the detail pane at 1440 (below 1200px it is a closed drawer with no call open), each sentence due and whole in the window, counted in text line boxes.
Red proofs: 'no calls why', 'stats empty why', 'sessions empty why' and the Pace red as noted before the park. 'polish 400 empty whole': with '#stats-empty, #session-rows td { white-space: nowrap }' -> 'FAIL polish 400 empty whole: cut (sessions: past the window (62..629 of 400), clipped by #page-sessions)'; with '#stats-empty span { white-space: nowrap; font-size: 15px }' -> 'FAIL polish 400 empty whole: cut (stats no session: past the window (78..432 of 400), clipped by #page-stats, wider than its box)'. Restored: 'ok polish 1440 empty whole: whole (detail in 1 line box, stats ended in 1 line box, ...)', 'ok polish 400 empty whole'.
State after this patch alone: ci.yml 301/58; static 134/0; live 301 passed; archive 58 passed (tree served ahead of the jar on 8103).

Closed 2026-10-23 by the delivery tracker. PR #181, merge 7f49e14. QA report: qa/web-ui-polish-part3-report.md (run folder of 2026-10-20), verdict close - yes.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug): check-web-static 134/0; live probe 304/0; archive 59/0; Chrome 342 readings, 0 failed on head, 3 failed on base (moved: polish settings first load counts, polish 1440/400 empty whole). A4 empty states: each sentence checked in jsdom and Chrome, reds re-run (stats empty why, sessions empty why, no calls why, 400 empty whole). 25-traffic-list.js 587 lines (max 589). Merge 7f49e14 (#181). QA report web-ui-polish-part3-report.md.
<!-- SECTION:FINAL_SUMMARY:END -->
