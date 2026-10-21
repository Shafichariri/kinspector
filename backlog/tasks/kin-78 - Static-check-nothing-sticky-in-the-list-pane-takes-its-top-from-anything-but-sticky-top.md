---
id: KIN-78
title: >-
  Static check: nothing sticky in the list pane takes its top from anything but
  --sticky-top
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies: []
ordinal: 78000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 sticky top green on the tree; red with top: 0 on #timeline > .filter-note
- [x] #2 .pv-metro changed with path station readings identical against base
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc (PR #119), Verified. AC1: check-web-static.js 'sticky top: yes (7 sticky selectors, 6 tops, each from --sticky-top)'; red via mutation m1 (top: 0 on #timeline > .filter-note): 'sticky top: NO - ... #timeline > .filter-note { top: 0 } (css/35-list.css)'. AC2: path station readings unchanged against base in measure-web-ui.js --base. DoD2: m1 red then restored. DoD3: client-name scan, no match.
<!-- SECTION:FINAL_SUMMARY:END -->
