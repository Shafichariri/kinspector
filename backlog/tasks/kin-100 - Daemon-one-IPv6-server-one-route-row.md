---
id: KIN-100
title: 'Daemon: one IPv6 server, one route row'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:route-key-one-ipv6-host'
dependencies: []
ordinal: 100000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-22 by the maintainer on the product owner's proposal. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:route-key-one-ipv6-host in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, 2026-10-22).

Criteria (roadmap item:route-key-one-ipv6-host):
1. Three OkHttp calls to ::1:8080 and three Ktor calls to [::1]:8080 on one path draw one route row with six calls on the Stats page, at every width; the Markdown routes table and MCP route_stats agree.
2. That row's filter term opens all six: host: is a substring match, so the bare literal finds both stored forms; grammar unchanged.
3. Two ports, or a host that is not an IPv6 literal, key exactly as today.
4. A daemon test with both stored forms, red with the key's host left as stored.

Fix: RouteTemplates.keyOf, the one place the key is built, strips the brackets of a stored IPv6 literal ([::1] -> ::1) so both capture paths key alike. Bare is the canonical form because it is the one host: term that finds both stored forms (substring). A host without ':' or not bracketed is untouched (lowercased as today). Capture unchanged (capture.md: bracket on read). Row host is bare; the page (statsOrigin via hostPortOf) and the Markdown cell (NetworkTransaction.hostPort) bracket it on read, so they draw [::1]:8080 as now.

Web: 95-stats.js does no grouping of its own (counts origins through hostPortOf, which already makes ::1 and [::1] one origin), so no web change and no mirror case (the route rule is daemon-only, daemon-and-mcp.md).

Tests: RouteTemplatesTest (key equality both forms, two ports differ, non-IPv6 unchanged), RouteFilterTermTest (term matches both stored forms), RouteStatsServiceTest/StatsRouteTest (REST: one row, 6 attempts), MCP route_stats, Markdown export routes table: one row. Red: keyOf with host left as stored.

Files: inspector-daemon/.../archive/RouteTemplates.kt, daemon tests, docs/decisions/daemon-and-mcp.md (route key paragraph), capture.md last sentence on host:.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #142 merge 1ed50c1, real daemons plus Chrome, a session seeded through /ingest with 3 x ::1 and 3 x [::1] on port 8080. One route row, six calls: GET .../stats 'host=::1 port=8080 /v6 calls=6 attempts=6' (base two rows of 3); MCP route_stats via real 'inspector mcp' the same; Markdown '| GET | [::1]:8080 | /v6 | 6 | 6 | 0 | 1 (16.7%)'; Stats page in Chrome at 1440 and 400, 4 rows with exactly one naming [::1]:8080. Filter term 'method:GET host:::1 path:*/v6' opens 7 calls (the six plus the port-9090 superset) in REST and in Chrome. A bracketed non-IPv6 host and a second port key as before. Red re-run by QA: keyOf on the stored host fails 5 of 61 (one per surface); dropping the ':' condition fails RouteTemplatesTest 'expected: <[example.invalid]> but was: <example.invalid>'. :inspector-daemon:test --rerun plus :repo-checks:test --rerun 368 tests, 0 failed. Verdict: close yes.
<!-- SECTION:FINAL_SUMMARY:END -->
