---
id: KIN-12
title: 'Phase F F3: split the web UI script into twenty ordered classic-script files'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 13:30'
updated_date: '2026-10-18 15:55'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-11
modified_files:
  - inspector-daemon/src/main/resources/web/js
  - inspector-daemon/src/main/resources/web/scripts.txt
  - structure/budgets.txt
  - docs/decisions/shared-rules.md
  - docs/decisions/web-ui.md
type: task
ordinal: 12000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F, commit F3 (design note §1 "JS cut, by area", §6 "Load order", §5 F3). Approved by the maintainer on 2026-10-18 as written. Read first: `docs/decisions/shared-rules.md`, `docs/decisions/web-ui.md`, `docs/decisions/exports.md`. shared-rules.md: "each name must appear exactly once as a begin and once as an end in the same file". exports.md: "**The browser's `fetch` is called exactly once in `app.js`**, and it is counted twice: statically on every push by `check-web-static.js` across every loaded script … and in the built export by `render-web-ui-archive.js`".

`js/00-app.js` is split into twenty files following where 1a sends things. Whole top-level blocks (a declaration with its doc comment) may move between files; boundaries fall at a declaration's doc comment; the tail moves verbatim into `99-init.js`. Line numbers are base `app.js`:

| File | Holds | From app.js |
|---|---|---|
| `00-core.js` | ARCHIVE, prefs, state, `$`/`el`, formatters, ages, prettyJson, `urlOf` mirror, curlFor, fmtClock | 9–217, 699–735, 2961–2968 |
| `05-network.js` | `source`, `archiveSource`, `api`, `enterArchiveMode`, `setConn`, `connectLive`: the one `fetch` and the one `WebSocket` | 736–818, 4469–4499, 4563–4568, 5080–5165 |
| `10-json-tree.js` | `jsonParser` mirror and the tree | 218–698 |
| `15-session-data.js` | loadSessions, labels, counts, loadTransactions, loadSignals, syncAllTransactions | 819–928, 1040–1098 |
| `20-navigation.js` | tabs, `tagOf` mirror, availableViews, selectView, applyView, drawer, pops | 929–1039, 3286–3420 |
| `25-traffic-list.js` | filter error, markers and form, `pathScope` mirror, scope bar, list, rowFor, sort order, applyFilter, setLiveTail, select/move | 1099–1389, 2800–2822, 2969–3018, 4392–4443, 5067–5079 |
| `30-repeats-endpoints.js` | `duplicates` and `endpointShortcuts` mirrors, endpoint chips | 2823–2955 |
| `35-detail.js` | attemptOf, renderDetail, panels, fetchBody | 2956–2960, 3019–3285 |
| `40-copy-export.js` | agent bundles, copy buttons, menus, EXPORTS | 1390–1759 |
| `45-visits.js` | `visits` mirror, timelineEntries, waterfall | 1760–2024 |
| `50-path.js` | the path view | 2025–2557 |
| `55-axis.js` | the time axis | 2558–2799 |
| `60-browser.js` | tag browser rows, groups, facets | 3421–3724 |
| `65-browser-detail.js` | glance, browser detail, value and history, pulls | 3725–4058 |
| `70-timeline.js` | `timelineRuns` mirror, timeline, now, selectSignal | 4059–4391 |
| `75-replay.js` | `hopByHop` mirror, replay | 4603–4863 |
| `80-settings.js` | duplicate window, endpoint limit | 4864–4902 |
| `85-processes.js` | control, banners, stop/restart, MCP, peers, server info | 4444–4468, 4500–4602 (minus setConn), 4903–5066 |
| `90-sessions.js` | confirmThen, sessionApi, delete/clear, session rows | 5166–5300 |
| `99-init.js` | keydown, wiring, `init()`, unchanged | 5301–5430 |

Load order: hoisting does not cross files. The only load-time references between declarations are `ARCHIVE`, `prefs`, and `source → archiveSource`, and those two share `05-network.js`; everything that runs at load is the tail. `source → archiveSource` runs only when `ARCHIVE` is set, so the archive probe is the only check that can see a load-order break there; the live probe staying green on that proof is expected.

`mirror-runner.js`, `MirrorCasesTest`, `HopByHopMirrorTest` need no logic change, because lifting is by anchor across all files. The `js/00-app.js` entry in `structure/budgets.txt` is removed. The mirror wording in shared-rules.md and web-ui.md (note §7) updates in this commit; the two new web-ui.md paragraphs and the rest of §7 are F5.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 `web/js` holds the twenty files of the description's table, listed in that order in `scripts.txt`, and `js/00-app.js` is gone; each file is a one-line `/* area */` header, then `'use strict';`, then a blank line, then its blocks
- [x] #2 Boundaries fall at a declaration's doc comment; each mirror block sits wholly inside one file; `fetch(` and `new WebSocket(` both live in `05-network.js`
- [x] #3 Every file is under the 600-line `FileBudgetTest` limit, with no new budget entries; the `js/00-app.js` entry is removed from `structure/budgets.txt`
- [x] #4 Proof, a one-off script in the PR and not committed: the multiset of top-level blocks equals `00-app.js`'s, and `99-init.js` is the tail verbatim
- [x] #5 Red proof, load order: `archiveSource` moved to a file after `source` turns the archive probe red; the live probe stays green, as expected
- [x] #6 Red proof, syntax error: a `SyntaxError` in one file is recorded as a page error and the later files still run
- [x] #7 Red proof, mirror anchors: begin and end of `duplicates` in different files gives exit 2; a stale anchored copy in another file gives exit 2
- [x] #8 Red proof, fetch: a `fetch(` in a listed file turns the static check and the archive probe red; a `fetch(` in an unlisted file turns `WebManifestTest` red, while the static check stays green
- [x] #9 Red proof, export order: two files swapped in the export turns `every file inlined` red
- [x] #10 shared-rules.md lines 2, 5, 12, 45, 51, 57, 74, 111 and 170: "`app.js`" → "the web UI" or "the web UI's `<name>` block"; web-ui.md: "`HOP_BY_HOP` in `app.js` mirrors" → "`HOP_BY_HOP` (the web UI's `hopByHop` block) mirrors", and "lifts both functions out of the real `app.js` by their `jsonParser` anchors" → "lifts both functions, by their `jsonParser` anchors, out of whichever manifest file holds them"
- [x] #11 Static checks 23 (24 once F4 has landed), the first 20 lines byte-identical to base `f574bda`: case counts (95 in all), network counts and the min-text selector list included
- [x] #12 Probes 164 and 21, compared per label (label plus verdict) against base
- [x] #13 `./gradlew :inspector-model:jvmTest :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in local commit 4a23f77 (not pushed). Ready for QA. Scratch: $TMPDIR/kin12/ (blocks.js, plan.js, split.js, prove.js, loadtime.js, red.sh, red-a.log, red-b.log, reports).

Cut: plan.js parses F2's 00-app.js (266f7bd) into 231 top-level blocks (column-0 declaration plus its blank/comment preamble; a '// @mirror-end' line closes the block before it) and assigns each by its declaration line against the design table (original line = F2 + 1). Section headers ('// --- api ---' etc.) travel with the declaration after them. Every mirror region landed in one file; no block straddled a range. Within each file blocks keep F2's order. Files: 00-core 255, 05-network 219, 10-json-tree 483, 15-session-data 168, 20-navigation 232, 25-traffic-list 420, 30-repeats-endpoints 136, 35-detail 277, 40-copy-export 372, 45-visits 261, 50-path 541, 55-axis 246, 60-browser 316, 65-browser-detail 341, 70-timeline 341, 75-replay 263, 80-settings 34, 85-processes 285, 90-sessions 146, 99-init 133.

Proof (node prove.js f2-00-app.js <web>): 'F2 blocks: 231; head blocks: 231 in 19 files; multiset equal' / '99-init.js tail verbatim: yes (130 lines from F2 line 5300)' / 'proof: PASS'. Also checks header shape, F2 order within each file, each mirror closed in its file, bare fetch( and new WebSocket( only in 05-network.js. Proof proven red: one changed token in 35-detail.js -> 'multiset DIFFERS'; a line appended to 99-init.js -> 'tail verbatim: NO'.
Load-time scan (loadtime.js, crude): the only non-function initializer naming a later declaration is source -> archiveSource, same file (hoisted); no column-0 statement outside 99-init.js.

Runs (daemon installDist'ed from the tree on 8231 over $TMPDIR/kin10/probe, stopped after):
- check-web-static.js: '23 checks, 0 failed'; head -20 cmp-identical to $TMPDIR/kin10/static-base.txt; 'use strict first : yes (20 file(s))', 'one declaration per name : yes (231 names in 20 file(s))'.
- render-web-ui.js: 'checks: 164 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes', errors none; per label+verdict vs kin10/ui-base.report: 209 lines, identical sequence.
- render-web-ui-archive.js on a fresh export: 'checks: 21 passed, 0 failed', 'every file inlined : yes - 20 script(s) and 1 stylesheet(s), each its file, in manifest order'; per label vs archive-base.report: 25 lines identical.
- ./gradlew :inspector-model:jvmTest :inspector-daemon:test :repo-checks:test :inspector-ui:jvmTest -> BUILD SUCCESSFUL; model 159, daemon 259, repo-checks 35, ui 133, 0 failures (ui UP-TO-DATE: no input of it changed).

Red proofs (red.sh, each restored and diff-verified identical after):
- load order, archiveSource moved to the end of 10-json-tree.js: static exit 0 (23, 0 failed); live probe exit 0 '164 passed' (expected); archive probe exit 1 'no page errors : NO - jsdomError: Uncaught [ReferenceError: archiveSource is not defined] | unhandled: api is not defined', 'checks: 10 passed, 6 failed'.
- syntax error, 'const = ;' appended to 80-settings.js: live probe 'errors : script js/80-settings.js: SyntaxError: Unexpected token '=' | ... ReferenceError: applyEndpointLimit is not defined ...' and 'rows rendered : yes (16)' (99-init ran after it), exit 1, 162 passed 2 failed.
- mirror end of duplicates moved into 35-detail.js: static exit 2 "harness: mirror block 'duplicates': @mirror-end must follow @mirror-begin in the same file".
- stale copy of the duplicates block (key without status) in 80-settings.js: static exit 2 "harness: mirror block 'duplicates': 2 @mirror-begin and 2 @mirror-end, expected exactly one of each".
- fetch( in listed 80-settings.js: static exit 1 'fetch : NO - 2, must be 1'; archive exit 1 'one network call site : NO - fetch 2, must be 1'.
- fetch( in unlisted js/42-x.js: static exit 0 '23 checks, 0 failed'; WebManifestTest exit 1 '(b) the folder and the manifest disagree: web/js/42-x.js is in the folder but web/scripts.txt does not list it'.
- export order, HtmlExport inlines scripts[1], scripts[0], rest: archive exit 1 'every file inlined : NO - script 1 is not js/00-core.js; script 2 is not js/05-network.js'.

Done differently, for QA to judge:
- F2's six-line file header comment ('Deliberately dependency-free ...') is kept, as a block of its own, at the top of 00-core.js after the one-line header, 'use strict' and blank line; prove.js counts it once. No decision file records that rule, so dropping it would have lost it.
- ExportTest 'refuses a manifest entry with no file' hardcoded js/00-app.js as its real first entry and failed after the split; it now takes WebPage.scripts().first() / styles().first(). Not in the ticket's modified_files.
- shared-rules.md:170 reads 'timelineEntries in its visits block', because timelineEntries sits inside the visits mirror block (mirror-runner's timeline rule lifts ['tagOf','visits']).
- Left for F5 (comments naming files): WebPage.kt:41 and web-sources.js:28 still use js/00-app.js as an example; web AGENTS.md still says app.js.
Not run: Chrome harness (QA's), CI, Android host. Client-name scan: 'NOT RUN — no terms' on this machine.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA panel 1 at 90618e2: all 13 ACs Verified. Script split into twenty ordered classic files; QA re-ran the move proof (JS block multiset identical, 99-init tail). Live probe 164/1 n/a/42 notes per-label identical to base; archive 21; headless Chrome 131, 16 configs served and export under file:, 32/32 identical computed-style, text and PNG hashes, 0 page errors. DoD no-client-names left unticked: the term list is kept out of the repository, so it was not runnable here; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
