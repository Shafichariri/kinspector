---
id: KIN-142
title: A brushed stretch under a filter says older matches may be in it
status: To Do
assignee: []
created_date: '2026-10-22 19:26'
labels:
  - 'item:web-stretch-says-older-matches'
dependencies:
  - KIN-102
  - KIN-103
  - KIN-104
  - KIN-105
  - KIN-106
  - KIN-107
  - KIN-108
type: bug
ordinal: 142000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 29, docs/ROADMAP.md §3 → Bugs found, not fixed (lane W, after polish PR A, KIN-102 to KIN-108). From QA on item:web-list-says-what-it-holds (its mixed stretch note) and docs/STATUS.md → Not verified. On a long session, with a filter matching more calls than the list loads, a brushed stretch reaching below the oldest match loaded draws the loaded matches and says at least 10 calls hidden by the filter, and nothing says older matches may also be in it, so the list reads as all the matches in the stretch. Out of scope: a daemon route that counts the matches in a range (if the sentence cannot be true without one, the item goes back to the product owner); the header's wording. Web UI only. Size S.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 In that case the list's note also says that the stretch reaches below the oldest of the N matches loaded, so older matches in it are not drawn, and that narrowing the filter reaches them
- [ ] #2 It claims no count of those matches: the page evaluates no filter (web-ui.md, Under a filter it claims no count)
- [ ] #3 A stretch wholly above the oldest match loaded reads as it does today
- [ ] #4 A line in check-web-static.js over the 3016-call stub and one in the live probe over the long session, each proven red with the sentence removed
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
