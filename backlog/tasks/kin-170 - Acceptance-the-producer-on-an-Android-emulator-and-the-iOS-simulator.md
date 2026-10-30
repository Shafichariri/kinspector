---
id: KIN-170
title: 'Acceptance: the producer on an Android emulator and the iOS simulator'
status: To Do
assignee: []
created_date: '2026-10-30 13:37'
updated_date: '2026-10-30 15:30'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-169
modified_files:
  - docs/STATUS.md
  - docs/REACT-NATIVE.md
type: task
ordinal: 170000
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
No product code. It is device acceptance on the scratch React Native 0.79.6 app (Hermes, New Architecture) on an Android emulator and the **iOS simulator**: CocoaPods is installed by the maintainer, and iOS is measured and accepted there, as ruled 2026-10-30. Each result is recorded in `docs/STATUS.md` with its evidence. Proof stays on the scratch app; no sample app enters the repo.

## What is proven, on each platform unless stated
| Area | What must be shown |
|---|---|
| **Injection** (`getAppendScripts.js:16-18`) | The producer runs in a bundle built with both Metro steps, the guide's way: step 2 is a side-effect import of `inspector-producer-xhr.js`, which pulls in the core (the two-file split, KIN-164). It does **not** run when only step 1 is taken: Metro's log never shows `[inspector] connected to …`. On Android only, step 2 importing the core instead of capture shows the "nothing is captured" line in Metro's log and in LogBox. |
| **Canary** | Present in a release build that injects the producer through that side-effect import of capture. It is the core's canary, carried in through capture's `require`. `inspector-bundle-check.js` exits 1 on it, and 0 on a release build without the producer. Android: the APK and `index.android.bundle` (Hermes bytecode). iOS: the bundle and `.hbc`. |
| **Stalled daemon** (KIN-162's bound table, F1) | Connect, `kill -STOP` the daemon, then emit 2000 signals of 10 KB. Record the app's memory before, at 30 s and at 90 s, and when, if ever, the producer's `stats().state` leaves `connected`. **Android emulator:** confirms or corrects the 16 MiB okhttp close and the 10 s or 60 s failure. **iOS simulator:** measures what SocketRocket 0.7.1 holds; read `Pods/SocketRocket/SocketRocket/SRWebSocket.m` first and quote its buffer handling. Recorded, not judged. The guide's efficiency paragraph states the result. |
| **Coverage** | An axios GET and a raw `fetch()` multipart POST each arrive as one row with method, URL with port, status, timing and headers. They show in the web UI's list, on the path tab and in MCP `list_transactions`. |
| **Bodies** | A 2 MB response yields a 256 KB body and a true `resBytes`, and the app receives all 2 MB unchanged. |
| **FormData size** (measurement 1, re-run) | With a file-URI part: `reqBytes` equals the server's `Content-Length` when a progress event arrived, and is 0 with `streaming` otherwise. iOS for the first time. |
| **`resBytes` under gzip** (measurement 4) | Equals the decoded size. iOS for the first time. |
| **Cost** | Hermes's longest single macrotask spent in the producer (chunked count, read, encode, flush) on the 2 MB response. Recorded, not judged. |
| **Fast Refresh** | Keeps the session; a full reload starts a new one. This confirms or corrects the guide's sentence. |
| **Physical Android** | `adb reverse tcp:8099 tcp:8099` and `host: '127.0.0.1'`, if a device is available; recorded either way. |

A case that cannot be run is recorded as Not verified, with the reason, and is never shown as passed.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 On the Android emulator and the iOS simulator the producer runs in a bundle built with both Metro steps, and does not run with step 1 alone
- [ ] #2 An axios GET and a raw fetch() multipart POST each arrive as one row with method, URL with port, status, timing and headers, shown in the web UI's list, the path tab and MCP list_transactions
- [ ] #3 A 2 MB response yields a 256 KB body and a true resBytes, and the app receives all 2 MB unchanged
- [ ] #4 With a file-URI part, a FormData row's reqBytes equals the server's Content-Length when a progress event arrived and is 0 with streaming otherwise, recorded per platform
- [ ] #5 A gzip response's resBytes equals the decoded size on both platforms
- [ ] #6 inspector-bundle-check.js exits 1 on a release build that injects the producer through a side-effect import and 0 on one without it, on the Android APK and bundle and on the iOS bundle
- [ ] #7 Hermes's longest producer macrotask on the 2 MB response is recorded
- [ ] #8 Fast Refresh and full-reload behaviour are recorded, and the guide's sentence matches them
- [ ] #9 Every result is in docs/STATUS.md with its evidence; anything not run is recorded as Not verified with the reason
- [ ] #10 The bundles in #1 and #6 inject the producer through a side-effect import of inspector-producer-xhr.js; on Android, importing the core instead shows the 'nothing is captured' line
- [ ] #11 With the daemon stopped by kill -STOP, the app's memory and the producer's state are recorded at 0, 30 and 90 s on the Android emulator and on the iOS simulator, with SRWebSocket.m's buffer handling quoted, and docs/REACT-NATIVE.md states the result
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
QA note from KIN-163 (non-blocking): the RN Android hello sends device: "unknown" although Platform.constants.Model is available. Decide before the device proof. Also see the KIN-164 note on the untested JS device rule.
<!-- SECTION:NOTES:END -->
