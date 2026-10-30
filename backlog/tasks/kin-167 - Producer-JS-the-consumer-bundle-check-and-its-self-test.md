---
id: KIN-167
title: 'Producer JS: the consumer bundle check and its self-test'
status: To Do
assignee: []
created_date: '2026-10-30 13:36'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-162
modified_files:
  - producers/js/inspector-bundle-check.js
  - producers/js/test/bundle-check.test.js
  - docs/decisions/release.md
type: feature
ordinal: 167000
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
`producers/js/inspector-bundle-check.js`, the check consumers run in their own release CI, plus its tests and the `release.md` paragraph on JavaScript release safety.

## Measurement 3 and the rules it sets
**Survives** (a constant that is used):
- `.hbc` from `hermesc -O -emit-binary`: ascii=1, utf16le=0;
- the JS bundle: ascii=1;
- the release APK's `assets/index.android.bundle` (Hermes bytecode v96): ascii=1;
- the raw `.apk`: ascii=1, because the bundle is stored uncompressed;
- the iOS bundle and `.hbc`: ascii=1;
- a module pulled in by a side-effect import (`import './x'`).

**Removed:**
- a module-local unused constant, and a string in a function never called (by `hermesc -O` and by the minifier);
- a whole module imported only for an unused binding.

| Rule | Why |
|---|---|
| The canary is assigned at module top level: `globalThis.__INSPECTOR_PRODUCER__ = { canary: CANARY, version }` (transport ticket). | It is a used reference, so its presence is tied to the module running. |
| The guide's step 2 is a **side-effect** import or `require`, never a named import. It imports the capture file, `inspector-producer-xhr.js`, which pulls in the core (KIN-164). It sits in one code block with step 1, commented `// required: without this the module is listed but never runs`. | An unused binding drops the whole module. Capture's `require` of the core is a used binding, so the core and its canary survive with it. |
| **One canary, in the core** (`inspector-producer.js`). The capture file holds none. | Every bundle holding producer code holds the core, because capture requires it. The check therefore proves that producer code is in the bundle. It does not prove that capture is, and need not: a release bundle with the core alone already fails. |
| `getModulesRunBeforeMainModule` **appends** after `InitializeCore` and never replaces the list. | The default list is `[InitializeCore]`. |

## `inspector-bundle-check.js <paths…>` and `--self-test`
- **One copy of the canary.** It is read from the `inspector-producer.js` beside the script. A missing producer file exits 2.
- **Encodings.** It searches ASCII and UTF-16LE. `release.md`'s iOS paragraph is the precedent for UTF-16.
- **Containers.** It walks directories, and reads zip containers (`apk`, `aab`, `ipa`) with a small central-directory reader plus `zlib.inflateRawSync`. An unsupported entry exits 2, "unproven".
- **Exit codes:** 1 when found, 0 when clean.
- **Hermes bytecode** is read like any other file, because the canary survives there. The guide states the measured basis: React Native 0.79.6, Hermes bytecode v96.
- **`--self-test`** builds a plain bundle, a UTF-16 bundle and a deflated zip. It must find the canary in each, and nothing in their clean twins.

## Decision paragraph (release.md)
**For JavaScript, release safety is the harness's convention, and the bundle check is the consumer's to run.**
- There is no `__DEV__` refusal.
- The canary is a used top-level reference.
- The guide requires a side-effect import.
- `check-release-clean.sh` is not reused, because it cannot see dex or Hermes bytecode.
- Nothing may call JS release safety enforced.

Pinned by `bundle-check.test.js`.

## Tests (each shown red)
| File | Holds | Red by |
|---|---|---|
| `bundle-check.test.js` | Runs the script as a child process for every exit code: found (plain, UTF-16, deflated zip) gives 1; clean gives 0; a missing producer file gives 2; an unsupported zip entry gives 2; `--self-test` gives 0. | searching ASCII only (the UTF-16 case fails); `--self-test` with its finds removed |
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 inspector-bundle-check.js reads the canary from the inspector-producer.js beside it and exits 2 when that file is missing
- [ ] #2 It exits 1 on a plain, a UTF-16LE and a deflated-zip input holding the canary, and 0 on the same inputs without it
- [ ] #3 An unsupported zip entry exits 2, unproven, never 0
- [ ] #4 --self-test exits 0 only when it finds the canary in each built input and nothing in the clean twins
- [ ] #5 The script stays under 600 lines and uses Node built-ins only
- [ ] #6 release.md carries the JavaScript release-safety paragraph, and nothing calls JavaScript release safety enforced
- [ ] #7 bundle-check.test.js is shown red by searching ASCII only, then restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
