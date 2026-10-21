---
id: KIN-75
title: >-
  Route stats: the accumulator, the cache, GET .../stats, MCP route_stats,
  inspector stats
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 16:25'
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
- [x] #1 GET /api/sessions/{id}/stats returns the §6.2 shape; 404/400 as listed; limit default 1000, max 5000; matched is 1203 on the 1203-call fixture session
- [x] #2 RouteStatsTest: retries vs redirects, rates over outcomes, transport failures out of latency, nearest-rank, p95/p99 withheld below 20 samples; each red as listed
- [x] #3 RouteStatsServiceTest: a fold equals a cold compute after appends and after a marker; an incomplete last line counted once
- [x] #4 MCP route_stats returns the service's report; errors are isError; mcp-tools-list.json regenerated
- [x] #5 inspector stats prints the report and honours --sort, --limit, --session
- [x] #6 Docs updated; hand-over states cold and warm timings and heap per entry on the 160k-call session
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR A, uncommitted. archive/IndexTail.kt (offset reader + shared decode; SessionRepository.readTransactions now calls it, -6 lines); service/RouteStats.kt (accumulator, report types, StatsSort; errors sorts by errorRate); service/RouteStatsService.kt (LRU 4, fold from offset, rebuild on shrink/fileKey/head bytes/markers size under a filter); http/StatsRoutes.kt (Dispatchers.IO); mcp/RouteStatsTool.kt; inspector stats in Main.kt; mcp-tools-list.json regenerated; McpProtocolTest tool set gains route_stats. Reds: interpolation p50 11; threshold 19 -> p95 19 shown; failures in samples -> latencySamples 2; 3xx set from filtered rows -> redirects 0 under status:200; offset past incomplete line -> 3 vs 4; markers size ignored -> matched 0 vs 2; head bytes dropped -> in-place rewrite stale; queryTransactions -> 1000 vs 1203 (REST and MCP); reworded description -> golden; CLI ignores --sort. Huge (160,016 rows, 33 MB): cold GET 0.24 s (fresh JVM), warm 2.5-3.4 ms, +100 rows refresh 2.7-3.5 ms, heap per entry ~17.8 MB (~114 B/attempt, jcmd GC.run + GC.heap_info), CLI 0.33 s wall. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR A, 0a5a1c9), Verified. AC1: sort=bogus -> 400 listing sorts, bad filter -> 400, unknown/../x -> 404; limit=0 -> 1 route, limit=999999 capped; 1203-call session total 1203, matched 1203 on REST and MCP. AC2: nearest rank on /users/{id} samples 1..20: p50 10, p90 18, p95 19, p99 20; /orders/{id} n=19: no p95Ms/p99Ms, tailWithheld n<20; transport failure counted (attempts 21, latencySamples 20, max 20, errorRate 0.0476); /pay calls 1 attempts 3 retries 2; /new redirects 1 retries 0, also under status:200. QA red: R1 floor rank -> 'expected <[10, 18, 19, 20, 20]> but was <[10, 18, 19, 19, 20]>'; R2 TAIL_MIN_SAMPLES=19 -> 'expected <null> but was <19>'; R3 failure in latency -> 'expected <1> but was <2>'. AC3: RouteStatsServiceTest in :inspector-daemon:test --rerun (357, 0 failed); live, 100 appended rows moved total 160116 -> 160216 -> 160316. AC4: MCP route_stats equals REST on the same session; unknown sort -> isError true. AC5: inspector stats --sort p50 --limit 4 --session <id> prints the report with sort p50, exit 0. AC6: huge session (160,016 rows, 32 MB): cold 0.331 s (budget 3 s), warm 2.4-3.8 ms, after +100 rows 2.4-2.8 ms (budget 50 ms), heap ~17.4 MB, ~111 B per attempt vs the note's ~100 (11% over), CLI 0.36 s. DoD2: QA repeated R1-R3 only; the other reds (3xx set from filtered rows, fold offset past an incomplete line, queryTransactions 1000 vs 1203, golden, CLI --sort) are the engineer's record, not repeated. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
