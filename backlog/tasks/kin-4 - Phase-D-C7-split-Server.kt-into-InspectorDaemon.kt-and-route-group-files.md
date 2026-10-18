---
id: KIN-4
title: 'Phase D C7: split Server.kt into InspectorDaemon.kt and route-group files'
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
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http
  - structure/budgets.txt
type: task
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C7 (design note §2 "Server.kt split", revision 2 "N1 lazies stay lazy"). `http/Server.kt` (917 lines) becomes `http/InspectorDaemon.kt` plus one file per route group. Route groups become top-level `internal fun Route.<group>Routes(...)`, each parameter named exactly as the `InspectorDaemon` field it replaces, so handler bodies move verbatim. The only permitted in-handler edit is passing `repository` to `resolveSession`/`respondExport`. Review with `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change`.

`http/InspectorDaemon.kt` (about 230 lines) keeps the constructor unchanged (6 parameters, same order: the coverage test's tripwire is `FAKED_CONSTRUCTOR_PARAMETERS = 6`), the lazy dependencies, `start`, `requirePortAvailable`, the 127.0.0.1 bind, `routingRoot()`, `stop`, `module()`, `shutdownAfterResponse` with `RESPONSE_FLUSH_MILLIS`, `PortUnavailableException`, and the three lifecycle routes (GET /api/server, POST stop, POST restart). `module()` calls the groups in today's registration order:

| File | Signature | Routes |
|---|---|---|
| IngestRoutes.kt (+decodeBody) | ingestRoutes(manager, liveApps) | WS /ingest |
| LiveRoutes.kt | liveRoutes(manager) | WS /api/live |
| SessionRoutes.kt (+SessionDeletion) | sessionRoutes(config, repository, manager) | GET /api/recording, GET /api/sessions, DELETE /api/sessions/{id}, POST /api/sessions/clear, GET …/summary, …/transactions, …/transactions/{txnId}, …/body/{side} |
| ExportRoutes.kt (+private respondExport) | exportRoutes(repository) | GET …/har, …/export.json, .md, .html |
| SignalRoutes.kt | signalRoutes(repository, liveApps) | GET …/signals, …/signals/{signalId}, …/data, …/current, POST …/signals/request |
| MarkerRoutes.kt (+KNOWN_MARKER_SOURCES) | markerRoutes(repository, manager) | GET/POST …/markers |
| PeerRoutes.kt | peerRoutes(peers) | GET /api/peers, POST /api/peers/{pid}/kill |
| ReplayRoutes.kt | replayRoutes(replayLazy) | POST /api/replay |
| McpRoutes.kt | mcpRoutes(mcpControlLazy) | GET /api/mcp, POST /api/mcp/probe |
| WebUiRoutes.kt (+respondResource) | webUiRoutes() | /, /app.js, /style.css |
| Responses.kt | | internal respondJson, respondError, resolveSession(repository); requireControlHeader until the gate commit |

N1: the replayer and the MCP control stay lazy, so a daemon that never replays starts no HTTP client. `private val replayLazy = lazy { replayer ?: Replayer(SessionRepository(config), liveApps) }; private val replay by replayLazy`, the same for `mcpControl`; the route function takes the `Lazy` and re-declares a local delegate: `internal fun Route.replayRoutes(replayLazy: Lazy<Replayer>) { val replay by replayLazy; post("/api/replay") { … } }`, likewise `mcpRoutes(mcpControlLazy: Lazy<McpControl>)`. The eight `requireControlHeader` calls stay in this commit; the gate is KIN-6.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 `http/Server.kt` no longer exists; `http/InspectorDaemon.kt` holds the members and three lifecycle routes listed in the description, and its constructor is unchanged (6 parameters, same order)
- [x] #2 The ten route-group files and `Responses.kt` exist under `http/` with the signatures in the table, and `module()` calls them in today's registration order
- [x] #3 `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change` shows every handler body as moved; the only in-handler edits are passing `repository` to `resolveSession` and `respondExport`
- [x] #4 `replayRoutes` takes `Lazy<Replayer>` and `mcpRoutes` takes `Lazy<McpControl>`, each re-declaring a local `by` delegate, and `InspectorDaemon` constructs neither eagerly
- [x] #5 The eight `requireControlHeader` calls are still present, and `ControlHeaderCoverageTest` passes unchanged
- [x] #6 Every new file is under 600 lines, and the Server.kt entry is removed from `structure/budgets.txt` with `-Dinspector.budgets.tighten=true` in the same commit
- [x] #7 `DaemonLayersTest` reports 0 violations; the daemon test diff still matches `^[+-]import [A-Za-z0-9_.]+$` only; `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C7 committed as 41806f3. Generated by slicing Server.kt at marker lines (bodies copied byte for byte, dedented 4 where a member became top-level); unused imports pruned by compile-checking each candidate. Daemon 235/0, repo-checks 35/0; DaemonLayersTest 0 violations. Route literals identical (32 = 32). git show --color-moved=zebra --color-moved-ws=allow-indentation-change: the only plain lines are signatures, braces, the lazies, module()'s call list, dropped '// --- section' divider comments, and repository passed to resolveSession/respondExport. Eight requireControlHeader calls present; ControlHeaderCoverageTest unchanged, prints the same 8 routes. Deviations: (1) the three lifecycle routes' member function is renamed controlRoutes -> lifecycleRoutes, since peers/replay/mcp no longer register from inside it; (2) InspectorDaemon holds only the Lazy fields (mcpControlLazy, replayLazy), without the design's 'private val replay by replayLazy' member delegates, which nothing in the class would read after the split; the local delegates live in replayRoutes/mcpRoutes as specified; (3) the '// --- ingest/REST/web UI' divider comments were dropped, since each group now has its own file.

Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: constructor identical; 32 route literals identical; 1835 moved lines, plain lines only scaffolding; no HttpClient thread under --no-usb until first replay; largest file 243 lines. DoD #2: no new guard in this ticket. DoD #3 (no client names) is NOT verified and left unticked: the term list is deliberately kept out of the repo, so it cannot be checked here by design; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
