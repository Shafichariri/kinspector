---
id: KIN-40
title: >-
  Web bug: path stations say '0 of N match' for visits whose calls match, past
  1000 matches
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 40000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. On the 3016-call session under path:/v3/shop (3015 match), Home, Product and Cart read '0 of 2/2/5 match', because their calls are older than the newest 1000 loaded rows. Beside it, the note says '1 call hidden' and the header '1000 of 3016 calls'.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A visit older than the oldest loaded filtered row does not claim 0 matches: it says the calls are not loaded, or it counts correctly
- [ ] #2 A check on a >1000-match session, red against a86dde2
- [ ] #3 The header's count and the note agree, or the note says what its count covers
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
