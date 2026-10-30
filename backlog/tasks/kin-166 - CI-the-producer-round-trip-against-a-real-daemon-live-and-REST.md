---
id: KIN-166
title: 'CI: the producer round trip against a real daemon, live and REST'
status: To Do
assignee: []
created_date: '2026-10-30 13:36'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-164
modified_files:
  - producers/js/test/live-roundtrip.js
  - scripts/lib/ingest-client.js
  - .github/workflows/ci.yml
type: feature
ordinal: 166000
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
One CI step that runs the real producer against a real daemon and reads the archive back, over both the live path and the REST path.

## Live round trip (CI `web` job, after the archive probe)
**Setup:**
- A second daemon on port 8232 with `--data $RUNNER_TEMP/producer`, so the probe session's counts do not move.
- `scripts/lib/ingest-client.js` **exports** its `LOOPBACK` and `checkHost` guards, and the round trip `require`s them. Reused, not copied.
- Every host ends in `.invalid`.

**`producers/js/test/live-roundtrip.js`** runs the real producer, both files, over `fake-xhr.js` and Node's global `WebSocket`. It loads the pair the way the guide does: `require('../inspector-producer-xhr.js')`, which pulls in the core, then `start()` on `inspector-producer.js` (the two-file split, KIN-164). It sends:
- a GET;
- FormData twice, once with an upload progress event and once without;
- a 2 MB blob response;
- two signals in one millisecond.

**Then:**
1. **Sole-app re-sign.** Write a closed, Kotlin-shaped session through a throwaway `ingest-client` app. Replay one of its rows with re-sign while only the producer is attached, so `liveApps.sole()` (`Replayer.kt:117-118`) routes the `signReq` to it.
   - Expect the producer's error within 2 s, ending with "replay without re-signing".
   - Refusal runs before signing, so the row must be replayable.
2. **`signalReq`.** `POST …/signals/request` must answer 502 with a body carrying `registered: none`.
3. **Read-back:**
   - REST: `GET …/transactions` and `…/signals`;
   - live: `/api/live`, where `resBodyRef` must already be set.

**Asserts:**
- method, URL with port, status, `reqBytes` and `resBytes`;
- `seq` order;
- the two FormData rows: `streaming` with the true `total`, and `streaming` with 0.

A run that finds no rows fails.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 CI's web job runs producers/js/test/live-roundtrip.js against a second daemon on port 8232 with its own data dir, and the probe session's counts do not change
- [ ] #2 scripts/lib/ingest-client.js exports its LOOPBACK and checkHost guards and the round trip requires them
- [ ] #3 Rows read back over REST and from /api/live match what was sent: method, URL with port, status, reqBytes, resBytes and seq order; the live row already carries resBodyRef
- [ ] #4 The two FormData rows read back as streaming with the true total, and as streaming with 0
- [ ] #5 A re-signed replay of a closed session's row with only the producer attached fails within 2 s with the producer's error ending 'replay without re-signing'
- [ ] #6 POST …/signals/request answers 502 with registered: none
- [ ] #7 A run that reads back no rows fails
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
