---
id: KIN-150
title: 'Guard: an interrupted measure run leaves no export temp directories'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 07:59'
updated_date: '2026-10-23 08:26'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
bob: PR (row 40). scripts/lib/measure-scratch.js scratchDir(prefix) makes the six case export dirs (stats-export-, room-export-, polish-export-, sheet-export-, plus sensitive-export- and polish-txn-, same pattern) inside the run's temp dir, which cleanUp removes on SIGINT/SIGTERM; throws if measure-web-ui.js never called setScratchRoot. Red on main (TMPDIR set to a fresh dir, SIGINT when the dir appears): polish-export- and sheet-export- left behind; room-export- and stats-export- were removed in 8 of 8 tries each only because closing Chrome rejects the case's pending call and its finally wins cleanUp's 500 ms wait (a race). Green: all six seen inside measure-web-ui-*/, nothing left. Guard red: setScratchRoot commented out -> exit 2 'scratchDir(polish-export-): no run directory set'. Full room,polish,shell 114/0 and sheet 5/0, nothing left. Ready for QA.
<!-- SECTION:NOTES:END -->
