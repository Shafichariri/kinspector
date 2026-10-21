---
id: KIN-86
title: Each traffic view opens where it was left
status: In Progress
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 14:36'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 86000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 with live paused list -> timeline -> list returns the list's own position; the timeline opens where it was left or at its start the first time
- [ ] #2 with live on each view follows as before and a run toggle does not move the pane
- [ ] #3 views keep place (jsdom) and room views keep place (Chrome); run readings unchanged
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in 36c2c7a. applyView saves the shared pane's scrollTop under the view it leaves and restores the one it opens (saveViewPlace/openViewPlace in js/20-navigation.js), only while live tail is paused; first open is the top (path keeps renderPath's rule). Places are kept with the list they were saved on (session, filter, order, stretch) and dropped the first time a view opens on another list (viewPlaces), instead of clearing in loadTransactions/setSortOrder/setTimeRange, so the static harnesses that run those files needed no stub. Checks: live 'views keep place' (list 300 -> timeline 0 -> 120 -> list 300 -> timeline 120; after a filter the timeline 0; red: restore removed); measure-web-ui 'room views keep place' on the 3016-call session (paused list 5000 -> timeline 0 -> list 5000; live on, the timeline opens at its end; red: restore removed); check-web-static 'socket …' and 'timeline asked follows' green; Chrome run/live readings green. Ready for QA.
<!-- SECTION:NOTES:END -->
