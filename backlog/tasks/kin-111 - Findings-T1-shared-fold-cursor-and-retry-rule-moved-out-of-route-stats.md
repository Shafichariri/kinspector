---
id: KIN-111
title: 'Findings T1: shared fold cursor and retry rule, moved out of route stats'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 08:24'
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
- [ ] #1 RouteStatsTest, RouteStatsServiceTest, StatsRouteTest and RouteStatsToolTest pass with no edit to their files
- [ ] #2 The offset-past-incomplete-line red proof, re-run against IndexCursor, fails
- [ ] #3 The filtered-3xx red proof, re-run against RedirectHops, fails
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
