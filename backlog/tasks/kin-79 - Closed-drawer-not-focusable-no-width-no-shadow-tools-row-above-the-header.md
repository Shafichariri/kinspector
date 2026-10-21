---
id: KIN-79
title: 'Closed drawer: not focusable, no width, no shadow; tools row above the header'
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-78
ordinal: 79000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 (a) at 1000px with the drawer closed scrollWidth equals the window and Tab never lands inside the pane, red with the fix removed
- [x] #2 (c) the close button and every header action disjoint at 1000, served and exported
- [x] #3 (d) no shadow while closed; closed drawer css
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified in Chrome. AC1: 1000px closed docScroll 1000, none of 8 focusables in the pane takes focus, measure 'room 1000 tab never enters closed: never' (base: enters); red via m2 (visibility: hidden removed): 'closed drawer css: NO - the closed #detail-pane is not visibility: hidden'. AC2: at 1000px open, the close box overlaps none of the 7 header actions; measure 'close disjoint' served and export: disjoint (base: overlaps). AC3: box-shadow none while closed, light and dark (base: drawn); static 'closed drawer css'. DoD2: base red, head green, m2 red then restored. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
