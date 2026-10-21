---
id: KIN-85
title: Every count says what it counts
status: To Do
assignee: []
created_date: '2026-10-21 13:45'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-81
ordinal: 85000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 (f3) counts agree live and exported on the capped session, red with an unlabelled scope count and with the tab counting rows
- [ ] #2 (f2) the count on one line at 1000 for every wording
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
