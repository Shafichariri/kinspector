---
id: KIN-70
title: Top bar and tab bar at 400-1440px
status: To Do
assignee: []
created_date: '2026-10-21 04:39'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-66
ordinal: 70000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Top bar scrollWidth <= window at 400 and every control reachable by pointer and keyboard
- [ ] #2 The whole count, filtered too, inside the window at 400
- [ ] #3 Start time visible with the picker closed and truncated; picker label whole or ellipsized with tooltip at 1440
- [ ] #4 Every tab reachable at 400
- [ ] #5 narrow top bar css, narrow tabs css, tabs menu, picker face, shell and topbar readings green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
