---
id: KIN-37
title: 'Web bug: a session past the argument-spread limit renders a blank page'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 37000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. Introduced by KIN-34, which reads the whole-session call list in full. Math.min/max(...array) breaks at about 110-120k elements in Chrome. On 160,000 calls: RangeError: Maximum call stack size exceeded at sessionWindow (55-axis.js:38), 0 rows drawn, stuck at 'connecting…'. e1711a3 drew 1000 rows. Sites: 55-axis.js:32-38, 50-path.js:88, 70-timeline.js:108 and :151, 25-traffic-list.js:247, 45-visits.js:181-182. Harnesses: $TMPDIR/doug3/tools/huge.js and hugechrome.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 No spread of a row array into an argument list anywhere in web/js; loops instead
- [ ] #2 A static check at 200k rows, red if any spread returns, green now
- [ ] #3 On a >160k-call session in headless Chrome: rows and the axis are drawn, no page exception, and the dot reaches connected; recorded as a documented check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
