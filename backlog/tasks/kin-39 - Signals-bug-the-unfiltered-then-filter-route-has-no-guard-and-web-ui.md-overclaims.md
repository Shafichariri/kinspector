---
id: KIN-39
title: >-
  Signals bug: the unfiltered-then-filter route has no guard, and web-ui.md
  overclaims
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
updated_date: '2026-10-18 22:45'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 39000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking; fails KIN-24 AC6. Reverting 15-session-data.js:294 to 'if (!filter)' leaves static, live and archive all green, yet load-unfiltered-then-type-a-filter makes the path read 'nothing recorded' over visits with calls. checkPastTheClamp tests only filter-first. web-ui.md:161-162 says the copy costs 'nothing at all when no filter is set', false since KIN-34. web-ui.md:191 says the copies 'go stale in three ways, and each is closed'; the paragraph lists five and one is open (KIN-40).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 An unfiltered-then-filter check, red with 'if (!filter)', green now
- [ ] #2 web-ui.md:161-162 and :191 are true, with no completeness claim
- [ ] #3 web-ui.md states the '0 of N match' limit until KIN-40 lands, or drops the statement if KIN-40 fixes it
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
