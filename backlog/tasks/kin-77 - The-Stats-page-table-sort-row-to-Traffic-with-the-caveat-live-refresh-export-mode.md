---
id: KIN-77
title: >-
  The Stats page: table, sort, row to Traffic with the caveat, live refresh,
  export mode
status: To Do
assignee: []
created_date: '2026-10-21 12:18'
labels:
  - 'item:endpoint-latency-stats'
dependencies:
  - KIN-75
  - KIN-76
  - KIN-66
  - KIN-70
ordinal: 77000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:endpoint-latency-stats (run of 2026-10-20), §13. PR B, after 1b. Also depends on 1b's one-row toolbar and count-words tickets (not yet filed when this was created).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 PAGES row stats and rail link #/stats; route parse holds #/stats; route pages and rail is navigation pass
- [ ] #2 One row per route with the §6.6 columns; sort headers send sort=; a session switch reloads it
- [ ] #3 A row opens Traffic with the row's filterTerm and the caveat; caveat gone after an edit or switch
- [ ] #4 Live: at most 2 stats requests per burst, never two in flight, none while paused or hidden
- [ ] #5 Export mode draws from the embedded report with no network attempt
- [ ] #6 No sideways scroll on #/stats at 1440/1000/600/400
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
