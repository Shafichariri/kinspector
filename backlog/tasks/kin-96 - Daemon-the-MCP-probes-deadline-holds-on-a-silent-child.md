---
id: KIN-96
title: 'Daemon: the MCP probe''s deadline holds on a silent child'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 06:42'
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
- [x] #1 Every acceptance criterion of the roadmap item labelled item:mcp-probe-deadline-holds in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
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

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #138 merge ffc9278, round 1, no failures. `a probe whose child holds its output open and never writes returns within its timeout` passes in 3.514 s (1.5 s timeout plus the probe's 2 s waitFor; bound 6.5 s), asserts ok == false, an error containing `did not answer a tools/list within 1500ms`, at least 1500 ms elapsed, and assertStopped(child). Red, re-run by QA: `git apply -R` of the McpControl.kt hunk (blocking readLine back) -> `FAIL ... had not returned after 6500ms: its deadline waits on a read the silent child never ends`, the only failure of 9, exit 1 after 14 s, no hang; `frames.poll(left, ...)` -> `frames.take()` the same; `destroyForcibly()` -> `Unit` fails 4 of 4 (`the probe returned and left its child running: pid 53594 is still alive`). Restored: McpProbeCleanupTest + McpControlTest 9 tests, 0 failed. :inspector-daemon:test --rerun 363 tests, 0 failed (362 at base); no McpProbeStubChild process left at t+0 to t+3 s. Not verified: full build and Android (no SDK; CI ran it); Linux and Windows only through CI; a real wedged `inspector mcp` behind the web UI button; the 5 s margin on a slow CI runner; a write to a child that never reads is still unbounded (two short lines cannot fill a pipe).
<!-- SECTION:FINAL_SUMMARY:END -->
