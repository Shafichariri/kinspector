---
id: KIN-106
title: >-
  Polish A5: Settings says the right counts on first load, in words that say
  what they count
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 10:12'
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
PARKED for after v1.4.0 (maintainer's call, relayed by the coordinator), uncommitted on branch shafic/web-ui-polish-a456 over 7882f33. The coordinator commits.
Done: paintSettingsCounts() in 80-settings.js ('3 rows in this session', 'showing 10 of 13 endpoints in this session'; no row(s) left in web/), called by applyDuplicateWindow, applyEndpointLimit and enterPage('settings') (08-routes.js now only paints, so entry computes duplicates once, not twice). Settings joins callsLanded from wireSettingsPage, so a load repaints it. Live 'settings counts follow load' in polish-probes.js. Chrome 'polish settings first load counts' in polish-cases.js is WRITTEN BUT NEVER RUN.
Red proof run: 'settings counts follow load: NO - after the load "0 rows in this session", "showing 0 of 0 endpoints in this session", due "3 rows in this session", "showing 10 of 13 endpoints in this session"' (Settings' callsLanded line removed), 270 passed and 1 failed; restored, 271 passed.
Not done: ci.yml UI_EXPECT_CHECKS still says 270, but the tree passes 271, so raise it to 271 before CI. Also not done: the Chrome case run and its red; the Pace red for this line; the §8 counts paragraph in web-ui.md.
<!-- SECTION:NOTES:END -->
