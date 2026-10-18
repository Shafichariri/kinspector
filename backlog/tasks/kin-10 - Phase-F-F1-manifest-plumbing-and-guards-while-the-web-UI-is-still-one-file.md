---
id: KIN-10
title: 'Phase F F1: manifest plumbing and guards while the web UI is still one file'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 13:30'
updated_date: '2026-10-18 13:32'
labels:
  - 'item:structure-phase-f'
dependencies: []
modified_files:
  - inspector-daemon/src/main/resources/web/js/00-app.js
  - inspector-daemon/src/main/resources/web/css/00-tokens.css
  - inspector-daemon/src/main/resources/web/scripts.txt
  - inspector-daemon/src/main/resources/web/styles.txt
  - inspector-daemon/src/main/resources/web/index.html
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/WebPage.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/WebUiRoutes.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/HtmlExport.kt
  - >-
    inspector-daemon/src/test/kotlin/dev/inspector/daemon/service/WebManifestTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/RestApiTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ExportTest.kt
  - scripts/lib/web-sources.js
  - scripts/render-web-ui.js
  - scripts/render-web-ui-archive.js
  - inspector-ui/build.gradle.kts
  - inspector-ui/src/jvmTest/kotlin/dev/inspector/ui/PaletteParityTest.kt
  - structure/budgets.txt
  - docs/decisions/exports.md
type: task
ordinal: 10000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F, commit F1 (design note "Phase F design note (revised after UX and QA)", §2, §3, §4 and §5 F1). Approved by the maintainer on 2026-10-18 as written. Read first: `docs/decisions/exports.md`, `docs/decisions/web-ui.md`, `docs/decisions/shared-rules.md`, `docs/decisions/repository.md`. exports.md: "The two tags the exporter replaces are the same literals `render-web-ui.js` replaces, and both sides now refuse to proceed when a replacement misses." shared-rules.md, on mirror cases: "without the input declaration, adding a bad case file left `jvmTest` up to date and green — measured". QA objection 4 is the same defect on PaletteParityTest, measured today.

The manifest plumbing and its guards land while the code is still one file, so F2 to F4 are made under them.

- `git mv web/app.js web/js/00-app.js` and `web/style.css web/css/00-tokens.css` (under `inspector-daemon/src/main/resources/`).
- `web/scripts.txt` and `web/styles.txt`: one path per line, `#` comments allowed, order is load order. One entry each at this commit.
- `index.html` keeps no list. It has two slots: `<!-- inspector:styles -->` in `<head>` and `<!-- inspector:scripts -->` before `</body>`.
- New `service/WebPage.kt` is the only Kotlin reader of the manifests and imports no Ktor. It expands the slots for `GET /` (fixed `<link rel="stylesheet" href="/css/…">` and `<script src="/js/…"></script>` tags, in manifest order) and for `HtmlExport` (one `<style>` per CSS file, then the data `<script>`, then one `<script>` per JS file).
- `WebUiRoutes.kt`: `GET /js/{f}` and `GET /css/{f}` serve only files the manifest lists. `/app.js` and `/style.css` go away. Nothing serves or fetches `scripts.txt`, so there is no runtime manifest read and no extra `fetch`.
- `HtmlExport.kt` reads both manifests through `WebPage`, `checkNotNull` on each entry naming the file, and refuses per file `</script`, `<!--` and `<script` in JS and `</style` in CSS. None occur today.
- `scripts/lib/web-sources.js` reads both manifests; `loadScripts()` and `loadStyles()` return `[{file, text}]` in order.
- `render-web-ui.js` expands the same two slot literals **from the working tree**. The maintainer declined QA objection 10: a probe that loaded what the daemon serves would test the daemon's older built jar after an edit and report green on code no longer there. `RestApiTest` carries the served half instead. Each manifest file runs as its own `vm.Script` in `dom.getInternalVMContext()`, in order, instead of one joined `window.eval`; both the constructor and `runInContext` sit in a try/catch that records a page error and continues with the next file, as a browser does. One `<style>` per CSS file. If jsdom 29.1.1 lacks `getInternalVMContext`, the fallback is `runScripts: 'dangerously'` with overrides in `beforeParse`.
- `render-web-ui-archive.js`: `app.js inlined` becomes one check, `every file inlined`. `@import` and `url(` are searched in inline `<style>`s only, not in code scripts (disputed item 2 in the note).
- `:inspector-ui`'s `jvmTest` gains a `CommandLineArgumentProvider` on the `MirrorCasesArgument` pattern from `inspector-model/build.gradle.kts`, and PaletteParityTest reads only through it.
- `WebManifestTest` (note §4) sits beside `WebPage`. The resources are on the test runtime classpath, so a change to them re-runs the test without a rerun flag.
- `structure/budgets.txt` is a ratchet that fails on a budgeted path that no longer exists, so the `app.js` and `style.css` entries follow the moved files at this commit (removed in F3 and F4), and `render-web-ui.js`'s line is reset to its new exact count by hand with the reason on the line.
- exports.md wording from note §7 lands here.

After this commit QA re-runs phase D's route matrix base against head.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `web/app.js` is `git mv`ed to `web/js/00-app.js` and `web/style.css` to `web/css/00-tokens.css`; `web/scripts.txt` and `web/styles.txt` list one entry each; `index.html` holds `<!-- inspector:styles -->` in `<head>` and `<!-- inspector:scripts -->` before `</body>` and keeps no file list
- [ ] #2 `service/WebPage.kt` is the only Kotlin reader of the manifests and imports no Ktor; `DaemonLayersTest` stays green
- [ ] #3 `GET /js/{f}` and `GET /css/{f}` serve only files the manifest lists; `/app.js` and `/style.css` go away; nothing serves or fetches `scripts.txt`
- [ ] #4 `RestApiTest`: `/` contains the tags in exact manifest order, and no slot comment is left
- [ ] #5 `RestApiTest`: every listed path returns 200 with `text/javascript` or `text/css`, and a body byte-equal to the resource
- [ ] #6 `RestApiTest`: an unlisted file gives 404; an encoded traversal (`/js/..%2f..%2findex.html`) gives 404; `PUT /js/00-core.js` gives 405 (KIN-9)
- [ ] #7 `HtmlExport` reads both manifests through `WebPage`, with `checkNotNull` on each entry naming the file, and refuses per file `</script`, `<!--` and `<script` in JS and `</style` in CSS
- [ ] #8 `ExportTest`: `</script>` count = 1 + number of scripts; `<style>` count = number of stylesheets; no `<script src`, `<link rel="stylesheet"`, `@import` or `url(` outside the data
- [ ] #9 `ExportTest`: "Refuses an index.html without a slot", "Refuses a manifest entry with no file", and per-file refusal of the sequences that would break inlining
- [ ] #10 `web-sources.js` reads both manifests; `loadScripts()` and `loadStyles()` return `[{file, text}]` in manifest order
- [ ] #11 `render-web-ui.js` expands the two slot literals from the working tree and refuses to proceed when an expansion misses; it runs each manifest file as its own `vm.Script` in `dom.getInternalVMContext()` (or the `runScripts: 'dangerously'` fallback), records a throw from the constructor or `runInContext` as a page error and continues with the next file, and injects one `<style>` per CSS file
- [ ] #12 `render-web-ui-archive.js` replaces `app.js inlined` with `every file inlined`, which passes only if: the number of code scripts (data excluded by the existing JSON rule) equals the length of `scripts.txt`; each code script's text equals the file at the same position exactly; the same for inline `<style>`s against `styles.txt`; the document has no `<script src` and no `<link rel="stylesheet"`; no `@import` or `url(` appears in any inline `<style>`, the data script never searched. The detail line names the failed condition
- [ ] #13 Red proofs for `every file inlined`, each seen failing with its condition named and then restored: one file dropped in the inliner; a leftover `<script src>`; an `@import` added to a CSS file
- [ ] #14 `WebManifestTest` asserts for both manifests, each with its own message: (a) every entry resolves on the built classpath; (b) the recursive listing of `web/js` and `web/css`, subdirectories included, equals the manifest exactly, failing if a directory is missing or a manifest is empty; (c) every entry matches `^js/\d\d-[a-z0-9-]+\.js$` or the CSS equivalent, with no stray whitespace; (d) entries are strictly ascending and a duplicate line gets its own message; (e) the web root holds only `index.html`, `scripts.txt`, `styles.txt`, `js/`, `css/`, `AGENTS.md` and `CLAUDE.md`
- [ ] #15 `WebManifestTest` red proofs, each run as plain `./gradlew :inspector-daemon:test` with no rerun flag, seen failing and restored: a ghost line in `scripts.txt`, and one in `styles.txt`; an unlisted `js/42-x.js`, and an unlisted CSS file; `js/sub/x.js`; a commented-out entry whose file still exists; a duplicate line; two lines swapped; a trailing space; a leftover `web/app.js`
- [ ] #16 `:inspector-ui` `jvmTest` declares `@InputDirectory @PathSensitive(RELATIVE)` on `inspector-daemon/src/main/resources/web/css` and `@InputFile` on `web/styles.txt` through a `CommandLineArgumentProvider`, passed as `-Dinspector.webStyles=…`; PaletteParityTest reads only that property, with no fallback on relative paths, and fails if it is unset
- [ ] #17 PaletteParityTest proven red with no `--rerun` by drifting `--m-get` in the CSS (the ordinary `jvmTest` run is not UP-TO-DATE), then green when restored
- [ ] #18 exports.md carries the four §7 rewordings: the export inlines every file in `web/styles.txt` and `web/scripts.txt` in manifest order; "the page talks to the daemon through one object"; `fetch` is called exactly once across every file `web/scripts.txt` lists, and `WebManifestTest` refuses a file it does not list; the two slots are the same literals `render-web-ui.js` expands
- [ ] #19 Probes 164 and 21, compared per label (label plus verdict) against base `f574bda`, not by totals
- [ ] #20 `node scripts/check-web-static.js` output byte-identical to base (20 checks)
- [ ] #21 QA re-runs phase D's route matrix base against head; differences only on `/app.js`, `/style.css`, `/js/*` and `/css/*`
- [ ] #22 `./gradlew :inspector-daemon:test :inspector-ui:jvmTest :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
