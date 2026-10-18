---
id: KIN-6
title: >-
  Phase D C9: one route-scoped ControlGate replaces the eight control-header
  checks
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 10:51'
updated_date: '2026-10-18 11:54'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-4
  - KIN-5
modified_files:
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/ControlGate.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http
  - >-
    inspector-daemon/src/test/kotlin/dev/inspector/daemon/http/ControlGateTest.kt
type: task
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C9 (design note §0.B, §2 "ControlGate", revision 2 "ControlGateTest cases"). Approved at the design gate on 2026-10-18. The phase C record already says "After phase D, the check is applied once by the control gate", and the split has just rewritten every route function.

`http/ControlGate.kt`: `createRouteScopedPlugin("ControlGate") { onCall { if (header != "1") call.respondError(403, CONTROL_REFUSAL) } }`. `Route.controlGated(build)` is `createChild(ControlGateSelector)` with a transparent `object` selector; it installs the plugin only if `pluginOrNull` is absent (the singleton selector yields the same node on every call, and a second install throws `DuplicatePluginException`), then runs `build()`. Exactly the eight phase C routes go inside: DELETE /api/sessions/{id}, POST /api/sessions/clear, POST …/signals/request, POST /api/server/stop, POST /api/server/restart, POST /api/peers/{pid}/kill, POST /api/replay, POST /api/mcp/probe. `requireControlHeader` is deleted. Markers and the WS routes stay outside.

"No route changes" means method, path, status and body unchanged. `RoutingNodeKt.path` renders only `RoutePathComponent` selectors, so the transparent selector is skipped and templates are unchanged; `getAllRoutes()` returns handler nodes and the gate node has none; the refusal stays `respondError(403, "control endpoints require the X-Inspector-Control: 1 header")`. A response from `onCall` stops the handler because in Ktor 3.5.0 the Call-phase wrapper `RoutingNode$buildPipeline$1$1` checks `PipelineCallKt.isHandled` before invoking handlers (verified in bytecode). That is a dependency on Ktor internals: revisit on upgrade.

The arbiter is `ControlHeaderCoverageTest`, unchanged. If it cannot pass unchanged, this commit is dropped and the eight calls stay; escalate rather than edit the test.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `http/ControlGate.kt` defines the route-scoped plugin and `Route.controlGated`, installing the plugin only when `pluginOrNull` is absent; exactly the eight routes named in the description are inside it; `requireControlHeader` no longer exists anywhere under `inspector-daemon/src/main`
- [ ] #2 New `inspector-daemon/src/test/kotlin/dev/inspector/daemon/http/ControlGateTest.kt` (embeddedServer on port 0, a counter per handler) passes cases 1 to 3: no header gives 403 with a body naming the header and counter 0; value `0` gives 403 and counter 0; value `1` runs the handler and counter is 1
- [ ] #3 ControlGateTest case 4: `controlGated` applied twice to one route throws nothing and both routes are gated
- [ ] #4 ControlGateTest cases 5 and 5b: a gated POST /x/clear beside an ungated POST /x/{id} is refused; an ungated POST /x/clear beside a gated POST /x/{id} reaches the ungated handler
- [ ] #5 ControlGateTest cases 6 and 7: an open GET /y beside a gated POST /y/z answers 200 with no header while the POST is refused; routes nested in `route("/n") {}` inside `controlGated` are refused
- [ ] #6 ControlGateTest case 8: the refusal body and Content-Type equal literals measured at base from today's `respondError`
- [ ] #7 Breaking the gate so it does not respond turns ControlGateTest case 1 red AND `ControlHeaderCoverageTest` red (through the `ServerControl.exit` fake); restored, both are green
- [ ] #8 `ControlHeaderCoverageTest` passes with no diff and prints the same 8 routes, and C1's `ReadRoutesOpenTest` passes unchanged
- [ ] #9 The set of (method, path) pairs from `routingRoot().getAllRoutes()` is identical before and after this commit
- [ ] #10 `DaemonLayersTest` reports 0 violations; `./gradlew :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C9 committed as 5d12bde. Daemon 244/0 (+9 ControlGateTest), repo-checks 35/0. ControlHeaderCoverageTest: no diff in C9, same 8 routes. Route set from getAllRoutes identical before/after (32 entries, runtime dump). Proven: a silent gate turns ControlGateTest 1-8 and ControlHeaderCoverageTest red (exit fake reached); dropping the install-once check throws DuplicatePluginException. Deviations: ControlGate and CONTROL_REFUSAL are private to ControlGate.kt (only controlGated needs them). Case 8 literals measured from a daemon built at e88af44, not at 1db168b: the refusal code is byte-identical between them, and building base would have meant a checkout outside this worktree. ControlGateTest's server starts outside runBlocking, because one started inside it hangs setUp; the test's KDoc says why.

Ready for QA.
<!-- SECTION:NOTES:END -->
