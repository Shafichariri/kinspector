---
id: KIN-5
title: 'Phase D C8: split McpTools.kt into tool-group files'
status: To Do
assignee: []
created_date: '2026-10-18 10:50'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-3
modified_files:
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp
  - structure/budgets.txt
type: task
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C8 (design note §3 "McpTools.kt split"). `mcp/McpTools.kt` (966 lines) is split by tool group. It shrinks to about 170 lines and keeps the same public constructor (`config`, `repository`, `markerPoster`, `signalPuller`), the same `descriptors(): JsonArray`, `acceptedArguments` verbatim (`by lazy` from `descriptors()`), `unknownArgumentError` verbatim, and `call()` with the same catches and the `"unknown tool '$name'"` fallthrough; `ToolOutcome` and `McpJson` stay in it. Dispatch is a map derived from the same `Tool` list, keyed by descriptor name: no second list.

`ToolGroup.kt`: `internal class Tool(val descriptor: JsonObject, val run: (JsonObject) -> ToolOutcome)`, `internal interface ToolGroup { val tools: List<Tool> }`, `ToolContext(config, repository)` with `resolve`/`noSuchSession` verbatim; the schema helpers and argument readers go from private to internal. The groups, in this order, reproduce today's `tools/list` order:

| File | Tools |
|---|---|
| SessionTools.kt | list_sessions, session_summary |
| TransactionTools.kt | list_transactions, get_transaction, explain, get_body (+bodyNote, explainMissingBody) |
| ExplainTool.kt | explain body, explainNotFound, splitAround, CallLine/ScreenAt/Repeats/Explanation |
| SignalTools.kt | timeline, current, list_signals, get_signal, request_signal (+CurrentObservation) |
| MarkerTools.kt | add_marker |
| ExportTools.kt | export_session (+EXPORT_FORMATS) |
| DaemonClients.kt | MarkerPoster, HttpMarkerPoster, SignalPuller, HttpSignalPuller |

Every tool block moves character for character. `McpTest`'s imports (`McpServer`, `McpTools`, `MarkerPoster`, `SignalPuller`, `ToolOutcome`, `splitAround`) stay in `dev.inspector.daemon.mcp`. Not in scope: moving explain into `service/`, splitting `McpTest`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `mcp/McpTools.kt` keeps its public constructor, `descriptors(): JsonArray`, `acceptedArguments`, `unknownArgumentError`, `call()` with the `"unknown tool '$name'"` fallthrough, `ToolOutcome` and `McpJson`
- [ ] #2 `ToolGroup.kt` and the seven files in the table exist under `mcp/`, and dispatch is derived from the same `Tool` list keyed by descriptor name, with no second list of tool names
- [ ] #3 `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change` shows every tool block as moved character for character
- [ ] #4 C1's `McpToolsListGoldenTest` passes byte-identical without `-Dinspector.mcp.regenerate=true`, and `mcp-tools-list.json` has no diff
- [ ] #5 `McpTest`'s imports of `McpServer`, `McpTools`, `MarkerPoster`, `SignalPuller`, `ToolOutcome` and `splitAround` still resolve in `dev.inspector.daemon.mcp`; `McpTest.kt` has no diff in this commit
- [ ] #6 Every new file is under 600 lines, and the McpTools.kt entry is removed from `structure/budgets.txt` with `-Dinspector.budgets.tighten=true` in the same commit
- [ ] #7 `DaemonLayersTest` reports 0 violations; `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
