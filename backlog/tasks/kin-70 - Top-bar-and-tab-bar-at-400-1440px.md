---
id: KIN-70
title: Top bar and tab bar at 400-1440px
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
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
- [x] #1 Top bar scrollWidth <= window at 400 and every control reachable by pointer and keyboard
- [x] #2 The whole count, filtered too, inside the window at 400
- [x] #3 Start time visible with the picker closed and truncated; picker label whole or ellipsized with tooltip at 1440
- [x] #4 Every tab reachable at 400
- [x] #5 narrow top bar css, narrow tabs css, tabs menu, picker face, shell and topbar readings green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Picker: native select transparent over .picker-face (time first, flex none; the rest ellipsized; tooltip = option text), flex 0 1 520px / min 200px, 150px below 720px. Top bar below 720px: two lines, count alone on line 2, brand hidden, connection word kept as aria-label. #export-host, #live-btn and .daemon-group no longer shrink (export ▾ broke over two lines at 1000px). Tab bar below 700px: active tab and views ▾ (menuButton, built from availableViews with counts).
Chrome (measure-web-ui.js --only topbar,shell): shell <w> topbar fits / controls / picker time / tabs reachable / rail / page <name> fits at 1440, 1000, 600, 400, and shell back; topbar long 975/800/600/400 count and topbar big <w> filtered count ("newest 1000 of 3015 matches shown"). Static: narrow top bar css, narrow tabs css. Live: picker face, tabs menu.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR B, a7e3946, qa/web-foundation-shell-report.md), Chrome 131 at 1440, 1000, 600 and 400 on Traffic, Sessions, Processes and Settings. AC1: #topbar.scrollWidth equals the window at every width; every top-bar control is hit by elementFromPoint, 'shell ... controls: pointer+keyboard' passes at all four widths. AC2: count whole on one line (countsWhole true, height 15), including 'newest 1000 of 1201 matches shown' at 400; measure 'topbar long 400 count' and 'topbar big 400 filtered count' went from 'wrapped or clipped' at base to 'one line, inside the bar'. AC3: .picker-time whole and on screen at every width ('11:00 31 Dec'); ellipsis with the whole option text as tooltip below 1440. AC4: below 700 px the tab bar shows the active tab plus 'views', the menu lists every tab, 9 of 9 on the nine-view session. AC5: narrow top bar css, narrow tabs css, tabs menu and picker face pass; measure-web-ui 229 readings, 2 failed (first rows huge, connected huge: KIN-42, failing at base too). Not run: the 160k-call 'first' section.
<!-- SECTION:FINAL_SUMMARY:END -->
