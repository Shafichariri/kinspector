---
id: KIN-70
title: Top bar and tab bar at 400-1440px
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:03'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-66
ordinal: 70000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Top bar scrollWidth <= window at 400 and every control reachable by pointer and keyboard
- [ ] #2 The whole count, filtered too, inside the window at 400
- [ ] #3 Start time visible with the picker closed and truncated; picker label whole or ellipsized with tooltip at 1440
- [ ] #4 Every tab reachable at 400
- [ ] #5 narrow top bar css, narrow tabs css, tabs menu, picker face, shell and topbar readings green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Picker: native select transparent over .picker-face (time first, flex none; the rest ellipsized; tooltip = option text), flex 0 1 520px / min 200px, 150px below 720px. Top bar below 720px: two lines, count alone on line 2, brand hidden, connection word kept as aria-label. #export-host, #live-btn and .daemon-group no longer shrink (export ▾ broke over two lines at 1000px). Tab bar below 700px: active tab and views ▾ (menuButton, built from availableViews with counts).
Chrome (measure-web-ui.js --only topbar,shell): shell <w> topbar fits / controls / picker time / tabs reachable / rail / page <name> fits at 1440, 1000, 600, 400, and shell back; topbar long 975/800/600/400 count and topbar big <w> filtered count ("newest 1000 of 3015 matches shown"). Static: narrow top bar css, narrow tabs css. Live: picker face, tabs menu.
<!-- SECTION:NOTES:END -->
