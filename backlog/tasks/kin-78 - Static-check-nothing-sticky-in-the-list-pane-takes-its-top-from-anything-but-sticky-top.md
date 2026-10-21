---
id: KIN-78
title: >-
  Static check: nothing sticky in the list pane takes its top from anything but
  --sticky-top
status: In Progress
assignee: []
created_date: '2026-10-21 13:45'
labels:
  - 'item:web-foundation-list-room'
dependencies: []
ordinal: 78000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 sticky top green on the tree; red with top: 0 on #timeline > .filter-note
- [ ] #2 .pv-metro changed with path station readings identical against base
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
