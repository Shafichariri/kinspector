---
id: KIN-71
title: The lost-daemon banner on every unplanned close
status: To Do
assignee: []
created_date: '2026-10-21 04:39'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-66
ordinal: 71000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Banner visible after a first drop, a second drop after a reconnect and a restart that never returns; cleared on reconnect
- [ ] #2 banner lines red with the running check back; recovers re-proven red (Pace rule)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
