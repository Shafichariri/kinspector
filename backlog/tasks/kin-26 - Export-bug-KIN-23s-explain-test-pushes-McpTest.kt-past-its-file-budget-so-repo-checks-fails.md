---
id: KIN-26
title: >-
  Export bug: KIN-23's explain test pushes McpTest.kt past its file budget, so
  :repo-checks fails
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 19:28'
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
- [ ] #1 explain_reports_the_full_url_with_port_and_query moves, unchanged, into a new test file inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/ExplainToolTest.kt (or the package the MCP tests' helpers allow), and still passes
- [ ] #2 McpTest.kt is at or under 815 lines and no budget in structure/budgets.txt is raised
- [ ] #3 ./gradlew :repo-checks:test --rerun and ./gradlew :inspector-daemon:test --rerun are both green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
