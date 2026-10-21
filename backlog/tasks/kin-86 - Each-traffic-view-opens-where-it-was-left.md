---
id: KIN-86
title: Each traffic view opens where it was left
status: To Do
assignee: []
created_date: '2026-10-21 13:45'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 86000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 with live paused list -> timeline -> list returns the list's own position; the timeline opens where it was left or at its start the first time
- [ ] #2 with live on each view follows as before and a run toggle does not move the pane
- [ ] #3 views keep place (jsdom) and room views keep place (Chrome); run readings unchanged
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
