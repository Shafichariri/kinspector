---
id: KIN-34
title: 'Signals bug: filter-note counts come from the 1000-row clamp'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 34000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Simon, Doug) on KIN-28 AC1 at scale, head e1711a3. On a 3016-call session: a filter matching 1200 calls draws no waterfall note; one note reads '996 calls hidden' where 3012 are; a no-match filter reads '1000 calls hidden'. The counts come from the 1000-row clamp on the rows read. The route's total is the session total and matched is the match count; state.matchTotal holds the total and is misnamed.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Unranged hidden calls = total - matched
- [ ] #2 Where only a capped range count exists, the note says 'at least'
- [ ] #3 The path's '0 of N' uses an uncapped N
- [ ] #4 state.matchTotal is renamed to what it holds
- [ ] #5 A probe or static check runs a session over 1000 calls, red at e1711a3
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
