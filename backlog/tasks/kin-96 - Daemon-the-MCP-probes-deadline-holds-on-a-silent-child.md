---
id: KIN-96
title: 'Daemon: the MCP probe''s deadline holds on a silent child'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 05:27'
labels:
  - 'item:mcp-probe-deadline-holds'
dependencies: []
ordinal: 96000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-22 by the maintainer on the product owner's proposal. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:mcp-probe-deadline-holds in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, 2026-10-22). Branch shafic/mcp-probe-deadline-holds from origin/main 84a1747.

Criteria (copied from ROADMAP item:mcp-probe-deadline-holds):
1. With a stub child that keeps stdout open and never writes, probe returns within its timeout plus a margin the test states, with an error that says the child did not answer in time.
2. The child is not alive once probe returns, read as McpProbeCleanupTest reads it (dead within its 5 s grace).
3. A silent mode beside answer, chatter and close-stdin, proven red with the fix removed; the red run fails on the test's own bound and does not hang the suite.
4. The three existing cleanup tests and McpControlTest's 'the real control either answers or explains why it cannot' stay green.

Build: in ProcessMcpControl.probe, read the child's stdout on a daemon thread into a queue and poll the queue with the time left until the deadline, so the deadline no longer waits on readLine. The finally's destroyForcibly then runs on time, and killing the child ends the reader thread's read. Timeout error text unchanged ('did not answer a tools/list within Nms').
Test: McpProbeStubChild gains SILENT (holds stdout open, writes nothing, ignores EOF). New test runs probe(timeoutMs = 1500) on its own daemon thread and waits at most timeout + stated margin (the probe's 2 s waitFor plus slack) via Future.get, so a red run fails with a timeout instead of hanging.
Red proof: restore the blocking readLine loop, see the new test fail on its bound; restore.
Touches only inspector-daemon/.../service/McpControl.kt, McpProbeCleanupTest.kt, and the daemon-and-mcp.md sentence naming the test paths if it needs the new one.
Checks: ./gradlew :inspector-daemon:test :repo-checks:test.
<!-- SECTION:NOTES:END -->
