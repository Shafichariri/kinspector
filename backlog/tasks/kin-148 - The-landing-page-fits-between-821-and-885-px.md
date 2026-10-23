---
id: KIN-148
title: The landing page fits between 821 and 885 px
status: To Do
assignee: []
created_date: '2026-10-23 07:59'
labels:
  - 'item:landing-page-fits-821-885'
dependencies: []
ordinal: 148000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 36. Placed 2026-10-23 by the product owner (PR #187). Acceptance criteria are the roadmap item's own, from docs/ROADMAP.md under the heading carrying this ticket's item label. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 No width from 360 to 1440 in 5 px steps has a scrollWidth above the window
- [ ] #2 The 400 px guard's check is extended to that sweep, or to a width in the band, and proven red
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
