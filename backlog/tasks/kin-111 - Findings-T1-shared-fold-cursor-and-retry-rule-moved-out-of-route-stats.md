---
id: KIN-111
title: 'Findings T1: shared fold cursor and retry rule, moved out of route stats'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:findings-payment-safety'
dependencies: []
type: feature
ordinal: 111000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR A. Behaviour-preserving moves so findings reuse the mechanics instead of copying them: archive/IndexCursor.kt (offset, fileKey() and first 256 bytes as last folded; replaced(index); advance(index, onRow) through IndexTail.read) out of RouteStatsService, and service/RedirectHops.kt (the 3xx callId#attempt set; add, isRetry, isRedirect) out of RouteStatsAccumulator. Files: archive/IndexCursor.kt, service/RedirectHops.kt, service/RouteStats.kt, service/RouteStatsService.kt.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 RouteStatsTest, RouteStatsServiceTest, StatsRouteTest and RouteStatsToolTest pass with no edit to their files
- [x] #2 The offset-past-incomplete-line red proof, re-run against IndexCursor, fails
- [x] #3 The filtered-3xx red proof, re-run against RedirectHops, fails
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA. Merged in PR #151 (merge 428745a). IndexCursor (archive/IndexCursor.kt) and RedirectHops (service/RedirectHops.kt) moved out of route stats; RouteStatsTest, RouteStatsServiceTest, StatsRouteTest, RouteStatsToolTest not edited (git diff --stat 1ed50c1..428745a shows none of them). Red: IndexCursor.advance moving the offset to the file size fails RouteStatsServiceTest 'a line written without its newline waits' (expected 4 but was 3); redirectHops.add moved after the filter fails RouteStatsTest 'a hop after a 3xx is a redirect ... under status 200' (status:200 expected 1 but was 0); RedirectHops.isRedirect returning false fails the same test. — bob
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #151 merge 428745a, detached copies, JDK 21; item stays open (T4 onward). AC1: git diff --name-only 1ed50c1 428745a lists RouteStats.kt and RouteStatsService.kt only; RouteStatsTest, RouteStatsServiceTest, StatsRouteTest, RouteStatsToolTest unchanged and passing. AC2 re-run: advance setting offset to the file size fails RouteStatsServiceTest 'a line written without its newline waits ... expected: <4> but was: <3>', the only failure of 52. AC3 re-run: isRedirect returning false fails RouteStatsTest 'a hop after a 3xx is a redirect', StatsRouteTest's shape test, and PaymentRulesTest's 3xx test, showing the rule is shared. :inspector-daemon:test --rerun 453 tests, 0 failed; :repo-checks:test --rerun 36/0. Verdict: close yes, no blocking findings.
<!-- SECTION:FINAL_SUMMARY:END -->
