---
id: KIN-28
title: 'Signals bug: empty states under a filter say nothing happened'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 28000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Simon) on KIN-24 AC6, head 5f6b610. Blocking. docs/decisions/web-ui.md falsely claims the waterfall says 'no matching calls'. Three empty states, all reached under a filter: (1) Waterfall: when the filter leaves no calls it shows the static 'no calls to lay out' from index.html:199 beside 'calls 16'. (2) Path view: a visit emptied by the filter says 'nothing' (50-path.js:227) and 'nothing recorded' (:289) next to '0 matching' (:302); on the fixture that is 4 of 6 visits. (3) Timeline: an empty filtered pane shows a bare 'no entries', and a marker in range, which ignores the filter, hides even that; signals the filter thinned out are dropped with no note. Screenshots: $TMPDIR/simon-sig/shots/served-8262/ (1440-nomatch-path, 400-nomatch-path, 1440-nomatch-all) and served-8262-wf/1440-tagscreen-waterfall.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Each pane (waterfall, path, timeline), when emptied or thinned by a filter, says so, names the filter, and offers 'clear filter' as the traffic list does
- [ ] #2 'nothing recorded' appears only when nothing was recorded; under a filter the path view reads '0 of N match', with N from the unfiltered session
- [ ] #3 The timeline decides it is empty by counting calls and signals, not markers, and when the filter hid signals it says how many (for example '19 signals hidden by the filter')
- [ ] #4 web-ui.md's false sentence about the waterfall is corrected
- [ ] #5 render-web-ui.js checks each case, proven red then green
- [ ] #6 The tab bar still never changes under a filter
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
