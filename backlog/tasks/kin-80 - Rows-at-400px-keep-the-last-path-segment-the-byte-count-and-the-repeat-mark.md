---
id: KIN-80
title: 'Rows at 400px keep the last path segment, the byte count and the repeat mark'
status: In Progress
assignee: []
created_date: '2026-10-21 13:45'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 80000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 (b) every row of the probe session at 400px shows its path's last segment and its byte count in full
- [ ] #2 every repeat mark reads in full (2x); row parts, narrow row css, room 400 rows
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
