---
id: KIN-106
title: >-
  Polish A5: Settings says the right counts on first load, in words that say
  what they count
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 19:58'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 106000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The note's §9 A5 criteria hold, each guard shown red (folded defect: counts read 0 when opened straight to #/settings)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A5 criteria:
1. Opened straight to #/settings on the probe session, Settings reads '3 rows in this session' and 'showing 10 of 13 endpoints in this session' once loaded, at 1440 and 400 in Chrome (polish settings first load counts) and in jsdom (settings counts follow load). Both shown red with the repaint removed.
2. 'row(s)' appears nowhere.
3. The counts paragraph of §8 is in.
Pace rule: chip marks active re-proven red with the new shared-page line present.
Files: web/js/80-settings.js, web/js/16-session-load.js (loadTransactions lives there now, not 15-session-data.js), scripts/lib/polish-probes.js, polish-cases.js, .github/workflows/ci.yml, docs/decisions/web-ui.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Rebased onto 5253bf3 and finished (part 3b). Ready for QA as patch 2 of 3 (A4, A5, A6) under the run's scratch; not committed.
Done: paintSettingsCounts() in 80-settings.js ('3 rows in this session', 'showing 10 of 13 endpoints in this session'; no 'row(s)' left in web/), called by applyDuplicateWindow, applyEndpointLimit and enterPage('settings'); Settings joins callsLanded (16-session-load.js) from wireSettingsPage. Deviation from note §3 P8: loadTransactions does not call paintSettingsCounts directly; pages add their repaint to callsLanded, because seven vm harnesses run loadTransactions and would need stubs. Live 'settings counts follow load'; Chrome 'polish settings first load counts' (both widths, served). web-ui.md counts paragraph (note §8) in, naming callsLanded.
Red proofs: 'settings counts follow load: NO - after the load "0 rows in this session", "showing 0 of 0 endpoints in this session", due "3 rows ...", "showing 10 of 13 endpoints ..."' (from before the park). Chrome, with Settings' callsLanded line removed: 'FAIL polish settings first load counts: not the session's (1440: "0 rows in this session", "showing 0 of 0 endpoints in this session" for 16 calls, due "3 rows in this session", "showing 10 of 13 endpoints in this session"; 400: the same)'. Restored: 'ok polish settings first load counts: the session's (1440: ...; 400: ...)'. Pace: 'chip marks active : false (the clicked chip, and only it)' with this line, A4's and A6's present (295 passed, 4 failed); restored, 304 passed.
State after patches 1-2: ci.yml 302/58; static 134/0; live 302 passed; archive 58 passed.
<!-- SECTION:NOTES:END -->
