---
id: KIN-163
title: >-
  Producer JS: shared fixtures, the strict Kotlin decode and the shared emulator
  profiles
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-30 13:36'
updated_date: '2026-10-30 15:30'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-162
modified_files:
  - producers/js/fixtures/README.md
  - producers/js/fixtures/defaults.json
  - producers/js/fixtures/emulator-profiles.json
  - producers/js/inspector-producer.js
  - producers/js/test/fixtures.test.js
  - producers/js/test/emulator.test.js
  - producers/js/test/defaults.test.js
  - inspector-model/build.gradle.kts
  - inspector-core/build.gradle.kts
  - inspector-stream/build.gradle.kts
  - >-
    inspector-stream/src/androidHostTest/kotlin/dev/inspector/stream/EmulatorDetectionTest.kt
  - docs/decisions/devices-and-stream.md
type: feature
ordinal: 163000
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
- the shared fixture folder, `producers/js/fixtures/`, with `README.md` (the format), `frames/*.json` for hello and signal (the transaction frames come with the XHR ticket), `defaults.json` and `emulator-profiles.json`;
- the Kotlin checks that read it;
- the JS emulator rule;
- the JS embedded defaults that `defaults.json` holds.

**Which file (the two-file split, ruled 2026-10-30; see KIN-164).** Both go in the **core**, `inspector-producer.js`:
- the emulator rule, because it decides `platform`, and `platform` decides `start()`'s default host. A consumer running the core alone must still dial the right address;
- of the defaults, only the 65536 signal payload cap, which `signal()` uses.

The content-type allowlist and the 262144 body cap belong to capture. They are embedded in `inspector-producer-xhr.js`, and their JS checks are written in KIN-164, where that file is created. This ticket still writes them into `defaults.json`, and `ProducerDefaultsTest` still holds Kotlin to all three.

**Headroom:** the core is about 480 lines after KIN-172. This ticket adds about 30, and KIN-164's hook adds about 25. That leaves about 65 under the 600-line cap. No `structure/budgets.txt` entry.

## Emulator rule (Android `platform` when config gives none)
- **What RN exposes:** `Fingerprint`, `Model`, `Brand` (and `Manufacturer`), per React Native 0.79.6 `AndroidInfoModule.kt:55-58`. There is no `HARDWARE`, `BOARD`, `DEVICE` or `PRODUCT`.
- **What JS does:** takes the product and device from the fingerprint (`brand/product/device:release/...`) and applies `EmulatorDetection.kt:59-72` over five fields.
- **The profiles move** out of `EmulatorDetectionTest` into `fixtures/emulator-profiles.json`. Each keeps its `note` (today's comment) and carries per-runner expectations, `kotlin` and `js`.
- **One profile differs: "hardware alone is enough".** It is `kotlin: true`, `js: false`, with reason "RN exposes no HARDWARE". Over the nine profiles, the three AVDs, Cuttlefish and Genymotion fire for JS, and the three non-emulators do not.
- **Provenance:** the JSON marks which profiles were transcribed from `adb shell getprop` and which are representative, as the test does today.

## Fixtures and the Kotlin checks
| Test (module) | Holds | Red by |
|---|---|---|
| `ProducerFramesTest` (`:inspector-model` jvmTest) | **Decode:** every frame decodes with `Json(InspectorJson){ ignoreUnknownKeys = false }` as `WireMsg`.<br>**Values:**<br>- `id` matches `^[0-9a-f]{8}$`<br>- `v == SCHEMA_VERSION` is present on `txn`, `signal` and `client`<br>- the omission is a `BodyOmission` constant, found by reflection<br>- `platform` is a `Platforms` constant<br>- `mono` is an integer ≥ 0<br>**Mutants built in the test, each refused:** `calId` (the daemon archived exactly that silently, live), a 9-character id, an uppercase id, an unknown omission, a fractional `mono`, a missing `v`, an array frame.<br>**Zero frames fails.** | `InspectorJson` swapped in, so `calId` passes; each value check dropped in turn |
| Gradle wiring | A **new provider class in each build script**: there is no `buildSrc`, and the daemon redeclares its own at `inspector-daemon/build.gradle.kts:72`.<br>- `@InputDirectory` with `PathSensitivity.RELATIVE` on `producers/js/fixtures`.<br>- Model: beside `MirrorCasesArgument` (`inspector-model/build.gradle.kts:42-57`).<br>- Core: its `jvmTest`.<br>- Stream: `testAndroidHostTest` (`inspector-stream/build.gradle.kts:64-69`, which uses `inputs.files` today), with a `jvmArgumentProviders` entry that passes the path. | Marked `@Internal`, an edited fixture leaves the task up to date (measured this way for the mirror cases, shared-rules.md) |
| `ProducerDefaultsTest` (`:inspector-core` jvmTest) | `InspectorConfig()` defaults equal `defaults.json`: the `captureContentTypes` list, `bodyCaptureMaxBytes` 262144 and `signals.maxPayloadBytes` 65536.<br>`capturesBody` (`BodyCapture.kt:34-41`: prefix match plus `+json`/`+xml`) gives each content-type case's answer. | one allowlist entry edited in the JSON |
| `EmulatorDetectionTest` (`:inspector-stream`) | Iterates the JSON's `kotlin` expectations with all seven fields. Zero profiles fails. | one expectation flipped |
| `fixtures.test.js` | The producer emits exactly each fixture's `frames`, driven with deterministic clock and id deps. Includes a signal over 64 KB whose cut splits a multibyte character, so it goes as `dataB64`. | `Math.floor` removed |
| `emulator.test.js` | The JS rule gives each profile's `js` expectation. | the fingerprint-derived product removed |
| `defaults.test.js` | The core's embedded payload cap equals `defaults.json`. KIN-164 extends this file with the capture file's allowlist, body cap and predicate. | the embedded payload cap changed |

## Decision paragraph (devices-and-stream.md)
- **Add:** a paragraph on the five-field emulator rule and the shared profiles.
- **Edit:** the sentence "Every emulator profile in `EmulatorDetectionTest` was transcribed…" now names the JSON file.
- **Pinned by:** `EmulatorDetectionTest` and `emulator.test.js`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 producers/js/fixtures/ holds README.md, frames for hello and signal, defaults.json and emulator-profiles.json
- [x] #2 ProducerFramesTest decodes every frame with a strict Json (unknown keys refused) and checks id, v, the omission constant, platform and integer mono; it refuses each in-test mutant (calId, 9-char id, uppercase id, unknown omission, fractional mono, missing v, array frame) and fails on zero frames
- [x] #3 Swapping InspectorJson into ProducerFramesTest turns it red
- [x] #4 Each Kotlin test that reads producers/js/fixtures declares it an @InputDirectory with PathSensitivity.RELATIVE through a provider class in its own build script; with the property marked @Internal an edited fixture leaves the task up to date
- [x] #5 ProducerDefaultsTest holds InspectorConfig's captureContentTypes, 262144 and 65536 equal to defaults.json and runs capturesBody over its cases
- [x] #6 EmulatorDetectionTest reads its profiles from emulator-profiles.json, and the JS rule reads the same file; 'hardware alone is enough' is kotlin true, js false, with its reason
- [x] #7 On Android without platform, start() with reactNative labels an emulator android-emulator and dials 10.0.2.2 by default
- [x] #8 devices-and-stream.md carries the five-field rule and names the JSON file
- [x] #9 Each test in the ticket's table is shown red as stated, then restored
- [x] #10 The JS emulator rule and the embedded 65536 payload cap are in inspector-producer.js, which stays under 600 lines with no budgets.txt entry
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob, ready for QA. Not committed.

Files: producers/js/fixtures/{README.md, defaults.json, emulator-profiles.json, frames/hello.json, frames/hello-android-emulator.json, frames/signals.json, frames/signal-truncated-b64.json}; producers/js/inspector-producer.js (504 lines, was 478); producers/js/test/{fixtures,emulator,defaults}.test.js (new), config.test.js (the interim "Android without platform is refused" test replaced by a refusal for an OS that is neither android nor ios); producers/js/AGENTS.md (one key-files row); inspector-model/src/jvmTest/.../ProducerFramesTest.kt; inspector-core/src/jvmTest/.../ProducerDefaultsTest.kt; inspector-stream/src/androidHostTest/.../EmulatorDetectionTest.kt (reads the JSON); ProducerFixturesArgument in inspector-{model,core,stream}/build.gradle.kts (stream: on testAndroidHostTest via jvmArgumentProviders); docs/decisions/devices-and-stream.md.

Green: node --test producers/js/test/*.test.js -> tests 62, pass 62. check-web-static.js -> 151 checks, 0 failed. ./gradlew :inspector-model:jvmTest :inspector-core:jvmTest :inspector-stream:testAndroidHostTest :repo-checks:test --rerun -> model 174/0, stream host 10/0, repo-checks 39/0; core had 4 OkHttpCaptureTest failures (JobCancellationException, LazyStandaloneCoroutine is cancelling) under that parallel run, then 79/0 on two --rerun runs alone. Not touched by this ticket; looks like a load flake.

Red, each mutated and restored (cmp/diff against a snapshot):
- ProducerFramesTest, InspectorJson swapped in: 'accepted mutants: [calId, an unknown key]'
- id check dropped: [a 9-character id, an uppercase id]; v check dropped: [missing v on a signal, on a client, on a txn]; omission check dropped: [an unknown omission]; platform check dropped: [an unknown platform]; mono check dropped: [a negative mono]. A fractional mono is refused by the strict decode itself (Long), so the mono check needed the negative mutant to be provable; the array frame is refused by the decode too ('Expected JsonObject'), so no separate guard was kept for it.
- zero frames (frames/*.json moved out): Kotlin 'no frame fixtures in …/frames' (2 failed); fixtures.test.js 'no fixtures in …'
- @InputDirectory, per module: provider marked @Internal, fixture edited -> task UP-TO-DATE, BUILD SUCCESSFUL (red); restored, same edit -> task runs and FAILS: model 'signals.json[1]: does not decode strictly: Encountered an unknown key calId'; core allowlist 'application/jwt' -> 'application/jwk' fails expected/actual list; stream 'hardware alone is enough: expected false, got true'. Each fixture restored -> green.
- ProducerDefaultsTest: allowlist entry edited (above); a content-type case flipped -> 'image/svg+xml: expected false'
- EmulatorDetectionTest: expectation flipped (above); zero profiles -> 'holds no profiles' and the both-answers check fails; emulator.test.js 'holds no profiles'
- fixtures.test.js, Math.floor removed: signals.json and signal-truncated-b64.json fail, mono 1500.75 / 2000.4 where 1500 / 2000 expected
- emulator.test.js, fingerprint-derived product removed: 'the product alone is enough' fails (see deviation); product and device removed: that and Cuttlefish fail
- defaults.test.js, embedded cap 65535: '65535 !== 65536'

Deviations, for QA and the architect:
- A tenth profile, 'the product alone is enough' (provenance constructed). On the nine moved profiles, removing only the fingerprint-derived product left emulator.test.js green, 11/11, because every real image also fires on the device or the model. So the ticket's stated red could not hold. The new profile isolates the product the way 'hardware alone is enough' isolates the hardware. kotlin true, js true.
- provenance has three values, not two: transcribed, representative, and constructed for 'hardware alone' and 'nothing known' (and the new profile), which are neither read off an image nor vendor values. README and the decision paragraph say so.
- Manufacturer is exposed by RN but none of the five checks reads it, so the profiles carry no Manufacturer and the JS test passes Fingerprint, Model and Brand only.
- defaults.json's cases include 'application/jsonp' -> true, which is Kotlin's prefix match as it stands; KIN-164's predicate must agree with that.
- Frame fixtures were generated by running each scenario once through the producer, then checked by hand, with bytes and base64 against Node's Buffer (65536-byte prefix ending 0xc3, bytes 65539). signal-truncated-b64.json is 88 KB; JSON is not under FileBudgetTest.

Not run: ./gradlew build -Pinspector=off (no consumer signature moved); iOS targets; Node 22 (only 23.3 here; nothing Node-23-only used: fs, path, node:test, destructuring defaults). The client-name scan ran on the working tree (8 terms, whole word, 21 files plus the branch name, no match), not via check-client-names.sh, whose range holds no commit yet. AGP auto-installed Android SDK Platform 36 into ~/Library/Android/sdk on the first host-test run (ANDROID_HOME set by env; the worktree has no local.properties).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), all Verified. AC1: b64 fixture decoded independently, 65539 bytes, 65536-byte prefix ending 61c3, invalid UTF-8. AC2-3: each value check removed returns its own mutant; InspectorJson swapped in lets calId through. AC4: @InputDirectory proven in model, core, stream; with @Internal the task stays UP-TO-DATE after a fixture edit, as built it re-runs and fails. AC5: ProducerDefaultsTest red on allowlist edit, cap 262143, application/jsonp false (jsonp captured is Kotlin's prefix match, BodyCapture.kt:37-38). AC6: profiles shared; hardware alone is kotlin true / js false with reason. AC7 live on host Node standing in for RN: API 34 AVD profile dialled ws://10.0.2.2:8099/ingest, archived platform android-emulator; Pixel control dialled 127.0.0.1, android-device. AC8: devices-and-stream.md paragraph accurate (RN AndroidInfoModule.kt:55-58 citation not checked by QA). AC9: every table row red. AC10: core 504 lines, no budget entry. DoD3: name scan clean on diff and branch. Suites: node 62/62; check-web-static 151/0; model 174/0; core 79/0; stream host 10/0; repo-checks 39/0. Not verified: iOS targets, real emulator or device, Node 22, full -Pinspector=off build.
<!-- SECTION:FINAL_SUMMARY:END -->
