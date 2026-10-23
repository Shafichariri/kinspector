---
id: KIN-137
title: >-
  Session diff T2: the rules, the service, GET /api/diff, MCP diff_sessions and
  inspector diff
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 19:25'
updated_date: '2026-10-23 07:23'
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
- [x] #1 GET /api/diff returns the design's §7.1 shape; every case of §7.2's table answers with its status and text; limit and unchanged cut after the order while counts and routeCount stay whole
- [x] #2 B defaults to latest and A to the newest earlier session of B's app, a tie on startedAt broken by id; aChosen says which
- [x] #3 Never removed from unknown: a key absent from B's complete samples while B holds a truncated, omitted, missing or unparsed JSON body on that route is in unsure, never removed (DiffShapeTest, red with a lenient prefix walk and with the all-read condition dropped)
- [x] #4 Nothing at or under a path in redacted[] on either side is compared; a key under an empty array is not removed
- [x] #5 Latency is judged on p50 only at n >= 5 on both sides with a change of >= 50 ms and >= 25%; otherwise same or notJudged with reason n<5 (SessionDiffTest, red with > for >= and with n >= 4)
- [x] #6 A mutation added, removed or with a changed call count is loud and sorts first
- [x] #7 No header value and no body value appears anywhere in the reply
- [x] #8 On the 1203-row long session against the probe session, /v3/long/bulk is removed with 1201 calls, on REST, MCP and the CLI (red through queryTransactions)
- [x] #9 A second identical read is served from the cache; an appended row or marker recomputes (red with the stamp check dropped)
- [x] #10 diff_sessions returns the service's report for the same arguments (unchanged off, limit 50 by default); errors are isError results with the REST messages; mcp-tools-list.json regenerated
- [x] #11 inspector diff honours --a, --b, --since-a, --since-b, --limit, --unchanged and --md
- [x] #12 The three daemon-and-mcp.md paragraphs of the design's §14 are in; docs/DAEMON.md §7 has the subsection and the CLI line of §15; the hand-over states the §8 timings
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once PR A merges. service/SessionDiff.kt (types, route rules, order, caps), SessionDiffShape.kt (shape rule), SessionDiffSide.kt (per-side fold, caps, strict JSON), SessionDiffService.kt (A/B choice, since, cache), http/DiffRoutes.kt, mcp/DiffTool.kt, ToolGroup.boolean, Main.kt diff. Deviations, each reported in the hand-over: (1) strict JSON also checks literals, since kotlinx's default parser accepts NaN, 01 and bare words; (2) response-body tallies and response headers count only attempts with a status, so a transport failure is not an empty body; (3) aChosen is set per reply, not cached (a bug the tests caught: a named A and a found A share an entry); (4) the Redactor placeholder moved to JsonPaths.PLACEHOLDER, which SensitiveSource reads; (5) the order rule ranks added above statusMix, so on base vs probe the loud added DELETE /v3/shop/cart/items sorts before POST orders/submit, and design §12's live line 'diff mutation loud (first row is POST …/orders/submit)' cannot hold as worded; PR B's probe should find the orders row instead. Red proofs (each run, seen failing, restored): > for >= at 50 ms; n >= 4; calls as attempts; a header value kept; a truncated prefix walked leniently; known(B) dropped; observable dropped; redacted exclusion dropped; unread as known; rows through queryTransactions (999 of 1201 on service, REST, MCP and CLI); stamp check dropped; since pasted into filter text; NotFound as 400; --since-b ignored; a reworded description (golden). Timings in the hand-over. -- bob

Closed 2026-10-23 by the delivery tracker. PR #180, merge f59f3d4. QA report: qa/session-diff-pr-a-report.md (run folder of 2026-10-20), verdict close - yes.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug) on f59f3d4: daemon 582 tests 0 failed, repo-checks 38/0, check-web-static 133/0, live probe 299/0, archive 58/0; 15 red proofs re-run. GET /api/diff, diff_sessions, inspector diff: base against probe gives 14 routes (7 added, 1 removed, 4 changed, 2 unchanged); 1201-call long-session removal on REST, MCP and CLI, red through queryTransactions (M5); never removed from unknown red (M3); latency reds M1 and M2; cache red M6; no header or body value in any reply (M7). Not exercised: MCP limit cap of 200 live, a startedAt tie live (both unit-tested), the 160k and 20k timings. Merge f59f3d4 (#180). QA report session-diff-pr-a-report.md.
<!-- SECTION:FINAL_SUMMARY:END -->
