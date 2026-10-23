---
id: KIN-155
title: A run's id changes when the order flips on a same-millisecond tie
status: To Do
assignee: []
created_date: '2026-10-23 10:21'
labels:
  - 'item:timeline-run-id-stable'
dependencies: []
ordinal: 155000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 35b (§3 → Bugs found, lanes L then W). The architect ruled it is not public API (no golden names TimelineRun; the model has no twin); only the value on a tie changes. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:timeline-run-id-stable holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
