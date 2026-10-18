---
id: KIN-38
title: 'Web bug: expanding a timeline run under live tail throws the pane to the end'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 38000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. Introduced by KIN-33. On a 3016-call session with live on: oldest-first, scrollTop goes 24 -> 29934; newest-first, 29800 -> 0; similar at 400. e1711a3 moved at most 34px. A plain row click does not move it. Cause: the follow in 70-timeline.js:121-125 now scrolls #list-pane on every re-render. Harness: $TMPDIR/doug3/tools/runjump.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With live on, expanding or collapsing a run leaves scrollTop unchanged in both orders at 1440 and 400
- [ ] #2 A newly arriving row is still followed when the pane was already at the end it follows, as logAtEnd does in 50-path.js
- [ ] #3 A check that is red against a86dde2: a jsdom probe if it can see it, otherwise a documented Chrome check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
