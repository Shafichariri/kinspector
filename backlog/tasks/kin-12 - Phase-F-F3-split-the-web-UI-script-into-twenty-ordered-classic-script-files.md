---
id: KIN-12
title: 'Phase F F3: split the web UI script into twenty ordered classic-script files'
status: To Do
assignee: []
created_date: '2026-10-18 13:30'
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
- [ ] #1 `web/js` holds the twenty files of the description's table, listed in that order in `scripts.txt`, and `js/00-app.js` is gone; each file is a one-line `/* area */` header, then `'use strict';`, then a blank line, then its blocks
- [ ] #2 Boundaries fall at a declaration's doc comment; each mirror block sits wholly inside one file; `fetch(` and `new WebSocket(` both live in `05-network.js`
- [ ] #3 Every file is under the 600-line `FileBudgetTest` limit, with no new budget entries; the `js/00-app.js` entry is removed from `structure/budgets.txt`
- [ ] #4 Proof, a one-off script in the PR and not committed: the multiset of top-level blocks equals `00-app.js`'s, and `99-init.js` is the tail verbatim
- [ ] #5 Red proof, load order: `archiveSource` moved to a file after `source` turns the archive probe red; the live probe stays green, as expected
- [ ] #6 Red proof, syntax error: a `SyntaxError` in one file is recorded as a page error and the later files still run
- [ ] #7 Red proof, mirror anchors: begin and end of `duplicates` in different files gives exit 2; a stale anchored copy in another file gives exit 2
- [ ] #8 Red proof, fetch: a `fetch(` in a listed file turns the static check and the archive probe red; a `fetch(` in an unlisted file turns `WebManifestTest` red, while the static check stays green
- [ ] #9 Red proof, export order: two files swapped in the export turns `every file inlined` red
- [ ] #10 shared-rules.md lines 2, 5, 12, 45, 51, 57, 74, 111 and 170: "`app.js`" → "the web UI" or "the web UI's `<name>` block"; web-ui.md: "`HOP_BY_HOP` in `app.js` mirrors" → "`HOP_BY_HOP` (the web UI's `hopByHop` block) mirrors", and "lifts both functions out of the real `app.js` by their `jsonParser` anchors" → "lifts both functions, by their `jsonParser` anchors, out of whichever manifest file holds them"
- [ ] #11 Static checks 23 (24 once F4 has landed), the first 20 lines byte-identical to base `f574bda`: case counts (95 in all), network counts and the min-text selector list included
- [ ] #12 Probes 164 and 21, compared per label (label plus verdict) against base
- [ ] #13 `./gradlew :inspector-model:jvmTest :inspector-daemon:test :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
