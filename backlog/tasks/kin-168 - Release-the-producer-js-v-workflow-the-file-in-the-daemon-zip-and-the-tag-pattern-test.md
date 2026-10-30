---
id: KIN-168
title: >-
  Release: the producer-js-v* workflow, the file in the daemon zip, and the
  tag-pattern test
status: To Do
assignee: []
created_date: '2026-10-30 13:37'
labels:
  - 'item:producer-react-native'
dependencies:
  - KIN-164
  - KIN-167
modified_files:
  - .github/workflows/producer-js.yml
  - .github/workflows/release.yml
  - inspector-daemon/build.gradle.kts
  - >-
    repo-checks/src/test/kotlin/dev/inspector/repochecks/ProducerReleaseWorkflowTest.kt
  - docs/decisions/release.md
type: feature
ordinal: 168000
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
The producer's own release path:
- a tag and workflow;
- the file in the daemon zip and the daemon smoke test;
- the `:repo-checks` test that keeps the two tag patterns apart.

## Release
**Workflow `.github/workflows/producer-js.yml`:**
- Trigger: `on.push.tags: ['producer-js-v*']`.
- Runs on ubuntu with Node 22:
  1. the `WebSocket` check;
  2. the node tests;
  3. a check that the tag equals `PRODUCER_VERSION` in **both** `inspector-producer.js` and `inspector-producer-xhr.js` (the two-file split, KIN-164). On a mismatch it fails and names the file that differs.
- Writes `SHA256SUMS` over every file it publishes, then:
  `gh release create … inspector-producer.js inspector-producer-xhr.js inspector-bundle-check.js docs/REACT-NATIVE.md SHA256SUMS --latest=false`.
- Why `--latest=false`: the README and `docs/ACCESS.md` link `/releases/latest` for the daemon zip.

**One version for the pair, caught three times:**
- on every PR, by `version.test.js` (KIN-164), which CI's web job runs;
- in the consumer's app, by capture's `_attach`, which refuses a core of another version and says so;
- at release, by the tag check above.

**Daemon zip.**
- In `inspector-daemon/build.gradle.kts`, `distributions.main.contents` copies `producers/js/*.js` (top level only, never `test/`) and the guide under `producers/js/`. **A glob, not a list**, so a third file cannot be left out.
- The `release.yml` "Smoke-test the artifact" step adds an `unzip -l` that lists them. It then loops over the repo's `producers/js/*.js` and `cmp`s each against the zip's copy, so the loop and the copy read the same glob.

**`:repo-checks` `ProducerReleaseWorkflowTest`.** It is a **hand-written line reading** of `on.push.tags` (there is no YAML parser), and the test says so.
- It uses GitHub glob semantics: `*` does not cross `/`.
- `producer-js-v0.1.0` matches nothing in `release.yml`.
- `v1.4.0` matches nothing in `producer-js.yml`.
- `--latest=false` is present in `producer-js.yml`'s `gh release create` and absent from `release.yml`'s.
- `producer-js.yml`'s `gh release create` names every top-level `producers/js/*.js` file in the repo.
- **Shown red** by adding `'*'` to `release.yml`'s tags, by dropping the flag, and by removing `inspector-producer-xhr.js` from the `gh release create` line.

## Decision paragraph (release.md)
- **Edit** "Cutting a daemon release — the tag is the trigger, and the only thing that ships this way" (line 172): it names the daemon and library tag.
- **Add** a paragraph: the JavaScript producer ships on its own tag, `producer-js-v*`, with `--latest=false`, and as a file in the daemon zip. Nothing goes to Maven Central and there is no npm package.
- **Pinned by** `ProducerReleaseWorkflowTest`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 producer-js.yml triggers on producer-js-v* only, runs the WebSocket check and the node tests, refuses a tag that differs from PRODUCER_VERSION in either inspector-producer.js or inspector-producer-xhr.js and names that file, and publishes the three .js files, the guide and SHA256SUMS over all of them with --latest=false
- [ ] #2 The daemon distZip copies producers/js/*.js (top level) and the guide through a glob; release.yml's smoke step lists them and cmp-matches each against the repo copy in a loop over the same glob
- [ ] #3 ProducerReleaseWorkflowTest proves producer-js-v0.1.0 matches no release.yml tag pattern, v1.4.0 matches no producer-js.yml pattern, --latest=false is in producer-js.yml's gh release create and not in release.yml's, and that gh release create names every top-level producers/js/*.js file
- [ ] #4 ProducerReleaseWorkflowTest is shown red by adding '*' to release.yml's tags, by dropping --latest=false, and by removing inspector-producer-xhr.js from gh release create, then restored, and says it is a hand-written line reading
- [ ] #5 release.md's tag sentence names the daemon and library tag, and a paragraph records producer-js-v*
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
