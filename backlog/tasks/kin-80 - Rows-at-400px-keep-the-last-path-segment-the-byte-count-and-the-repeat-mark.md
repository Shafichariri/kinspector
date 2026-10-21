---
id: KIN-80
title: 'Rows at 400px keep the last path segment, the byte count and the repeat mark'
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 80000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 (b) every row of the probe session at 400px shows its path's last segment and its byte count in full
- [x] #2 every repeat mark reads in full (2x); row parts, narrow row css, room 400 rows
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified in Chrome at 400px. AC1: across 1000 rows of the probe session and the 1203-call session, 0 rows with .path-tail or .size cut, hidden or off-screen; export: 1203 rows, 0 bad; measure 'room 400 rows: whole' (base: cut). AC2: repeat mark reads the short form 2x at 400 (1440 reads the long form); live 'row parts' and static 'narrow row css' pass. DoD2: base red (cut), head green. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
