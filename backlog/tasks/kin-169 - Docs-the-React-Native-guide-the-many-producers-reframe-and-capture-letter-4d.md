---
id: KIN-169
title: >-
  Docs: the React Native guide, the many-producers reframe, and capture letter
  4d
status: To Do
assignee: []
created_date: '2026-10-30 13:37'
updated_date: '2026-10-30 15:30'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-165
  - KIN-167
  - KIN-168
modified_files:
  - AGENTS.md
  - README.md
  - docs/ROADMAP.md
  - docs/REACT-NATIVE.md
  - docs/README.md
  - docs/STATUS.md
  - docs/decisions/capture.md
  - docs/decisions/repository.md
type: docs
ordinal: 169000
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
- the consumer guide;
- the reframe of `AGENTS.md`, README and the ROADMAP closing section, ruled 2026-10-30;
- the 4d paragraph in capture.md;
- the docs index;
- the measurement record in `docs/STATUS.md`.

## AGENTS.md (byte cap)
- **Budget:** 14,622 B today. `AgentGuideTest:110` fails at ≥ 15,000, so 377 B are free.
- **Moves** (each a move, not a copy, so `DecisionsTest` stays green), freeing about 898 B in total:
  - "The lettered rows are capture mechanisms…" (301 B) goes to the head of capture.md's capture roadmap, reworded so it does not dangle there;
  - the "`./gradlew dependencies --write-locks` from the root…" trap (220 B) goes to `docs/decisions/repository.md`.
- **Reframe** (wording accepted at the gate):

| Section | Text |
|---|---|
| What this is | "A network inspector that lives on the host. Producers in an app stream its calls to a daemon on the developer's machine, and the archive, web UI, findings, diff and MCP read one wire format whichever producer wrote a row. Primary support: **Compose Multiplatform apps using Ktor** (Kotlin libraries, with an in-app overlay) and **React Native apps** (two JavaScript files the team's harness injects; no overlay)." The words "one JavaScript file" were accepted at the gate. The two-file split (maintainer, 2026-10-30) changes them to "two JavaScript files", one byte more. |
| What it is not: scope bullet, appended | "The JavaScript producer sees XMLHttpRequest and fetch on the JS thread only: not image loads, native modules or SDKs, WebViews, or redirects the native stack follows." |
| What it is not: production bullet | "Not shipped to production, ever. For Kotlin this is enforced (Production safety below). For JavaScript it is a convention the consuming team's harness owns; Inspector cannot enforce it, and nothing may say it does." |
| Production safety heading | "## Production safety — two layers, Kotlin only", plus: "Neither layer reaches a JavaScript bundle. The producer stays out of a release build only because the harness does not inject it; its canary and inspector-bundle-check.js let the consumer verify that in their own release CI, where they run it (docs/decisions/release.md)." |
| Not modules | Add `producers/js/`. |

- **Over budget** means shortening the reframe, never raising the budget.
- **Edit `AGENTS.md`**, never the `CLAUDE.md` symlink.

## README and ROADMAP
- **README:** the same framing.
- **`docs/ROADMAP.md`'s closing section:** "capture stays Ktor-client-only until 4c is deliberately chosen" is rewritten to the many-producers framing.
- **`docs/ROADMAP.md` §7:** "The work is one file" and the not-doing bullet "One vendorable file is what the consumer asked for" are corrected to the ruling. The producer is two vendorable files, the core and capture, still with no package and no registry. The consumer's own constraint, "a single file they can vendor is the preferred way to ship", stays as they stated it.

## capture.md
- **4d paragraph.** Add "**4d — JavaScript producers.**" beside 4a, 4b and 4c. It covers the React Native producer (this item), with Node and the browser later.
- **Existing leads.** The "JavaScript producers" paragraphs that earlier tickets added take the 4d letter.

## `docs/REACT-NATIVE.md`
**Version line:** "Document version: v1 — <date>", with a changelog section, versioned the way `INTEGRATION.md` is.

**What the guide covers:**
- **vendoring:**
  - copy `inspector-producer.js` and `inspector-producer-xhr.js` into one folder of the harness, both from one release; `SHA256SUMS` lists both;
  - capture `require`s `./inspector-producer.js`, so the two must sit side by side under those names;
  - a mismatched pair says so in Metro's log and captures nothing;
- the two Metro steps in **one code block**:
  - step 1 appends the module to `getModulesRunBeforeMainModule` after `InitializeCore`, never replacing the list;
  - step 2 is a side-effect import **of `inspector-producer-xhr.js`**, commented `// required: without this the module is listed but never runs`. That one import pulls in the core;
  - `start({...})` is called on `require('./inspector-producer.js')`, in the same block;
- the two `console.error` lines a broken install shows, verbatim from KIN-164:
  - "nothing is captured: inspector-producer-xhr.js was not loaded…" means step 2 imports the core instead of capture;
  - "not capturing: … Copy both files from one release." means the two files come from different releases;
- the convention sentence, verbatim from the item: "For JavaScript, keeping the producer out of a release build is a convention your harness owns. Inspector does not enforce it." It also says Kotlin apps keep the enforced artifact swap;
- `start()` options;
- iOS needs `platform: 'ios-simulator'`;
- Android: emulator detection, and `adb reverse tcp:8099 tcp:8099` (required on a device, optional on an emulator, then `host: '127.0.0.1'`);
- what "connected" looks like in Metro's log: "if this line never appears, step 2 is missing";
- "Each full reload (r in Metro, or a red-screen reload) starts a new session. Pick the newest in the session picker; Fast Refresh does not.", once device acceptance has confirmed Fast Refresh;
- "Nothing is redacted. Authorization headers, cookies and tokens are written to ~/.inspector/sessions in plain text. Kotlin apps can turn redaction on; this producer cannot yet.";
- "Redirects followed by the native networking stack are invisible: you see one row with the final status. An axios retry is a separate row.";
- no 150 ms signal merge and no dedup;
- `streaming` at 0 means the upload's size was not measured;
- the minimum daemon line: "Use daemon <version> or later. An older daemon shows an upload whose size was not measured as empty; it still refuses to replay it.";
- the efficiency paragraph, with KIN-162's bound table:
  - the producer's own queue is about 16 MB at worst;
  - on Android, okhttp holds at most 16 MiB more and then closes the socket;
  - on iOS, the socket's buffer has no bound the producer can see while a daemon is attached but not reading (stopped with Ctrl-Z, for example), with KIN-170's simulator measurement;
  - "Do not leave the daemon suspended while the app runs.";
- the bundle check, with its measured basis;
- the re-sign note: untick "regenerate per-request headers (re-sign)" to replay a JS app's call.

**What it never says:** that JS release safety is enforced.

## Other docs
| File | Change |
|---|---|
| `INTEGRATION.md` | Not bumped: no Kotlin consumer action follows. |
| `docs/README.md` | Indexes the guide. |
| `docs/STATUS.md` | Records measurements 1, 2, 3, 4, 6 and 7 with their evidence and date (2026-10-30), Android and iOS separately, under Verified and Not verified. |
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 AGENTS.md carries the reframe in the ticket, lists producers/js/ under Not modules, and stays under 15,000 bytes with AgentGuideTest and DecisionsTest green
- [ ] #2 'The lettered rows…' now heads capture.md's capture roadmap and reads correctly there; the --write-locks trap is in repository.md; neither remains in AGENTS.md
- [ ] #3 The README and the ROADMAP closing section say Inspector is a host tool with many producers, with primary support for Compose Multiplatform and React Native apps
- [ ] #4 capture.md carries '4d — JavaScript producers.' beside 4a, 4b and 4c
- [ ] #5 docs/REACT-NATIVE.md v1 covers every point the ticket lists, including the convention sentence verbatim, and nothing in it, the README or AGENTS.md calls JavaScript release safety enforced
- [ ] #6 The guide shows both Metro steps in one code block, with step 2 a side-effect import of inspector-producer-xhr.js commented as required, and start() called on inspector-producer.js
- [ ] #9 The guide says to vendor both files side by side from one release, and quotes the 'nothing is captured' and 'not capturing' lines with what each means
- [ ] #10 The guide's efficiency paragraph carries the queue and socket bounds per runtime, including that iOS has no bound the producer can see while the daemon is attached but not reading
- [ ] #11 AGENTS.md's reframe says two JavaScript files, and ROADMAP §7 no longer calls the producer one file
- [ ] #7 docs/README.md indexes the guide; INTEGRATION.md is not bumped
- [ ] #8 docs/STATUS.md records measurements 1, 2, 3, 4, 6 and 7 with evidence, Android and iOS separately
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
QA note from KIN-163 (non-blocking): with Platform.OS 'web' the refusal says 'pass reactNative: require("react-native") or platform' although reactNative was passed. The wording should say the OS is neither Android nor iOS.
<!-- SECTION:NOTES:END -->
