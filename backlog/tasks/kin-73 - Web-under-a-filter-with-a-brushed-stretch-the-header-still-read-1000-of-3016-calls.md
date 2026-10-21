---
id: KIN-73
title: >-
  Web: under a filter with a brushed stretch, the header still read '1000 of
  3016 calls'
status: Done
assignee: []
created_date: '2026-10-21 10:49'
updated_date: '2026-10-21 10:50'
labels:
  - 'item:web-list-says-what-it-holds'
dependencies: []
type: bug
ordinal: 73000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA round 1 on PR #104 (merge fb8e54e). Reproduction: same session and filter, with a stretch brushed over the whole session: header read '1000 of 3016 calls' (partial stretches read 'N of 3016 calls'). The new branch was guarded by !state.timeRange. Fixed in PR #110 (ff4f9de); closed on QA round 2.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Under a filter that matches more than the list draws, the header numbers the matches and never reads '1000 of 3016 calls', with or without a brushed stretch
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Fixed in PR #110 (merge ff4f9de); QA round 2 Verified. Chrome, filter path:/v3/shop/bulk on a 3016-call session: stretch over the whole session reads '1000 of the newest 1000 of 1200 matches shown'; newest part '400 of the newest 1000 of 1200 matches shown'; no-match stretch '0 of the newest ...'; mixed '40 of the newest ...'; no '... of 3016 calls' under the filter in any stretch. Live 'long filtered count' passes. Red (QA's mutation, old !state.timeRange guard put back): static 'filtered count: NO - "1000 of 3016 calls"'. At 1000 px the longer header stays on one line and overlaps no control.
<!-- SECTION:FINAL_SUMMARY:END -->
