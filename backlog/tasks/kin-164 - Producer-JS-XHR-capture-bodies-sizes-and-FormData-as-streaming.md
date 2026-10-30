---
id: KIN-164
title: 'Producer JS: XHR capture, bodies, sizes and FormData as streaming'
status: To Do
assignee: []
created_date: '2026-10-30 13:36'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-162
  - KIN-163
modified_files:
  - producers/js/inspector-producer.js
  - producers/js/inspector-producer-xhr.js
  - producers/js/AGENTS.md
  - producers/js/test/fake-xhr.js
  - producers/js/test/xhr.test.js
  - producers/js/test/pair.test.js
  - producers/js/test/version.test.js
  - producers/js/test/defaults.test.js
  - producers/js/fixtures/frames
  - docs/decisions/capture.md
type: feature
ordinal: 164000
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
The capture file `inspector-producer-xhr.js`, the hook in the core that it attaches to, `test/fake-xhr.js` and the transaction fixtures.

## Two files (ruled by the maintainer 2026-10-30, against the architect's budget-entry recommendation)
The producer is two vendorable files. Each stays under the 600-line `FileBudgetTest` cap, so `structure/budgets.txt` gets no entry.

| File | Holds | Lines, estimated |
|---|---|---|
| `inspector-producer.js`, the **core** | transport, config, the emulator rule (KIN-163), `signal()`, both replies, the ring and the `bufferedAmount` gate (KIN-172), the UTF-8 and base64 helpers, the canary, `PRODUCER_VERSION`, and the capture hook below | about 535, headroom about 65 |
| `inspector-producer-xhr.js`, **capture** | the XHR wrap, URL parsing, the allowlist and body cap, body and size capture, FormData, building the `txn` frame | about 300, headroom about 300 |

**Dependency direction: capture requires the core, and the core never requires capture.**
- `inspector-producer-xhr.js` begins `const core = require('./inspector-producer.js')` and calls `core._attach(install, PRODUCER_VERSION)` at module top level. Under measurement 3 a binding that is used survives, so importing the capture file as a side effect pulls in both files. That import is the guide's step 2 (KIN-169).
- A capture file vendored without the core fails Metro's resolution at build time, which is loud enough.
- The core imports nothing of capture's, so the core alone loads, connects and sends signals.

**The hook.**
- `_attach(install, version)` registers `install`. `start()` calls each registered `install` with an internal context after it has started:
  - the hot-path pieces: `stamp`, `enqueue`, `randomId`, `now`, `wallNow`, `setTimeout`, `log`, `XMLHttpRequest` from deps;
  - the UTF-8 and base64 helpers. **They are passed, not copied: one copy in the core.**
- A capture attached after `start()` is installed at once.
- The registry is reachable through `deps`, so a test can run the core with no capture attached.
- `_attach` is not consumer surface. `config.test.js` keeps the public keys `start`, `signal`, `stats` and `stop`, and allows only underscored internals beyond them.

**A core vendored alone is told loudly that it captures nothing.**
- On the first `helloAck` of the producer's life, if no capture is attached, the core logs once with `console.error`, so LogBox shows it:
  `[inspector] nothing is captured: inspector-producer-xhr.js was not loaded. Import it as the guide's step 2 shows; it loads inspector-producer.js itself.`
- **Why `helloAck` and not `start()`:** by then every module the bundle runs before main has loaded, so the order of the import and the `start()` call cannot raise a false alarm. The line also lands beside `connected to …`, which is where the guide tells the reader to look.
- This widens the ladder's "only refusals use `console.error`" to the two capture lines in this section.

**One version for the pair.**
- Both files declare `const PRODUCER_VERSION = '<x.y.z>'`. Rename the core's `VERSION`; `globalThis.__INSPECTOR_PRODUCER__.version` reads it.
- `_attach` compares the two versions. On a mismatch it installs nothing and logs once with `console.error`:
  `[inspector] not capturing: inspector-producer-xhr.js is <a> but inspector-producer.js is <b>. Copy both files from one release.`
- `version.test.js` reads both constants from source and fails when they differ. KIN-168's tag check reads the same two constants.

**One canary, in the core.** The capture file holds none.
- Every bundle that holds producer code holds the core: capture requires it, and the core alone is the core.
- So one used top-level canary covers every case, and KIN-167's check, which reads it from `inspector-producer.js`, needs no change.
- The check proves producer code is in the bundle. It does not prove capture is, and it does not need to.

## The XHR wrap
**Source facts**, read from React Native 0.79.6:
- `fetch` is whatwg-fetch over XHR, with `responseType` `blob` (measurement 7).
- RN ships `XMLHttpRequest_old`/`_new`, chosen by `RN$useBuiltInEventTarget`. `WebSocket` and `FileReader` have the same split.
- For `'json'`, the raw string is kept in `_response` (`_old.js:271-276`).
- Metro's `getAppendScripts.js:16-18` runs a listed module only if it is in the graph. The default list is `[InitializeCore]` (`@react-native/metro-config/dist/index.js:48-50`).
- whatwg-fetch 3.6.20 sets `on*` **properties** (`fetch.umd.js:546-577`). So the producer uses `addEventListener` only.

**Wrapped:** `open`, `setRequestHeader` and `send`.
- A prototype symbol prevents a second wrap.
- `fake-xhr.js` covers both event models.

| Concern | Rule |
|---|---|
| Rows | **Exactly one `txn` frame per XHR**, sent at `loadend`. The archive is append-only (`SessionWriter.append`), so a second frame would be a second row. |
| URL | The producer's own regex, because RN's `URL` lacks `hostname`/`port`.<br>`port` only when explicit and not the default.<br>Host lowercased; IPv6 keeps its brackets.<br>An empty path is `/`; the fragment is dropped. |
| Request body | **string:** captured if allowlisted, cap 256 KB; `reqBytes` is the chunked UTF-8 count.<br>**ArrayBuffer or view:** `byteLength`.<br>**Blob:** `blob.size`, read sliced and only if allowlisted.<br>**FormData:** below. |
| Allowlist | `BodyCapture.kt:34-41`: prefix match plus `+json`/`+xml`. The embedded copy, in `inspector-producer-xhr.js`, is held to `defaults.json` by `defaults.test.js`. |
| Response body | **`''` or `'text'`:** `responseText`.<br>**`'blob'`:** `resBytes` is `blob.size`; then `slice(0, cap)` before `FileReader`. Not allowlisted: no read, and `contentType`.<br>**`'arraybuffer'`:** `byteLength`.<br>**`'json'`:** `_response`. |
| `resBytes` | The decoded size the app received. **`Content-Length` is never read:** on Android, OkHttp's transparent gzip strips it, along with `Content-Encoding` (measurement 4). |
| Errors | `load` gives the status.<br>`error`, `timeout` and `abort` give `status: null` and `error`:<br>- `XMLHttpRequestError: <_response message>`<br>- `TimeoutError: after <n> ms`<br>- `AbortError`<br>`ms` is an integer. |
| Attempts | `attempt: 1` and a fresh `callId` per XHR. Redirects followed natively are invisible, and an axios retry is a new row. |
| Redaction | None in v1: `redacted: []`. |

## FormData request size (ruled at the gate: `streaming`)
**What measurement 1 found:**
- **Android:** when an upload progress event fires, its `total` equals the server's `Content-Length` (205252). Events fire only sometimes:
  - none in run 3;
  - none in any run via `adb reverse`;
  - none for `fetch`;
  - the final event never fires.
- **Why**, from the source: `ProgressRequestBody.kt` double-counts and skips the exact-done value, and `NetworkingModule.java:586-590` drops events within 100 ms.
- **The `total` itself:**
  - iOS: `HTTPBody.length` (`RCTNetworkTask.mm:135`);
  - Android: OkHttp's `contentLength()`, and for file and content-URI parts `inputStream.available()` (`RequestBodyUtil.kt:140-143`).
- **JS cannot compute it:** file parts are native URIs, and the native side picks the boundary.

| FormData row | `reqBytes` | `reqBodyOmitted` | `reqContentType` |
|---|---|---|---|
| A progress event with `lengthComputable` arrived | that `total`, a true size | `streaming` | `multipart/form-data` unless the app set one |
| No event arrived | `0`, meaning **not measured** | `streaming` | same |

Replay refuses both rows through `Replay.kt:97`, which refuses `streaming` regardless of `reqBytes`.

## Efficiency contract, restated for one thread
| Where | Work |
|---|---|
| `send()`, synchronous | Take `mono`, `seq` and `ts`.<br>Keep references to the method, URL, header map and body. The body is not read.<br>Add `loadend` listeners, and `xhr.upload` `progress` for FormData.<br>Return.<br>**A spy test proves this path never reads a body.** |
| `loadend` | Snapshot the status, parse `getAllResponseHeaders()`, then `setTimeout(0)`. The app's `onload` and promise continuations run first. |
| Deferred, still on the JS thread | **UTF-8 length of a text body:** counted in chunks of 64 Ki chars per macrotask. A 2 MB body took 3.7 ms with V8 JIT and 53 ms with `--jitless`; Hermes is measured in device acceptance.<br>**Slice:** 256 KB.<br>**Read:** one async `FileReader` of ≤ 256 KB, native side.<br>**Encode:** `JSON.stringify` of the frame, here, so its length is known for the ring. |

**What is not claimed:** that any of this is free. The dedicated worker is Kotlin's mechanism. The aim is unchanged: the app never blocks, waits or meaningfully allocates.

## Decision paragraphs (capture.md, under "**JavaScript producers.**")
| Paragraph | Pinned by |
|---|---|
| The XHR prototype wrap with listeners, not RN's interceptor slot or `on*` properties. | `xhr.test.js`, "app handlers still fire" |
| The blob is sliced before `FileReader`, because RN's `FileReader` reads the whole blob. | the 2 MB case |
| The efficiency contract restated. | the sync-path spy |
| FormData is `streaming`, with `total` when a progress event was seen and 0 otherwise. | the FormData cases |
| `resBytes` is the decoded size, and `Content-Length` is never read. | — |
| **The producer is two files, and capture requires the core.** Ruled 2026-10-30. Do not merge the files back over the line cap, and do not make the core require capture. One side-effect import of capture pulls in both. The core alone says loudly that it captures nothing. There is one canary, in the core, and one `PRODUCER_VERSION` that both files must agree on. | `pair.test.js`, `version.test.js` |

## Tests (each shown red)
| File | Holds | Red by |
|---|---|---|
| `xhr.test.js`, both event models | one frame per XHR; GET fields; the 2 MB blob (slice before read, the app's blob identical, `resBytes` 2 MB, truncated); FormData with and without an event; Blob `reqBytes`; error, timeout and abort give `status: null`; the app's `on*` handlers fire first; no double wrap; the sync path reads no body; the chunked count yields between chunks | a whole-blob read; status 0 kept; a synchronous read; a one-shot count |
| `fixtures.test.js` | transaction frames added: GET, FormData both ways, 2 MB blob, each error kind | as in the fixtures ticket |
| `:inspector-model` `ProducerFramesTest` | decodes the new frames strictly (no new code) | — |
| `pair.test.js` | the core with no capture attached logs the "nothing is captured" line once, at the first `helloAck`, and not again after a reconnect; core plus capture logs no such line; a capture attached after `start()` wraps at once; mismatched versions log the "not capturing" line and leave `XMLHttpRequest.prototype` unwrapped; the capture file holds no `INSPECTOR_JS_CANARY_` string | the no-capture check deleted; the version comparison deleted |
| `version.test.js` | `PRODUCER_VERSION` read from both files' source is present and equal | one file's version bumped |
| `defaults.test.js` (extended from KIN-163) | capture's embedded allowlist and 262144 cap equal `defaults.json`, and its predicate gives each content-type case's answer | one embedded allowlist entry changed |
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 XMLHttpRequest.prototype open, setRequestHeader and send are wrapped once, through addEventListener only; the app's own on* handlers still fire, before the producer's deferred work
- [ ] #2 Each XHR yields exactly one txn frame, sent at loadend
- [ ] #3 send()'s synchronous path never reads a request or response body (spy test)
- [ ] #4 A 2 MB blob response is sliced to 256 KB before FileReader; the row carries a 256 KB body, resBodyTruncated and resBytes of 2 MB; the app's blob is unchanged
- [ ] #5 resBytes is the decoded size the app received, and Content-Length is never read
- [ ] #6 A FormData row is reqBodyOmitted streaming with reqContentType multipart/form-data, reqBytes the upload total when a progress event arrived and 0 otherwise
- [ ] #7 A Blob request body reports blob.size as reqBytes
- [ ] #8 error, timeout and abort give status null and the error strings in the ticket, with an integer ms
- [ ] #9 Text bodies are counted in chunks of 64 Ki chars per macrotask
- [ ] #10 fake-xhr.js covers both RN event models and every xhr.test.js case runs on both
- [ ] #11 Transaction fixtures decode under ProducerFramesTest
- [ ] #12 capture.md carries the six JavaScript-producer paragraphs listed in the ticket
- [ ] #13 Each test in the ticket's table is shown red as stated, then restored
- [ ] #14 The XHR capture is in inspector-producer-xhr.js, which requires ./inspector-producer.js and registers through _attach; inspector-producer.js requires nothing of it; both files are under 600 lines with no budgets.txt entry
- [ ] #15 With no capture attached, the first helloAck logs '[inspector] nothing is captured: inspector-producer-xhr.js was not loaded. Import it as the guide's step 2 shows; it loads inspector-producer.js itself.' once with console.error; with capture attached it does not
- [ ] #16 Both files declare PRODUCER_VERSION; on a mismatch capture installs nothing and logs the 'not capturing' line in the ticket; version.test.js fails when the two differ
- [ ] #17 The canary is only in inspector-producer.js, and the UTF-8 and base64 helpers exist only there; capture receives them through the hook
- [ ] #18 producers/js/AGENTS.md's key-files table lists both files, each under 600 lines, and says capture requires the core
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
