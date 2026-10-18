---
id: KIN-4
title: 'Phase D C7: split Server.kt into InspectorDaemon.kt and route-group files'
status: To Do
assignee: []
created_date: '2026-10-18 10:50'
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
- [ ] #1 `http/Server.kt` no longer exists; `http/InspectorDaemon.kt` holds the members and three lifecycle routes listed in the description, and its constructor is unchanged (6 parameters, same order)
- [ ] #2 The ten route-group files and `Responses.kt` exist under `http/` with the signatures in the table, and `module()` calls them in today's registration order
- [ ] #3 `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change` shows every handler body as moved; the only in-handler edits are passing `repository` to `resolveSession` and `respondExport`
- [ ] #4 `replayRoutes` takes `Lazy<Replayer>` and `mcpRoutes` takes `Lazy<McpControl>`, each re-declaring a local `by` delegate, and `InspectorDaemon` constructs neither eagerly
- [ ] #5 The eight `requireControlHeader` calls are still present, and `ControlHeaderCoverageTest` passes unchanged
- [ ] #6 Every new file is under 600 lines, and the Server.kt entry is removed from `structure/budgets.txt` with `-Dinspector.budgets.tighten=true` in the same commit
- [ ] #7 `DaemonLayersTest` reports 0 violations; the daemon test diff still matches `^[+-]import [A-Za-z0-9_.]+$` only; `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
