---
id: KIN-83
title: Draggable divider; hide and restore the detail column
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-79
ordinal: 83000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 divider, detail hide, room 1440 divider green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in dd44c74 (with KIN-84). #pane-divider between the panes (role=separator, aria-valuenow, tabindex 0): drag sets --list-share on #panes, clamped 25-75%, saved as inspector.listShare once a moving drag ends; ArrowLeft/Right move 2 points; double-click and Home reset and remove the pref (setListShare/wireDivider in js/20-navigation.js, grid in css/10-panes.css). The tools row (#detail-tools) is drawn in column mode too; #detail-hide puts the column away (#panes.detail-hidden, >=1200px only) and #detail-restore, a slim in-flow tab at the right edge, names the shown call and brings it back; selecting another row keeps it hidden; not kept across reloads. Below 1200px the divider, hide and restore are not drawn. Checks: live probe 'divider' (red: double-click setting 47.6 instead of clearing the pref) and 'detail hide' (red: select() clearing detail-hidden); 'hidden panes hide' gains #detail-restore (red: #detail-restore[hidden] removed); measure-web-ui 'room 1440 divider' (+200px for a 200px drag, kept across reload, double-click restores, not drawn at 1000; red: the drag not saved). Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified. Live 'divider' and 'detail hide' pass; measure 'room 1440 divider: resizes' (base: wrong): 605 to 805px, kept across a reload, double-click back to 605. In Chrome at 1440 the detail-hide control takes the list from 605 to 1251px, and the restore tab names the selected call. DoD2: base red, head green. Not verified by QA in real Chrome: the divider dragged by keyboard (jsdom covers it). DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
