---
id: KIN-137
title: >-
  Session diff T2: the rules, the service, GET /api/diff, MCP diff_sessions and
  inspector diff
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:25'
updated_date: '2026-10-22 19:44'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-136
  - KIN-135
modified_files:
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/SessionDiffSide.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/SessionDiff.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/SessionDiffService.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/DiffRoutes.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/InspectorDaemon.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/DiffTool.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/McpTools.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/ToolGroup.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/Main.kt
  - inspector-daemon/src/test/resources/mcp-tools-list.json
  - docs/DAEMON.md
  - docs/decisions/daemon-and-mcp.md
type: feature
ordinal: 137000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR A (lane D), after T1. Design: session-diff note §3 to §8, §12 T2, §14 (the three daemon-and-mcp.md paragraphs) and §15 (DAEMON.md). Routes are compared, never calls; per route and side the route stats row is reused; shape comes only from complete, strictly parsed JSON bodies. Decided 2026-10-22 by the maintainer's proxy: a key is removed only when every JSON body on the other side was read complete, nothing at or under a redacted path is compared, and different hosts never match.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /api/diff returns the design's §7.1 shape; every case of §7.2's table answers with its status and text; limit and unchanged cut after the order while counts and routeCount stay whole
- [ ] #2 B defaults to latest and A to the newest earlier session of B's app, a tie on startedAt broken by id; aChosen says which
- [ ] #3 Never removed from unknown: a key absent from B's complete samples while B holds a truncated, omitted, missing or unparsed JSON body on that route is in unsure, never removed (DiffShapeTest, red with a lenient prefix walk and with the all-read condition dropped)
- [ ] #4 Nothing at or under a path in redacted[] on either side is compared; a key under an empty array is not removed
- [ ] #5 Latency is judged on p50 only at n >= 5 on both sides with a change of >= 50 ms and >= 25%; otherwise same or notJudged with reason n<5 (SessionDiffTest, red with > for >= and with n >= 4)
- [ ] #6 A mutation added, removed or with a changed call count is loud and sorts first
- [ ] #7 No header value and no body value appears anywhere in the reply
- [ ] #8 On the 1203-row long session against the probe session, /v3/long/bulk is removed with 1201 calls, on REST, MCP and the CLI (red through queryTransactions)
- [ ] #9 A second identical read is served from the cache; an appended row or marker recomputes (red with the stamp check dropped)
- [ ] #10 diff_sessions returns the service's report for the same arguments (unchanged off, limit 50 by default); errors are isError results with the REST messages; mcp-tools-list.json regenerated
- [ ] #11 inspector diff honours --a, --b, --since-a, --since-b, --limit, --unchanged and --md
- [ ] #12 The three daemon-and-mcp.md paragraphs of the design's §14 are in; docs/DAEMON.md §7 has the subsection and the CLI line of §15; the hand-over states the §8 timings
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
