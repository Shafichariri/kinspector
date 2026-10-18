---
id: KIN-33
title: >-
  Web bug: the timeline's filter note covers the sticky prefix bar, and a
  station click lands under the path's sticky box
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 33000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Regressions outside the ACs, found by the second verify panel (Simon, Doug), head e1711a3, measured in headless Chrome. (B2) Timeline: '#timeline > .filter-note' (35-list.css:191, z-index 2) sits on the scope bar at the same y, so rows show stripped paths with no bar, breaking shared-rules.md's sticky-bar rule. Root cause predates the diff: #timeline is height:100% beside the bar, and live follow scrolls #list-pane (70-timeline.js:122); this also hid the first 34px of the timeline before. (S5) Path view: scroll-margin-top: 96px (20-path.css:119) does not count the note, so a clicked station lands 27px (1440) or 41px (400) under the sticky box. Measurement scripts: $TMPDIR/simon2/tools/{scope,interact,tl}.js and $TMPDIR/doug2/h/scopetop.js. Screenshots: $TMPDIR/simon2/shots/served-8272/1440-path-all.png against served-8271/...
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 In headless Chrome at 1440 and 400, under a filter, the element on top at the scope bar's centre is the scope bar
- [ ] #2 The timeline's first row is fully visible, with and without a filter
- [ ] #3 A clicked station's row head is below the sticky box at both widths, with and without a filter
- [ ] #4 Each is checked by a committed measurement or a documented manual check recorded in the notes, and each is red against e1711a3
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
