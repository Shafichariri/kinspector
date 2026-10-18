---
id: KIN-7
title: 'Phase D C10: update file references and record the three phase D decisions'
status: To Do
assignee: []
created_date: '2026-10-18 10:51'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-6
modified_files:
  - docs/decisions/devices-and-stream.md
  - >-
    inspector-stream/src/androidMain/kotlin/dev/inspector/stream/Platform.android.kt
  - repo-checks/src/test/kotlin/dev/inspector/repochecks/FileBudgetTest.kt
  - inspector-daemon/AGENTS.md
  - docs/decisions/daemon-and-mcp.md
  - structure/budgets.txt
type: docs
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C10 (design note §7 and revision 2 "Commit plan revised", C10). Brings every file reference up to date with the moved code and records the three decisions phase D establishes, in the house style of `docs/decisions/` (bold one-line rule, the reason, the test that pins it).

Reference updates: `docs/decisions/devices-and-stream.md:43` and the comment at `inspector-stream/src/androidMain/kotlin/dev/inspector/stream/Platform.android.kt:50` (comment only) say "boundary in `Server.kt`", which becomes `http/InspectorDaemon.kt`; the KDoc at `repo-checks/src/test/kotlin/dev/inspector/repochecks/FileBudgetTest.kt:10`; `inspector-daemon/AGENTS.md:47-50` key-file paths (`service/Har.kt`, `service/Export.kt`, `archive/SessionRepository.kt`) plus rows for `http/InspectorDaemon.kt` and the layer table. The three recorded decisions in `docs/decisions/daemon-and-mcp.md` the roadmap item names (identity re-checked at kill time, no "start MCP" endpoint, `ServerControl` as an interface) cite names rather than paths; each is re-checked against the moved code.

The `McpControlTest` sentence is corrected to: "`McpControlTest` (in `PeerRegistryTest.kt`) asserts that probing requires the header and spawns nothing without it, and that a probe reports a tool count or a reason. That a probe leaves no process behind rests on its `finally { process.destroyForcibly() }` and is not pinned by a test; `ROADMAP.md` §3 → Guards carries it."

New paragraphs in `docs/decisions/daemon-and-mcp.md`:
1. "The daemon's packages are layers, and an import scan holds them because `internal` cannot" (pinned by `DaemonLayersTest`). It records the rule "A rule both adapters serve lives in `service/` or `archive/`, never in a route or tool body." and the two known violations, recorded and not fixed: GET …/signals sorts by `mono` descending and clamps 1..1000 in the route while MCP `list_signals` uses `SessionRepository.querySignals` and filters differently (tag `equalsIgnoreCase` against the filter grammar); and the delete/clear loop.
2. "A daemon class decoded from outside input keeps its pre-move serial name" (pinned by `SerialNameTest`).
3. "Control routes are gated by one route-scoped plugin, and the coverage test, not the gate, catches a route left outside it" (pinned by `ControlGateTest` and `ControlHeaderCoverageTest`; depends on `isHandled` in Ktor 3.5.0's `buildPipeline`, revisit on a Ktor upgrade).

The `McpTest.kt` line in `structure/budgets.txt` gets the reason text below. The four `ROADMAP.md` lines the design calls for (two under §3 Guards, the delete/clear extraction under §6 1a, the `McpTest` split) belong to the product owner and are not part of this ticket.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `grep -rn 'Server.kt' docs inspector-daemon/AGENTS.md inspector-stream/src repo-checks/src` returns no line outside `docs/ROADMAP.md` that describes current code; `devices-and-stream.md` and `Platform.android.kt` name `http/InspectorDaemon.kt`, and the `Platform.android.kt` diff is comment lines only
- [ ] #2 `FileBudgetTest.kt`'s KDoc no longer names `Server.kt` or `McpTools.kt` as files awaiting phase D
- [ ] #3 `inspector-daemon/AGENTS.md` key-file rows point at `service/Har.kt`, `service/Export.kt` and `archive/SessionRepository.kt`, and it has a row for `http/InspectorDaemon.kt` and the layer table; every path in those rows exists
- [ ] #4 The `McpControlTest` sentence in `docs/decisions/daemon-and-mcp.md` reads exactly as quoted in the description
- [ ] #5 The kill-time identity re-check, the no "start MCP" endpoint decision and `ServerControl` as an interface are each still true of the moved code, with the file each now lives in recorded in the QA evidence
- [ ] #6 `docs/decisions/daemon-and-mcp.md` has the three new paragraphs with the bold rules as titled in the description; the layers paragraph quotes the adapters rule and names both known violations; each names the test that pins it
- [ ] #7 The `McpTest.kt` entry in `structure/budgets.txt` carries the reason "phase D added N import lines and nothing else. Its split is deferred because D's criteria keep the tests unchanged." with N the measured count
- [ ] #8 `./gradlew :repo-checks:test :inspector-daemon:test` passes, including `DecisionsTest` and `AgentGuideTest`
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
