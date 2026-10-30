---
id: KIN-162
title: 'Producer JS: transport, config, signal() and the daemon replies'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-30 13:35'
updated_date: '2026-10-30 14:27'
labels:
  - 'item:producer-react-native'
dependencies: []
modified_files:
  - producers/js/inspector-producer.js
  - producers/js/AGENTS.md
  - producers/js/CLAUDE.md
  - producers/js/test/transport.test.js
  - producers/js/test/replies.test.js
  - producers/js/test/config.test.js
  - producers/js/test/utf8.test.js
  - producers/js/test/fake-ws.js
  - .github/workflows/ci.yml
  - docs/schema.md
  - inspector-model/src/commonMain/kotlin/dev/inspector/model/Wire.kt
  - docs/decisions/capture.md
type: feature
ordinal: 162000
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

## Read before building
Decision files: `docs/decisions/capture.md`, `release.md`, `devices-and-stream.md`, `shared-rules.md`, `exports.md`, `daemon-and-mcp.md`, `twins-and-api.md`, `web-ui.md`.

## Design checklist (whole item)

| # | Answer |
|---|---|
| 1 Module | `producers/js/` is a plain folder, not a Gradle module, so `structure/*.txt` does not change.<br>Kotlin gets tests only: the strict decode in `:inspector-model`, defaults parity in `:inspector-core`, emulator profiles in `:inspector-stream`.<br>The daemon and web UI get wording changes (`streaming`-at-0 ticket).<br>One mirror change: the duplicates exclusion. |
| 2 Public API | **Kotlin:** none, and no model constant. No `api/*` golden names `BodyOmission` (grep: 0 hits in all six), and `:inspector-model` is the twin exemption. twins-and-api.md: "`:inspector-model` needs no twin because both members of the core pair `api` it".<br>**JS:** `start`, `signal`, `stats` and `stop` are a new consumer surface, versioned by the guide. |
| 3 Wire/schema | No new field.<br>`reqBytes` gains one case: `streaming` with `reqBytes: 0` means "not measured".<br>The round trip checks the live path and the REST path. |
| 4 Hot path | Restated for one thread (below).<br>The queue is bounded by frames **and** characters, drops the oldest, and counts its drops. |
| 5 Security | The bind is unchanged.<br>The producer dials loopback or `10.0.2.2` only.<br>`signReq` is always refused, so JS has no signing hook.<br>No control route is called. |
| 6 Platforms | No Kotlin target change.<br>No commas in backtick test names. |
| 7 Dependencies | No packages; Node built-ins only.<br>The global `WebSocket` is checked by CI's first producer step.<br>The producer never `require`s `react-native`. |

## Layout and CI

```
producers/js/
  AGENTS.md + CLAUDE.md -> AGENTS.md   module-guide shape: names decision files, no **-led paragraph
  inspector-producer.js       the core (this ticket): transport, config, signal(), the canary; CommonJS, < 600 lines
  inspector-producer-xhr.js   the capture (KIN-164): requires the core; CommonJS, < 600 lines
  inspector-bundle-check.js   consumer release-CI check, < 600 lines
  fixtures/README.md
  fixtures/frames/*.json      { "scenario": {...}, "frames": [ <WireMsg>, ... ] }
  fixtures/defaults.json      allowlist, 262144 cap, 65536 payload cap, content-type cases
  fixtures/emulator-profiles.json
  test/fake-xhr.js            both RN event models (_old/_new, RN$useBuiltInEventTarget)
  test/fake-ws.js, test/*.test.js, test/live-roundtrip.js
docs/REACT-NATIVE.md          consumer guide, versioned like INTEGRATION.md
.github/workflows/producer-js.yml
```

**CI, `web` job, before Gradle** (Node 22, `ci.yml:128-130`):
1. `node -e "process.exit(typeof WebSocket==='function'?0:1)"` — checked first and by name, because the job's `site` measurement already opens Node's global `WebSocket` (`scripts/lib/measure-cdp.js:88,118`, reached by `measure-web-ui.js --only site`), and the producer's live round trip (KIN-166) will. The producer's unit tests use `fake-ws.js`, not the global;
2. `node --test producers/js/test/*.test.js`, a shell glob of explicit files.

Every test that iterates fixtures fails on zero.

## This ticket builds
- the folder, `AGENTS.md` (+ the `CLAUDE.md` symlink) and `inspector-producer.js`'s transport half:
  - config, loopback, platform from config, the log lines, `stats()`;
  - the ring, backoff, `seq`/`mono`, `signal()`, both replies;
  - the canary's top-level use;
  - UTF-8 and base64 helpers;
- the two CI steps;
- the `schema.md` and `Wire.kt` corrections below.

Android emulator detection from `Platform.constants` comes with the shared profiles in the fixtures ticket. Until then, Android without `platform` refuses, as below.

## Efficiency contract (the parts this ticket owns)
| Where | Work |
|---|---|
| Memory | A ring of 512 frames **and** 8 Mi chars, whichever binds first, dropping the oldest. **The producer's own queue** is therefore about 16 MB at worst, at 2 bytes per char; Kotlin's is 512 × 512 KB = 256 MB. What the platform's socket buffers after `ws.send` is not in that figure; see the bound table below. |
| Flush | At most 256 Ki chars of frames per tick. Where `ws.bufferedAmount` is a number, flushing waits while it is above 1 MiB (KIN-172). |

**What the socket holds beyond the queue** (architect, 2026-10-30, ruled by the maintainer; the gate is built in KIN-172):

| Runtime | Producer's queue | Socket buffer after `ws.send` |
|---|---|---|
| Node, browsers | about 16 MB | 1 MiB plus one frame: `bufferedAmount` is maintained there, and the gate holds frames in the ring, which drops the oldest and counts it. |
| React Native 0.79.6, Android | about 16 MB | At most 16 MiB of UTF-8. `WebSocket_old.js:92` and `WebSocket_new.js:92` declare `bufferedAmount` and nothing assigns it, so the gate sees `undefined` and does nothing. okhttp 4.9.2's `RealWebSocket.send` calls `close(1001)` once its queue would pass 16777216 bytes, and drops every later send silently; `WebSocketModule.kt:219-242` ignores the result. JS hears of it when okhttp fails the socket: probably within 10 s (`writeTimeout`, `WebSocketModule.kt:84`, read from bytecode, not measured), at most 60 s (`CANCEL_AFTER_CLOSE_MILLIS`). Frames sent in that window are lost and not counted, as for any dying socket. |
| React Native 0.79.6, iOS | about 16 MB | **No bound the producer can see.** `RCTWebSocketModule.mm:104-107` hands strings to SocketRocket 0.7.1 and ignores the result; its buffer was not read (no pods installed). Measured on the simulator in KIN-170. |

A drain signal that would bound iOS needs a wire change (a `ping`/`pong` pair). It is a ROADMAP row, not part of this item.

**What is not claimed:** that any of this is free. The dedicated worker is Kotlin's mechanism. The aim is unchanged: the app never blocks, waits or meaningfully allocates.

## Config, platform, logs

```
start({ appId, appVersion, platform?, reactNative?, host?, port = 8099, buildType = 'debug',
        device?, osVersion?, captureAllBodies = false })  -> boolean
signal(tag, name, data?)
stats() -> { state, url, sessionId, dropped, lastError }
stop()
```

| Rule | Detail |
|---|---|
| Host | **Accepted:** `127.0.0.1`, `localhost`, `::1`/`[::1]`, `10.0.2.2`. An IPv6 host is bracketed in the `ws://` URL, and `config.test.js` has that case.<br>**Default:** `10.0.2.2` on `android-emulator`, otherwise `127.0.0.1`. That is Kotlin's `defaultDaemonHost()`, devices-and-stream.md. |
| `adb reverse` | Physical Android is in scope. The guide says to run `adb reverse tcp:8099 tcp:8099`: required on a device, optional on an emulator, and with it pass `host: '127.0.0.1'`. |
| Platform | From config.<br>**Android:** without `platform`, `reactNative` is required, and `Platform.constants` is read (fixtures ticket). Without either, refuse.<br>**iOS:** `platform` is required. |
| Start | `start()` never throws; it returns `false` and installs nothing on a refusal. A missing `XMLHttpRequest` makes it return `false`. |
| Logs | Every line is prefixed `[inspector]`. Only refusals use `console.error` (LogBox).<br>**Refusals:**<br>- `not started: host "<h>" is not allowed. Use 127.0.0.1, localhost, ::1 or 10.0.2.2.`<br>- `not started: on iOS pass platform: 'ios-simulator' to start(). It cannot be detected without a native module.`<br>- `not started: pass reactNative: require('react-native') or platform`<br>**`console.info`, once each:**<br>- per session: `connected to <url>, session <id>`<br>- per outage: `daemon not reachable at <url>; retrying, up to 512 calls are kept`<br>- per outage: the first drop<br>- on return: `reconnected; <n> calls were dropped while the daemon was away`<br>Drops use `info`, not `warn`, so the app's UI is not nagged about the inspector's completeness. |

## Wire discipline
| Rule | Design |
|---|---|
| Framing | One `JSON.stringify(msg)` per `ws.send`, never an array. |
| Integers | `mono = floor(performance.now())`, clamped non-decreasing. `performance.now` is fractional on device (measurement 6).<br>Every Long is an integer: `mono`, `ms`, `reqBytes`, `resBytes`, `seq`, `bytes`. |
| `seq` | **The producer assigns it.** One counter for calls and signals, taken right after `mono`. One thread makes it exact program order. |
| Session | Send `hello{client:{v:1,…}, resumeSessionId?}`, then flush only after `helloAck`.<br>**A full JS reload is a new session**, because `mono` restarts. Never persist `resumeSessionId` across one. |
| Backoff | 250 ms doubling to 5 s (`StreamSink.kt:354-355`), **reset on `helloAck`**. Kotlin resets when a session ends without exception (`StreamSink.kt:203`), so this reset is the JS producer's own choice and is not cited to Kotlin. |
| Queue | The ring above. Frames queued while disconnected go out after the next `helloAck`, as Kotlin's do.<br>`docs/schema.md:260` and `Wire.kt:21-22` ("NOT replayed") are false today; this ticket corrects both.<br>A frame handed to `ws.send` before the socket dies is lost and **not** counted, on both producers; `schema.md` says so. |
| `signReq` | Answered directly, outside the queue, with `signRes{requestId, error}`.<br>Error: `this app's JavaScript producer cannot regenerate per-request headers. Untick "regenerate per-request headers (re-sign)" and replay without re-signing.`<br>It names the fix, as `Replayer.kt:121-122` does. |
| `signalReq` | `signalErr{requestId, error:"no provider for <tag>/<name>; registered: none"}`, the form in `Recorder.kt:241-242`. |
| Dead daemon | Socket construction, `send`, `onerror` and `onmessage` are each in `try/catch`. Nothing reaches the app. |

**`signal(tag, name, data)`:**
- **Encoding**, as Kotlin's: a string goes through `JSON.stringify(str)`, an object through `JSON.stringify(obj)`, and if that throws, `JSON.stringify(String(obj))`.
- **`bytes`** is the UTF-8 length.
- **Over 64 KB:** the wire carries the **prefix bytes**, with `dataTruncated: true`. They are text when valid UTF-8, and `dataB64` when the cut splits a character (`Recorder.kt:432`, `StreamSink.kt:246-252`). The JSON-string form at `Recorder.kt:418` is the in-memory row only.
- **No merging:** no 150 ms merge and no dedup in v1. Kotlin does both (`InspectorConfig.kt:84-87`); the guide and the fixtures say JS does not.
- **Before `start()`:** ignored.
- **Never throws.**

**Canary:** `INSPECTOR_JS_CANARY_<fixed random>`, assigned at module top level: `globalThis.__INSPECTOR_PRODUCER__ = { canary: CANARY, version }`. Measurement 3: a constant that is used survives Hermes bytecode, and an unused one is removed.

**UTF-8 and base64:**
- encoding uses `TextEncoder`, which Hermes has (measurement 2), for prefixes up to 256 KB;
- counting is hand-rolled, chunked and allocation-free;
- decoding is hand-rolled, because there is no `TextDecoder`;
- base64 is hand-rolled;
- Node's `TextEncoder`, `TextDecoder` and `Buffer` are the test oracles.

## Spec corrections in this ticket
- `docs/schema.md`:
  - wire rows for `signReq` and `signRes` (`Wire.kt:133,148`);
  - `reqBodyOmitted` (line 47) lists `discarded`;
  - the disconnected-queue rule is corrected;
  - a "Second implementations" rule: one message per frame, Longs are integers, a fractional value drops the frame.
- `Wire.kt:21-22`: comment corrected. No API change.

## Decision paragraph (capture.md, under a "**JavaScript producers.**" lead)
Integers on the wire, and a reload is a new session. Pinned by `transport.test.js`.

## Tests (each shown red)
| File | Holds | Red by |
|---|---|---|
| `transport.test.js` | hello first; no array frame; flush after ack; the resume id; the backoff sequence and its reset on ack; the ring by frames and by chars, with its drop count; flush ≤ 256 Ki chars per tick; a dead daemon never throws; `bye` on `stop` | backoff cap removed; drop removed; two frames joined; flush unbounded |
| `replies.test.js` | both replies' exact wording and `requestId`, sent with a full queue | handler deleted |
| `config.test.js` | refusals and their exact log lines; the `[::1]` URL; default hosts | allowlist removed |
| `utf8.test.js` | count, encode, decode and base64 against Node's oracles, including surrogates and a split multibyte prefix | chars counted instead of bytes |
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 producers/js/ exists with AGENTS.md, its CLAUDE.md symlink and inspector-producer.js under 600 lines; :repo-checks stays green
- [x] #2 Every frame the producer sends is one JSON object per WebSocket send, never an array, and hello is the first
- [x] #3 Frames are flushed only after helloAck; a reconnect sends resumeSessionId; backoff runs 250, 500, 1000, 2000, 4000, 5000 ms and resets on helloAck
- [x] #4 The send queue holds at most 512 frames and 8 Mi chars, drops the oldest, and stats().dropped counts every drop
- [x] #5 A dead or refusing daemon never blocks or throws into the app
- [ ] #6 mono, ms, reqBytes, resBytes, seq and bytes are JSON integers, and seq is one counter for calls and signals taken right after mono
- [x] #7 signReq is answered with a signRes carrying the requestId and the exact error text in the ticket; signalReq with a signalErr reading 'no provider for <tag>/<name>; registered: none'
- [x] #8 start() refuses a host outside 127.0.0.1, localhost, ::1 and 10.0.2.2, iOS without platform, and Android with neither reactNative nor platform, each with its exact [inspector] line, installs nothing and never throws
- [x] #9 signal() over 64 KB sends the prefix bytes with dataTruncated, as text when valid UTF-8 and as dataB64 when the cut splits a character
- [x] #10 The canary is assigned at module top level to globalThis.__INSPECTOR_PRODUCER__
- [x] #11 CI's web job checks typeof WebSocket and runs node --test over producers/js/test before Gradle
- [x] #12 docs/schema.md gains signReq and signRes, lists discarded, states the second-implementation rules and the corrected disconnected-queue rule; Wire.kt:21-22 is corrected
- [x] #13 Each test in the ticket's table is shown red as stated, then restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob, ready for QA. Not committed.

Files: producers/js/{AGENTS.md, CLAUDE.md -> AGENTS.md, inspector-producer.js (465 lines), test/fake-ws.js, test/transport.test.js, test/replies.test.js, test/config.test.js, test/utf8.test.js}; .github/workflows/ci.yml (two steps in the web job, after Set up Node, before any Gradle step); docs/schema.md; inspector-model/.../Wire.kt (comment only); docs/decisions/capture.md (the **JavaScript producers.** paragraph, appended at the end).

Green: node --test producers/js/test/*.test.js -> tests 41, pass 41, fail 0 (Node 23.3.0 locally; no Node-23-only API used). node scripts/check-web-static.js -> 151 checks, 0 failed. ./gradlew :repo-checks:test :inspector-model:jvmTest -> BUILD SUCCESSFUL; the repo-checks suites ran fresh (AgentGuideTest 5, DecisionsTest 5, FileBudgetTest 1, NoTodoTest 1, all 0 failures) with producers/ listed by git ls-files --others.

Red, each by mutating the real file and restoring it byte-identical (cmp), re-run against the final file:
- backoff cap removed (backoffMs * 2 uncapped): transport fail 1, 'backoff runs ...' got 8000, 16000, 32000 where 5000 was expected
- drop removed (the ring's while loop deleted): transport fail 3, dropped 0 where 88 was expected; char test kept 12031182 chars; the log ladder lost the first-drop line
- two frames joined (flush sends '[a,b]'): transport fail 7; first is 'one object per send: [{"type":"signal"...' actual false
- flush unbounded (budget break deleted): transport fail 1, 'drained over 1 ticks'
- signReq handler deleted: replies fail 1 (sent 1 where 2 expected); signalReq handler deleted: replies fail 1
- allowlist removed: config fail 5, one for each refused host (start returned true)
- chars counted instead of bytes (utf8Length returns end - from): utf8 fail 4, 'café' 4 where 5 was expected
- CI WebSocket step: node --no-experimental-websocket -e <check> exits 1; without the flag it exits 0

Also measured: the built :inspector-model classes run through InspectorJson from a small Java probe. mono 1234 decodes; 1234.5 and 1234.0 throw JsonDecodingException 'Unexpected symbol . in numeric literal at path: $.signal.mono'; an array frame throws 'Expected JsonObject, but had JsonArray'. Every frame kind the producer sends (hello, signal small/object/truncated-text/truncated-b64, signRes, signalErr, bye) decodes as its WireMsg type. This backs schema.md's new 'Second implementations' rules.

Choices the ticket left open, for QA and the architect:
- The first-drop log line had no exact text. It reads '[inspector] the queue is full while the daemon is away; dropping the oldest calls', once per outage, and only during an outage. A drop while connected is counted in stats().dropped but not logged.
- start() also refuses with console.error, beyond the three listed refusals: missing appId/appVersion strings ('not started: pass appId and appVersion as strings'), a port that is not 1..65535, a missing XMLHttpRequest ('not started: XMLHttpRequest is not available here', which the ticket requires to return false), and a missing WebSocket.
- Android without platform refuses with the reactNative line ('pass reactNative: require(...) or platform') even when reactNative is passed, until KIN-163 adds the Platform.constants rule in platformFrom(). iOS is told by reactNative.Platform.OS === 'ios'.
- device/osVersion default to 'unknown' when config gives none; nothing is read from reactNative yet.
- stop() sends bye only once acked, closes the socket, clears timers and discards the queue without flushing it. stats().dropped is cumulative for the producer's life.
- signal(tag, name) with data undefined sends no payload and no bytes; null is a payload ('null').
- Test seam: module.exports._test = { createProducer(deps), utf8Length, utf8Prefix, utf8Decode, base64, CANARY, RING_FRAMES, RING_CHARS, FLUSH_CHARS }. Not consumer surface; config.test.js holds the public keys to exactly start, signal, stats, stop.

Headroom risk for KIN-164: the file is 465 of 600 lines, leaving 135 lines for the XHR wrap, the allowlist predicate and KIN-163's emulator rule.

Not run: :repo-checks covers the folder, but there is no Gradle task for the JS tests, so only CI's web job runs them. check-client-names.sh was not run (no terms file in this session). No device or Hermes run: measurements 2, 3 and 6 are the ROADMAP's, not mine.

QA final (Doug), after fix rounds. Live checks against a real daemon: resume after restart with the same session id; ring keeps the newest 512 (archive 517 rows, o1499..a5, seq/mono in order); log lines print once each; replay re-sign refused in 172 ms (430 ms under a full ring) versus the 30 s timeout; signals/request gives 502 'no provider for cache/orders; registered: none'; >64 KB signals arrive byte-exact as prefix bytes, dataB64 on a split character; every test guard red in a snapshot. Suites: node --test producers/js/test/*.test.js 44/44; check-web-static.js 151/0; :repo-checks:test 39/0; :inspector-model:jvmTest 172/0. AC #6 stays unticked: Verified for mono, seq and bytes; ms, reqBytes and resBytes are not observable until KIN-164 adds txn frames. DoD #1 and #3 stay unticked: DoD #1 needs AC #6; the name scan (8 terms, no match) ran in QA round 1 only, so rerun it before commit. Not verified: Node 22 (none on the machine), Hermes, devices.
<!-- SECTION:NOTES:END -->
