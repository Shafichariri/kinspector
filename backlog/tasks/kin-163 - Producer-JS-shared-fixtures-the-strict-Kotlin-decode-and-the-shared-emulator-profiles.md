---
id: KIN-163
title: >-
  Producer JS: shared fixtures, the strict Kotlin decode and the shared emulator
  profiles
status: To Do
assignee: []
created_date: '2026-10-30 13:36'
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
| `defaults.test.js` | The producer's embedded allowlist, cap and payload cap equal `defaults.json`, and its predicate gives each case's answer. | one embedded entry changed |

## Decision paragraph (devices-and-stream.md)
- **Add:** a paragraph on the five-field emulator rule and the shared profiles.
- **Edit:** the sentence "Every emulator profile in `EmulatorDetectionTest` was transcribed…" now names the JSON file.
- **Pinned by:** `EmulatorDetectionTest` and `emulator.test.js`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 producers/js/fixtures/ holds README.md, frames for hello and signal, defaults.json and emulator-profiles.json
- [ ] #2 ProducerFramesTest decodes every frame with a strict Json (unknown keys refused) and checks id, v, the omission constant, platform and integer mono; it refuses each in-test mutant (calId, 9-char id, uppercase id, unknown omission, fractional mono, missing v, array frame) and fails on zero frames
- [ ] #3 Swapping InspectorJson into ProducerFramesTest turns it red
- [ ] #4 Each Kotlin test that reads producers/js/fixtures declares it an @InputDirectory with PathSensitivity.RELATIVE through a provider class in its own build script; with the property marked @Internal an edited fixture leaves the task up to date
- [ ] #5 ProducerDefaultsTest holds InspectorConfig's captureContentTypes, 262144 and 65536 equal to defaults.json and runs capturesBody over its cases
- [ ] #6 EmulatorDetectionTest reads its profiles from emulator-profiles.json, and the JS rule reads the same file; 'hardware alone is enough' is kotlin true, js false, with its reason
- [ ] #7 On Android without platform, start() with reactNative labels an emulator android-emulator and dials 10.0.2.2 by default
- [ ] #8 devices-and-stream.md carries the five-field rule and names the JSON file
- [ ] #9 Each test in the ticket's table is shown red as stated, then restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
