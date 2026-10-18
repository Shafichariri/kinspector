---
id: KIN-5
title: 'Phase D C8: split McpTools.kt into tool-group files'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 10:50'
updated_date: '2026-10-18 12:40'
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
- [x] #1 `mcp/McpTools.kt` keeps its public constructor, `descriptors(): JsonArray`, `acceptedArguments`, `unknownArgumentError`, `call()` with the `"unknown tool '$name'"` fallthrough, `ToolOutcome` and `McpJson`
- [x] #2 `ToolGroup.kt` and the seven files in the table exist under `mcp/`, and dispatch is derived from the same `Tool` list keyed by descriptor name, with no second list of tool names
- [x] #3 `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change` shows every tool block as moved character for character
- [x] #4 C1's `McpToolsListGoldenTest` passes byte-identical without `-Dinspector.mcp.regenerate=true`, and `mcp-tools-list.json` has no diff
- [x] #5 `McpTest`'s imports of `McpServer`, `McpTools`, `MarkerPoster`, `SignalPuller`, `ToolOutcome` and `splitAround` still resolve in `dev.inspector.daemon.mcp`; `McpTest.kt` has no diff in this commit
- [x] #6 Every new file is under 600 lines, and the McpTools.kt entry is removed from `structure/budgets.txt` with `-Dinspector.budgets.tighten=true` in the same commit
- [x] #7 `DaemonLayersTest` reports 0 violations; `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C8 committed as e88af44. Golden byte-identical without regenerate; McpTest.kt and mcp-tools-list.json no diff; daemon 235/0, repo-checks 35/0, DaemonLayersTest 0. Proven: swapping two groups in McpTools.tools turns the golden red. Design choices worth knowing: (1) Tool(descriptor) { run }: the descriptor is the only argument in parentheses and run is a trailing lambda, so each descriptor block moved byte for byte, closing brace included; (2) ToolContext is an internal abstract class the archive-reading groups extend, so tool bodies still read repository/config/resolve(args) unchanged; (3) explain is an internal extension on TransactionTools in ExplainTool.kt, so its body moved verbatim, and bodyNote went from private to internal so the extension can call it; (4) the dangling KDoc above CurrentObservation ('Posting a marker needs the daemon...') moved onto MarkerPoster, which it describes; (5) two KDoc links fixed ([addMarker] -> [MarkerTools], [McpTools.requestSignal] -> [SignalTools.requestSignal]); (6) MarkerTools takes only the poster, since add_marker reads no archive.

Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: acceptedArguments and unknownArgumentError verbatim; one tools list; 1640 moved lines; mcp-tools-list.json untouched. DoD #2: no new guard in this ticket. DoD #3 (no client names) is NOT verified and left unticked: the term list is deliberately kept out of the repo, so it cannot be checked here by design; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
