---
id: KIN-79
title: 'Closed drawer: not focusable, no width, no shadow; tools row above the header'
status: In Progress
assignee: []
created_date: '2026-10-21 13:45'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 79000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 (a) at 1000px with the drawer closed scrollWidth equals the window and Tab never lands inside the pane, red with the fix removed
- [ ] #2 (c) the close button and every header action disjoint at 1000, served and exported
- [ ] #3 (d) no shadow while closed; closed drawer css
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
