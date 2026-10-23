---
id: KIN-159
title: >-
  Web: the Diff page draws a route with unknown keys without the unchanged
  toggle
status: To Do
assignee: []
created_date: '2026-10-23 10:38'
updated_date: '2026-10-23 10:38'
labels:
  - 'item:diff-unsure-key-shown'
dependencies:
  - KIN-158
ordinal: 159000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap row 31, lane W (T2 of designs/diff-unsure-key-shown.md), for the web developer. Depends on the daemon ticket with the same label, which adds DiffRoute.unsure and counts.unsure. The page reads the daemon's flag and decides nothing. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label, narrowed to the page. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With the toggle off, a route with unsure: true is drawn, its change cell reads 'unknown keys' (diff-tag data-kind=unsure, with the design's title), and the page never reads as no change for it
- [ ] #2 The page hides only the rows the daemon counts unchanged (changes empty and not unsure); the toggle counts with the same predicate
- [ ] #3 The summary names the daemon's counts.unsure ('N with unknown keys') between changed and unchanged
- [ ] #4 Static 'diff unsure shown' and live 'diff unsure shown' (base against probe, the feed row) are shown red with the old predicate; 'diff truncated unknown' no longer needs the toggle and a neighbouring line is re-proven red (Pace rule); CI's static and live counts move
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
