---
id: KIN-152
title: timeline() ignores seq on same-millisecond ties
status: To Do
assignee: []
created_date: '2026-10-23 08:38'
labels:
  - 'item:timeline-seq-ties'
dependencies: []
ordinal: 152000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 35 (§3 → Bugs found). Design: designs/timeline-seq-ties.md (run of 2026-10-20); decision 2026-10-23-timeline-device-marker-seq (a device marker carrying seq sorts by it within its millisecond). One PR: the model's timeline() and the web timelineEntries mirror, both run by the shared mirror case. The note's three side findings stay out. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:timeline-seq-ties holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
