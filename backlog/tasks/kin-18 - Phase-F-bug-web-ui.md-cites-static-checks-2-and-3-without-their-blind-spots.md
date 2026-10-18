---
id: KIN-18
title: 'Phase F bug: web-ui.md cites static checks 2 and 3 without their blind spots'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-14
type: bug
ordinal: 18000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (S3). The decision cites checks 2 and 3 as guaranteeing one declaration per name, but column-0 detection misses indented declarations, '/* x */ function stop(){}' and the second name in a multi-line 'let a = f(1,\n 2), ok = 3'. Engineer chooses fix or disclosure; the choice is recorded in the ticket notes.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Either the scan handles indented declarations, a leading block comment and the second name in a multi-line declaration, each proven red then green; or docs/decisions/web-ui.md states the limit in one sentence where it cites the checks
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
