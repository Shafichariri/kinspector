---
id: KIN-81
title: 'Overview line: the now strip and the axis in one band'
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 81000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 overview line, the four now lines and row 4's now lines green
- [x] #2 room 1440 bands list <= 160px; base -> head heights stated
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified. AC1: live 'overview line' passes; 'now ...' and row-4 lines stay green; overview is one line, 33px, at every width. AC2: 'room 1440 bands list' top 145px (base 208px) and 'bands timeline' 145px (base 234px), against the 160px bar. DoD2: base red, head green. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
