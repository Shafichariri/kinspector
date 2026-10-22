---
id: KIN-142
title: A brushed stretch under a filter says older matches may be in it
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:26'
updated_date: '2026-10-22 21:17'
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

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Factor the 'stretch reaches below the oldest call the list loaded' computation out of olderThanLoaded into belowLoaded() in js/15-session-data.js (25-traffic-list.js is at 587 of a 589 cap), so the empty text and the note read one rule.
2. olderMatchesNote() beside it: under a filter past the ceiling, a stretch that draws loaded matches and reaches below the oldest of them (with the copy showing calls there) gets one sentence in the list note: this stretch reaches below the oldest of the N matches loaded, so any older matches in it are not drawn - narrow the filter to reach them. No count of those matches (criterion 2). A stretch the list draws none of keeps the empty text's sentence; a stretch wholly above the oldest loaded match reads as today (criterion 3).
3. filterNote takes the list's extra clause before 'clear filter'; renderListNote passes it.
4. Static line 'stretch below loaded matches' in scripts/lib/list-holds-checks.js over the 3016-call stub (mixed stretch has the sentence, a wholly-above stretch reads today's exact text); live line 'long stretch below matches' in probeCapped over the 1203-call session. Each proven red with the sentence removed; Pace neighbour 'long list note' re-proven red. ci.yml UI_EXPECT_CHECKS 304 -> 305.
5. web-ui.md: the stretch paragraph gains the rule.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on shafic/web-stretch-says-older-matches (base 7f49e14), uncommitted; ready for QA.

Sentence (list note, under a filter past the ceiling, a stretch that draws loaded matches and reaches below the oldest of them, with the copy showing calls there): 'this stretch reaches below the oldest of the 1000 matches loaded, so any older matches in it are not drawn — narrow the filter to reach them', placed before 'clear filter'. No count of the older matches. A stretch wholly above the oldest loaded match reads as before; a stretch the list draws none of keeps the empty text's sentence and the note gains nothing.

Code: belowLoaded() and olderMatchesNote() in js/15-session-data.js (448 lines); olderThanLoaded now reads belowLoaded; filterNote takes an optional clause (js/25-traffic-list.js 587 -> 582).
Guards: static 'stretch below loaded matches' (scripts/lib/list-holds-checks.js), live 'long stretch below matches' (scripts/lib/long-session-probes.js probeCapped). Counts: static 134 -> 135, live 304 -> 305 (ci.yml UI_EXPECT_CHECKS), archive 59 unchanged.
Red: static with the sentence removed gave the base text 'at least 50 calls hidden … — newest 1000 of 1200 matches loaded — clear filter'; with the reaches-below rule ignored the wholly-above stretch failed. Live with the sentence removed plus Pace neighbour 'long stretch older' broken: 303 passed, 2 failed (exactly those two).
Green: static 135/0; CI web replay on 8105 305 passed, archive 59; measure-web-ui --only layout,room,first vs 7f49e14: moved nothing, 153/153 readings 0 failed both; :repo-checks:test --rerun 38 tests pass. Looked in Chrome at 1440 (3 lines) and 400 (5 lines): sticky under the scope bar, no overflow.
<!-- SECTION:NOTES:END -->
