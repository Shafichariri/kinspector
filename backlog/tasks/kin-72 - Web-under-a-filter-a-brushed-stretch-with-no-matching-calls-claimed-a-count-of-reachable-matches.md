---
id: KIN-72
title: >-
  Web: under a filter, a brushed stretch with no matching calls claimed a count
  of reachable matches
status: Done
assignee: []
created_date: '2026-10-21 10:49'
updated_date: '2026-10-21 10:50'
labels:
  - 'item:web-list-says-what-it-holds'
dependencies: []
type: bug
ordinal: 72000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA round 1 on PR #104 (merge fb8e54e). Reproduction: a 3016-call session whose oldest 1000 calls are /v3/shop/stock, then 1200 /v3/shop/bulk. Filter path:/v3/shop/bulk, brush the oldest 500 calls. The list read '500 calls in this stretch are older than the newest 1000 of 1200 matches the list loaded - narrow the filter to reach them' while 0 calls there match. Cause: olderThanLoaded counted the unfiltered whole-session copy. A regression this item introduced. Fixed in PR #110 (ff4f9de); closed on QA round 2.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 A brushed stretch the list holds no calls for, while the stretch holds calls, never reads 'no calls in the selected stretch' and, under a filter, claims no count of matches in the stretch
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Fixed in PR #110 (merge ff4f9de); QA round 2 Verified. Chrome on a 3016-call session, filter path:/v3/shop/bulk, oldest 500 calls (all non-matching) brushed: the list reads 'none of the newest 1000 of 1200 matches the list loaded is in this stretch, and the 200 older ones are not loaded - narrow the filter to reach any here'; it claims no count of matches in the stretch and no longer contradicts the axis's '0 calls'. No filter: oldest 151 read '151 calls in this stretch are older than the newest 1000 the list loaded - filter to reach them'. Static 'stretch older than loaded' now covers 5 stretches. Red (QA's own mutation, filter branch of olderThanLoaded disabled): 'NO - 151 calls in this stretch are older ... where none of the newest 1000 of 1200 matches ... was due'.
<!-- SECTION:FINAL_SUMMARY:END -->
