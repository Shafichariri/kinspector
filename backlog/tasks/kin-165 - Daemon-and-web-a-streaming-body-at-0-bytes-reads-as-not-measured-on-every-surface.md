---
id: KIN-165
title: >-
  Daemon and web: a streaming body at 0 bytes reads as not measured on every
  surface
status: To Do
assignee: []
created_date: '2026-10-30 13:36'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-164
  - KIN-161
modified_files:
  - inspector-daemon/src/main/resources/web/js/35-detail.js
  - inspector-daemon/src/main/resources/web/js/40-copy-export.js
  - inspector-daemon/src/main/resources/web/js/75-replay.js
  - inspector-daemon/src/main/resources/web/js/15-session-data.js
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/TransactionTools.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/ExplainTool.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/ExportMarkdown.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/Har.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/RouteStats.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/Replay.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Duplicates.kt
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Transaction.kt
  - mirror-cases/duplicates.json
  - docs/DAEMON.md
  - docs/schema.md
  - docs/decisions/exports.md
  - docs/decisions/shared-rules.md
  - docs/decisions/web-ui.md
  - scripts/check-web-static.js
  - scripts/lib/polish-cases.js
  - scripts/lib/polish-checks.js
  - scripts/lib/polish-probes.js
type: feature
ordinal: 165000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Item:** `item:producer-react-native` (ROADMAP §7, "Order from here" row 42, lane J). This ticket carries its part of the architect's design note (round 2), which passed the design gate on 2026-10-30.

**Maintainer rulings at the gate (2026-10-30):**
- the capture letter is **4d**, JavaScript producers;
- FormData size uses **`streaming`**: `reqBytes` is the upload `total` when a progress event arrived, else 0, meaning not measured;
- iOS is measured and accepted on the **simulator**, with CocoaPods installed by the maintainer;
- a `streaming` row at 0 never joins a repeat group;
- no sample React Native app in the repo; device proof comes from the scratch app and is recorded in `docs/STATUS.md`;
- the `AGENTS.md` moves and the reframe wording are accepted;
- there is no redaction in JS v1;
- re-sign stays checked by default, and the producer's error names the fix.

**Measurement numbers are the ROADMAP's:** 1 upload, 2 TextEncoder, 3 canary, 4 Content-Length. The note added 6 (performance.now) and 7 (fetch→XHR).

## This ticket builds
The surfaces that read a `streaming` row at `reqBytes: 0` ("not measured"), the `captureAllBodies` remedy wording that is false for a JS row, and the empty-list wording that names only Kotlin.

## Why `streaming` (ruled at the gate, 2026-10-30)
| Question | Answer |
|---|---|
| True for the row? | Yes. Native code builds the multipart body, and JS never holds it. |
| Replay | `Replay.kt:97` refuses `streaming` **regardless of `reqBytes`**, in every daemon that has replay. That includes an older daemon, which therefore cannot replay such a row empty. |
| Precedent | `streaming` at 0 already exists. `InspectorOkHttpInterceptor.kt:183-186` emits `STREAMING` with `known = declared.coerceAtLeast(0)`, so a one-shot or duplex OkHttp body of unknown length is already `streaming` with `reqBytes: 0` in Kotlin archives. Every surface below misreads those rows today too. |
| Model change | None. The `STREAMING` kdoc (`Transaction.kt:176-180`) is amended to "its size is reported when known". |

**Rulings this touches** (the maintainer ruled `streaming` at the gate; the product owner records it in ROADMAP §7):
- overturn 6 applies: 0 can mean unknown, and HAR writes `-1`;
- the size-unknown constant is replaced by `streaming`;
- the two request-size criteria are restated;
- `docs/schema.md`'s `reqBytes` "**True** body size" and exports.md's "`bodySize` is deliberately *not* `-1`" are amended for `streaming` at 0.

## Surfaces
Every one checks `streaming` at 0 **before** any zero-byte shortcut.

| File | Change |
|---|---|
| `inspector-daemon/src/main/resources/web/js/35-detail.js:235-256` | Check `streaming` before `if (!totalBytes) return 'empty'`.<br>At 0: `not captured — streamed body, never held in memory; its size was not measured. It was not empty.` |
| `35-detail.js:178` (request tab face) and `:218` (Request size) | At 0 under `streaming`: `not measured`. |
| `web/js/40-copy-export.js:90` | At 0 under `streaming`: `req size not measured`. |
| `web/js/75-replay.js:64-77` | Check `streaming` before `if (!txn.reqBytes) return null`.<br>At 0: `A streamed body was never buffered and its size was not measured. There is nothing captured to replay; supply the body here.` |
| `mcp/TransactionTools.kt:141-156` | At 0: `The req body was streamed and never held in memory, and its size was not measured. It was not empty.` Builds on the omission-first ordering from the depended-on bug ticket. |
| `mcp/ExplainTool.kt:195` | `CallLine` gains `reqBodyOmitted`, so a dropped 0 is not read as "no body". |
| `service/ExportMarkdown.kt:391` | At 0: `Body: not captured — it was streamed and never held, and its size was not measured. That does not mean it was empty.` |
| `service/Har.kt:142` | The request's `bodySize` is `-1` when `streaming` and 0, and its comment says `size not measured`. |
| `service/RouteStats.kt:209,273` | `meanReqBytes` excludes `streaming` rows at 0. |
| `inspector-model/.../Duplicates.kt:108`, the web UI's `duplicates` mirror block, `mirror-cases/duplicates.json` | A `streaming` row at 0 never joins a repeat group, so two different uploads never read "sent 2×". Ruled at the gate. Cases go into the case file, and both runners run them. |
| HTML export | No code. exports.md: "`export.html` is the served `index.html` with every file in `web/scripts.txt`… inlined". |
| `docs/DAEMON.md` | `streaming with reqBytes 0: the body was streamed and its size was not measured (a JavaScript FormData upload with no progress event, or an OkHttp one-shot or duplex body of unknown length). It was not empty.` |
| `docs/schema.md` | `reqBytes` documents `streaming` at 0. |

**The `captureAllBodies` remedy.** It is false for a JS row on four sites: `Replay.kt:107`, `75-replay.js:75`, `35-detail.js:247-249` and `TransactionTools.kt:151`. Each reads:

> set captureAllBodies = true in the app's inspector config (InspectorConfig in Kotlin, start() in JavaScript)

**The empty-list wording.** `web/js/15-session-data.js:35` reads:

> waiting for an app to connect: inspector-stream in a Kotlin app, inspector-producer.js in a JavaScript one

Changed in the same commit, because they quote the old sentence:
- `docs/decisions/web-ui.md`;
- the probe strings at `scripts/lib/polish-cases.js:376`, `polish-checks.js:246` and `polish-probes.js:246`.

## Decision paragraphs
| File | Paragraph | Pinned by |
|---|---|---|
| `exports.md` | `bodySize -1` for `streaming` at 0, the one exception to "deliberately *not* `-1`". | `HarTest` |
| `shared-rules.md` | The duplicates exclusion, in the repeated-request paragraph. | the mirror case |

## Tests (each shown red with the old ordering or wording put back)
| Test | Holds |
|---|---|
| `ReplayTest` | A `streaming` row at 0 and at > 0 are both refused, and the `captureAllBodies` wording is right. |
| `TransactionToolsTest` | The `get_body` text at 0. |
| `ExplainToolTest` | `CallLine` carries `reqBodyOmitted`. |
| `HarTest` | `-1` at 0; `reqBytes` when > 0. |
| `ExportTest` or a Markdown case | The Markdown line. |
| `RouteStatsTest` | The mean excludes rows at 0. |
| `DuplicatesTest`, `mirror-cases/duplicates.json` | Exclusion on both sides. |
| `check-web-static.js` lines | The detail, request size, tab face, copy summary and replay note texts, lifted from the real files; `no calls why` with the new wording. |
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 On a row with reqBodyOmitted streaming and reqBytes 0, the web detail, request size, request tab face, copy summary, replay note, MCP get_body, MCP explain, Markdown export and HAR say the size was not measured, with the exact strings in the ticket, and none says empty or no body
- [ ] #2 HAR writes request bodySize -1 for that row and reqBytes when it is above 0
- [ ] #3 Replay refuses a streaming row at 0 and above 0
- [ ] #4 Route stats' mean request size leaves streaming rows at 0 out
- [ ] #5 Two streaming rows at 0 to one URL never form a repeat group, on the model and in the web UI, held by cases in mirror-cases/duplicates.json
- [ ] #6 The captureAllBodies remedy on Replay.kt:107, 75-replay.js:75, 35-detail.js:247-249 and TransactionTools.kt:151 names InspectorConfig in Kotlin and start() in JavaScript
- [ ] #7 The no-session empty text reads 'waiting for an app to connect: inspector-stream in a Kotlin app, inspector-producer.js in a JavaScript one', with web-ui.md and the probe strings changed in the same commit
- [ ] #8 docs/DAEMON.md and docs/schema.md describe streaming at 0; the STREAMING kdoc says its size is reported when known
- [ ] #9 exports.md and shared-rules.md carry the two paragraphs in the ticket
- [ ] #10 Each test in the ticket's table is shown red with the old ordering or wording put back, then restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
