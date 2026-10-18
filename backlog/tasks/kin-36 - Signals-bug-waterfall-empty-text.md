---
id: KIN-36
title: 'Signals bug: waterfall empty text'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 36000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Simon, Doug), head e1711a3. (S2) A brushed stretch plus a filter says 'no calls were recorded in this session' (45-visits.js:207-210); this is Doug's scale-free repro of KIN-28 AC1. (S3) tag:screen says 'nothing matches the filter' though 6 screens matched; it should say 'no calls match'. (S6) 20-navigation.js:57 repeats the removed false claim. Nits: (a) renderListEmpty's doc comment is orphaned (25-traffic-list.js:214-224). (b) web-ui.md:166-168 should name hiddenByFilter as a third reader of state.signals.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The text order is: a filter with hidden calls gives the filter note; then a time range with calls in the session gives the stretch text; then 'nothing recorded' appears only when the session has no calls
- [ ] #2 The tag:screen text is 'no calls match'
- [ ] #3 The comment at 20-navigation.js:57 is true
- [ ] #4 Each case is in the render probe, red then green
- [ ] #5 Nit: renderListEmpty's orphaned doc comment (25-traffic-list.js:214-224) is reattached or removed
- [ ] #6 Nit: web-ui.md:166-168 names hiddenByFilter as a third reader of state.signals
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
