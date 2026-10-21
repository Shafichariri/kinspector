---
id: KIN-71
title: The lost-daemon banner on every unplanned close
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:03'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). js/05-network.js: every close that is not a stop sets away and paints the lost banner, except a restart younger than RESTART_GRACE_MS (20 s, state.restartAt set in requestRestart, cleared in onopen). Lines banner every drop, banner after failure, banner restart lost replay the page's own handlers on the Processes page beside the stop probe; recovers re-proven red (Pace).
<!-- SECTION:NOTES:END -->
