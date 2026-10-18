---
id: KIN-1
title: >-
  Phase D C1: guards before the move (peer argv, serial names, tools/list
  golden, kill identity, open reads)
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 10:49'
updated_date: '2026-10-18 11:54'
labels:
  - 'item:structure-phase-d'
dependencies: []
modified_files:
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/MainClassTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/SerialNameTest.kt
  - >-
    inspector-daemon/src/test/kotlin/dev/inspector/daemon/McpToolsListGoldenTest.kt
  - inspector-daemon/src/test/resources/mcp-tools-list.json
  - >-
    inspector-daemon/src/test/kotlin/dev/inspector/daemon/PeerKillIdentityTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ReadRoutesOpenTest.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/Daemon.kt
type: task
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C1 (design note revision 2, "Commit plan revised"). Guards land before anything moves, so every later commit in the item is made under them. All are new test files in `inspector-daemon/src/test/kotlin/dev/inspector/daemon/`; no existing test file is touched. Each is proven both ways at base, before any move.

- `MainClassTest` pins the peer argv token: `PeerArgv.MAIN_CLASS` must stay exactly `dev.inspector.daemon.MainKt`, which is also how a new daemon recognises older peers, and it must resolve to a real entry point.
- `SerialNameTest` pins the serial names of the only three daemon-owned `@Serializable` types ever decoded from outside input (`ReplayRequest`, `SignalPullRequest`, `DaemonConfig.FileConfig`), because kotlinx-serialization quotes the serial name in `MissingFieldException` and the route echoes that message into a 400 body (for `FileConfig`, to stderr). It passes at base because the names are still the class FQNs there.
- `McpToolsListGoldenTest` holds the `tools/list` reply byte for byte against a golden file generated at base, so "no MCP tool description changes" is a test rather than a promise.
- `PeerKillIdentityTest` pins the recorded decision that the kill endpoint re-verifies process identity at kill time.
- `ReadRoutesOpenTest` pins that read-only routes stay open (no header needed), which the control gate must not change.

`Daemon.kt` (`DaemonPlaceholder`) is deleted in the same commit: it is unreferenced and its KDoc is stale.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `MainClassTest` asserts the literal `PeerArgv.MAIN_CLASS == "dev.inspector.daemon.MainKt"` and that `Class.forName(PeerArgv.MAIN_CLASS, false, loader)` has a public static void `main(String[])`; editing the constant turns it red, restoring it turns it green
- [ ] #2 `SerialNameTest` asserts the descriptors' `serialName` equal `dev.inspector.daemon.ReplayRequest`, `dev.inspector.daemon.SignalPullRequest` and `dev.inspector.daemon.DaemonConfig.FileConfig`, and passes at base
- [ ] #3 `McpToolsListGoldenTest` compares the `tools/list` reply with `inspector-daemon/src/test/resources/mcp-tools-list.json`, generated at base; the file is rewritten only under `-Dinspector.mcp.regenerate=true`
- [ ] #4 `PeerKillIdentityTest` spawns `sleep 30`; `ProcessHandlePeerRegistry().kill(pid, false)` is `Refused` with a reason containing "not an inspector process", the process is still alive afterwards, and teardown destroys it
- [ ] #5 `ReadRoutesOpenTest` sends every GET in `routingRoot().getAllRoutes()` with no `X-Inspector-Control` header to a daemon on inert fakes, and none answers a 403 naming the header
- [ ] #6 Each of the five guards is proven both ways at base (broken, seen failing, restored), with the red output recorded
- [ ] #7 `inspector-daemon/src/main/kotlin/dev/inspector/daemon/Daemon.kt` is deleted and nothing referenced `DaemonPlaceholder`
- [ ] #8 The five guards are new files; `git diff <base> --stat -- inspector-daemon/src/test` lists no pre-existing test file
- [ ] #9 `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C1 committed as 8c4f9a4. Daemon tests 227 -> 235 (+8), repo-checks 18. All five guards proven both ways at base; proofs in the session scratchpad phase-d-proofs.md under KIN-1. Deviation: inspector-daemon/build.gradle.kts gained a systemProperty forward for inspector.mcp.regenerate (Gradle's -D lands on the Gradle daemon, not the test JVM; same pattern as inspector.api.regenerate in inspector-core). Without it AC #3's regenerate switch could not reach the test. ReadRoutesOpenTest probes 23 GET routes.

Ready for QA.
<!-- SECTION:NOTES:END -->
