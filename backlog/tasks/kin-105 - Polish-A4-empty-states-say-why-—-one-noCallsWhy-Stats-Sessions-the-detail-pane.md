---
id: KIN-105
title: >-
  Polish A4: empty states say why — one noCallsWhy, Stats, Sessions, the detail
  pane
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 10:12'
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
- [ ] #1 The note's §9 A4 criteria hold, each guard shown red; 25-traffic-list.js at most 589 lines
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
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
PARKED for after v1.4.0 (maintainer's call, relayed by the coordinator), uncommitted on branch shafic/web-ui-polish-a456 over 7882f33. The coordinator commits.
Done: noCallsWhy() in 15-session-data.js (413 lines); renderListEmpty calls it (25-traffic-list.js 591 -> 587); statsEmptyWhy calls it for no session and for total 0; Sessions empty sentence (90-sessions.js); detail pane 'select a call' (index.html, and the same pane's fallback in 65-browser-detail.js); a callsLanded list in 16-session-load.js (loadTransactions moved there since the note), which Stats joins from wireStatsPage so it repaints once recording is known. The list sits in 16-session-load.js, not as direct calls, because seven vm harnesses run loadTransactions and would need new stubs. Static 'no calls why' and live 'stats empty why' and 'sessions empty why' (scripts/lib/polish-probes.js). ci.yml UI_EXPECT_CHECKS 268 -> 270. The web-ui.md sentence from §8 is in.
Red proofs run: no calls why: 'NO - statsEmptyWhy says "No calls in this session yet" itself' (old literal back); 'NO - ended, no endedAt, another recording: "the app is connected — no calls yet", not "no calls were recorded in this session"; …' (noCallsWhy broken). stats empty why: 'NO - ended: "No calls in this session yet.", not "no calls were recorded in this session"; recording: …; no session: "No session yet. The table fills …", not "waiting for an app with inspector-stream to connect"'. sessions empty why: 'NO - an empty list reads "no sessions", not "No sessions yet. …"'. Pace: 'chip marks active  : false (the clicked chip, and only it)', with both new lines yes, 269 passed and 1 failed. All restored: static 127/0 failed, live 270 passed.
Not done: Chrome check that each sentence is whole at 400 and 1440; measure-web-ui and repo-checks runs; per-ticket patch.
<!-- SECTION:NOTES:END -->
