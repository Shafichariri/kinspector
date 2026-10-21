---
id: KIN-91
title: 'Guard: a probe leaves no process behind'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 18:15'
updated_date: '2026-10-21 22:28'
labels:
  - 'item:guard-mcp-probe-cleanup'
dependencies: []
ordinal: 91000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Batch 1 of the order decided 2026-10-21. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. Built without QA before merge; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:guard-mcp-probe-cleanup in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, branch shafic/guard-mcp-probe-cleanup).

Criteria, copied from docs/ROADMAP.md §3 → Guards, item:guard-mcp-probe-cleanup:
1. The child the probe started is not alive once probe returns: on the success path, on the timeout path (the child never answers tools/list), and when writing to the child throws.
2. The stub child ignores EOF (keeps running after its stdin closes), so only a probe that kills it passes.
3. Deleting destroyForcibly() turns the test red, naming the surviving pid.
4. The existing 'the real control either answers or explains why it cannot' test stays.

Seam: an internal constructor on ProcessMcpControl taking a launch function (() -> Process). The public one-argument constructor is unchanged, so InspectorDaemon's constructor and phase C's parameter-count tripwire are untouched. A launch function rather than a bare command because the write-throws path needs the child to have closed its stdin BEFORE the probe writes (otherwise the write lands in the pipe buffer and nothing throws); the test's launch waits for the child to say so, which a command alone cannot.

Stub: a test-only main class run in a child JVM (java.home + java.class.path), modes answer / chatter / close-stdin; every mode keeps running after EOF. Test: new file inspector-daemon/src/test/kotlin/dev/inspector/daemon/service/McpProbeCleanupTest.kt; teardown force-kills every stub it started so a red run leaks nothing.

Files: service/McpControl.kt, the new test, and the one sentence in docs/decisions/daemon-and-mcp.md that says the property is not pinned by a test.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #126 merge 74f5ca0, one round, no blocking findings. McpProbeCleanupTest 3 of 3 pass (success, timeout, failed write; the failed-write test asserts IOException). With process.destroyForcibly() replaced by Unit: 3 of 3 FAIL, 'the probe returned and left its child running: pid 97836 is still alive' (and 97844, 97853); restored 3 of 3 pass. EOF proof: stub answering at EOF with destroyForcibly deleted -> success test passes, so the stub must ignore EOF. PeerRegistryTest.kt unchanged; InspectorDaemon.kt unchanged. :inspector-daemon:test --rerun 357 -> 360, 0 failed; pgrep -f McpProbeStubChild 0 before, after, after the red run. Not run locally: full build (no Android SDK). Found, not fixed: a probe whose child goes silent never returns (timeout checked only between lines).
<!-- SECTION:FINAL_SUMMARY:END -->
