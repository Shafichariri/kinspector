---
id: KIN-26
title: >-
  Export bug: KIN-23's explain test pushes McpTest.kt past its file budget, so
  :repo-checks fails
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 19:28'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:export-md-query-once'
dependencies:
  - KIN-23
type: bug
ordinal: 26000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the architect while designing the signals item, confirmed by the conductor. inspector-daemon/src/test/kotlin/dev/inspector/daemon/McpTest.kt is 830 lines against a structure/budgets.txt budget of 815. ./gradlew :repo-checks:test gives '35 tests completed, 1 failed' (FileBudgetTest). Commit 2b1b650 added the 15 lines. Both the engineer and QA ran only :inspector-daemon:test, so nothing saw it.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 explain_reports_the_full_url_with_port_and_query moves, unchanged, into a new test file inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/ExplainToolTest.kt (or the package the MCP tests' helpers allow), and still passes
- [x] #2 McpTest.kt is at or under 815 lines and no budget in structure/budgets.txt is raised
- [x] #3 ./gradlew :repo-checks:test --rerun and ./gradlew :inspector-daemon:test --rerun are both green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Commit 1e9fb3a. Test moved unchanged to inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/ExplainToolTest.kt (package dev.inspector.daemon.mcp). Shared helpers mcpExchange/mcpCallTool/mcpSession are internal in McpTestSupport.kt; McpTest's private exchange/callTool/session now delegate to them. McpTest.kt 830 -> 784; budgets.txt entry tightened 815 -> 784 by -Dinspector.budgets.tighten=true (the ratchet fails a file below its budget). ExplainToolTest uses refusing MarkerPoster/SignalPuller fakes so it can never reach a daemon on 8099. Moved test proven to run: changing its id argument to port9999 failed it, restored.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Verified by QA earlier; re-confirmed at 8f64058: :repo-checks:test --rerun 35/0, :inspector-daemon:test --rerun 267/0 (AC3). Tracker checked McpTest.kt is 784 lines (AC2, under 815; budget tightened to 784 by the ratchet, none raised) and ExplainToolTest.kt holds the moved test (AC1). Follow-up to KIN-23, which was closed already. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
