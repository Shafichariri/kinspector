---
id: KIN-150
title: 'Guard: an interrupted measure run leaves no export temp directories'
status: To Do
assignee: []
created_date: '2026-10-23 07:59'
labels:
  - 'item:guard-export-temp-cleanup'
dependencies: []
ordinal: 150000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 40. Placed 2026-10-23 by the product owner (PR #187). Acceptance criteria are the roadmap item's own, from docs/ROADMAP.md under the heading carrying this ticket's item label. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Each of stats-export-, room-export-, polish-export- and sheet-export- is made inside the run's own temp directory, or registered with cleanUp
- [ ] #2 An interrupt mid-case leaves none of the four, proven red as item:guard-measure-interrupt-cleans-up proved its daemons
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
