---
id: KIN-17
title: >-
  Phase F bug: static check 3 denylist misses browser built-ins the page calls
  (Map, atob, encodeURIComponent)
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-11
type: bug
ordinal: 17000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (architect S2, confirmed by QA). 'function Map(){}', 'function atob(){}' and 'const encodeURIComponent = 1' all pass static check 3, yet the page calls Map 21x, encodeURIComponent 17x and atob 1x; redeclaring one in a classic script shadows the built-in for every later file. The comment at scripts/lib/web-audits.js:298 over-claims the denylist.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Check 3 also rejects any name in Object.getOwnPropertyNames(globalThis) as seen by Node at check time, in addition to the named list
- [ ] #2 Each of 'function Map(){}', 'function atob(){}' and 'const encodeURIComponent = 1' is proven red, then removed and green
- [ ] #3 The comment at web-audits.js:298 is made accurate
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
