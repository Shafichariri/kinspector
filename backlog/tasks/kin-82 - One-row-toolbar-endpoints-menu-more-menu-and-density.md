---
id: KIN-82
title: 'One-row toolbar, endpoints menu, more menu and density'
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 82000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 toolbar one row, endpoints menu, more menu, density, shortcuts in menu, exportMenu on the more menu green
- [x] #2 room <w> toolbar one row at 1440 and 1000; export items hidden in the export
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified. AC1: live 'toolbar one row', 'endpoints menu', 'more menu', 'density', 'shortcuts in menu' pass (live 236 passed, 0 failed); the menu holds html, md, json, har, keyboard shortcuts and compact rows, checked in Chrome. QA's report does not name the exportMenu probe separately; it is inside the 236. AC2: measure 'room 1000 toolbar one row' (base: overflows), 'room 600/400 toolbar fits'; archive 'export items hidden'. DoD2: base red, head green. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
