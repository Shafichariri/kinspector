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
  - producers/js/test/fake-xhr.js
  - producers/js/test/xhr.test.js
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
The capture half of `inspector-producer.js`, its `test/fake-xhr.js` and its transaction fixtures.

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
| Allowlist | `BodyCapture.kt:34-41`: prefix match plus `+json`/`+xml`. The embedded copy is held by `defaults.json`. |
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

## Tests (each shown red)
| File | Holds | Red by |
|---|---|---|
| `xhr.test.js`, both event models | one frame per XHR; GET fields; the 2 MB blob (slice before read, the app's blob identical, `resBytes` 2 MB, truncated); FormData with and without an event; Blob `reqBytes`; error, timeout and abort give `status: null`; the app's `on*` handlers fire first; no double wrap; the sync path reads no body; the chunked count yields between chunks | a whole-blob read; status 0 kept; a synchronous read; a one-shot count |
| `fixtures.test.js` | transaction frames added: GET, FormData both ways, 2 MB blob, each error kind | as in the fixtures ticket |
| `:inspector-model` `ProducerFramesTest` | decodes the new frames strictly (no new code) | — |
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
- [ ] #12 capture.md carries the five JavaScript-producer paragraphs listed in the ticket
- [ ] #13 Each test in the ticket's table is shown red as stated, then restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
