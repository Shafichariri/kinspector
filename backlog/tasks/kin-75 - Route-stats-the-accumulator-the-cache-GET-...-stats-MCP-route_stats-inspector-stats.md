---
id: KIN-75
title: >-
  Route stats: the accumulator, the cache, GET .../stats, MCP route_stats,
  inspector stats
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 12:39'
labels:
  - 'item:endpoint-latency-stats'
dependencies:
  - KIN-74
ordinal: 75000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:endpoint-latency-stats (run of 2026-10-20), §13. PR A.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /api/sessions/{id}/stats returns the §6.2 shape; 404/400 as listed; limit default 1000, max 5000; matched is 1203 on the 1203-call fixture session
- [ ] #2 RouteStatsTest: retries vs redirects, rates over outcomes, transport failures out of latency, nearest-rank, p95/p99 withheld below 20 samples; each red as listed
- [ ] #3 RouteStatsServiceTest: a fold equals a cold compute after appends and after a marker; an incomplete last line counted once
- [ ] #4 MCP route_stats returns the service's report; errors are isError; mcp-tools-list.json regenerated
- [ ] #5 inspector stats prints the report and honours --sort, --limit, --session
- [ ] #6 Docs updated; hand-over states cold and warm timings and heap per entry on the 160k-call session
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR A, uncommitted. archive/IndexTail.kt (offset reader + shared decode; SessionRepository.readTransactions now calls it, -6 lines); service/RouteStats.kt (accumulator, report types, StatsSort; errors sorts by errorRate); service/RouteStatsService.kt (LRU 4, fold from offset, rebuild on shrink/fileKey/head bytes/markers size under a filter); http/StatsRoutes.kt (Dispatchers.IO); mcp/RouteStatsTool.kt; inspector stats in Main.kt; mcp-tools-list.json regenerated; McpProtocolTest tool set gains route_stats. Reds: interpolation p50 11; threshold 19 -> p95 19 shown; failures in samples -> latencySamples 2; 3xx set from filtered rows -> redirects 0 under status:200; offset past incomplete line -> 3 vs 4; markers size ignored -> matched 0 vs 2; head bytes dropped -> in-place rewrite stale; queryTransactions -> 1000 vs 1203 (REST and MCP); reworded description -> golden; CLI ignores --sort. Huge (160,016 rows, 33 MB): cold GET 0.24 s (fresh JVM), warm 2.5-3.4 ms, +100 rows refresh 2.7-3.5 ms, heap per entry ~17.8 MB (~114 B/attempt, jcmd GC.run + GC.heap_info), CLI 0.33 s wall. Ready for QA.
<!-- SECTION:NOTES:END -->
