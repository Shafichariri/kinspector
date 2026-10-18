---
id: KIN-8
title: 'Phase D acceptance evidence: base against head comparison'
status: To Do
assignee: []
created_date: '2026-10-18 10:51'
updated_date: '2026-10-18 10:52'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-1
  - KIN-2
  - KIN-3
  - KIN-4
  - KIN-5
  - KIN-6
  - KIN-7
type: task
ordinal: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D acceptance evidence, base against head (design note revision 2, "Acceptance evidence QA runs base vs head", items 1 to 7). Base is `1db168b`, the commit the design was measured at; head is the branch after KIN-1 to KIN-7. Every comparison runs both trees the same way and goes into the PR, so "no MCP tool description or route changes" and "every daemon test passes unchanged" are shown, not asserted. No code changes in this ticket: it is the qa-verifier's run that closes the item. If any comparison differs, the delivery-tracker files a bug ticket under `item:structure-phase-d` against the ticket that caused it.

The route matrix runs on inert fakes, so stop and restart touch nothing real and are sent last. `GET /api/server` carries a pid and a port that differ per run; they are normalised before the tables are compared.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 1. `tools/list` over JSON-RPC from the `installDist` binary of base and of head is byte-identical
- [ ] #2 2. Route matrix on inert fakes: every (method, path) from base's `getAllRoutes()`, plus GET, POST, DELETE and PUT on every distinct path, each with no header, `X-Inspector-Control: 0` and `X-Inspector-Control: 1`, plus POST /api/replay with body `{}` and the header, stop and restart last, pid and port in GET /api/server normalised; the base and head tables are identical
- [ ] #3 3. With `installDist -Pinspector.version=9.9.9-phase-d`, MCP `serverInfo.version` and the HAR export's `creator.version` both read `9.9.9-phase-d` on head, as on base
- [ ] #4 4. After the head daemon starts and no replay has been sent, no thread named `HttpClient-*-SelectorManager` exists
- [ ] #5 5. Every changed line in `git diff 1db168b -- inspector-daemon/src/test` (headers aside) matches `^[+-]import [A-Za-z0-9_.]+$`, and the head test count equals the base count plus the tests in the new files only
- [ ] #6 7. `node scripts/render-web-ui.js` and `node scripts/render-web-ui-archive.js` both pass against the head daemon
- [ ] #7 6. `ControlHeaderCoverageTest` prints the same 8 probed routes, the same 2 routes exempt as WebSocket handshakes and the same allowlist on base and head
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
