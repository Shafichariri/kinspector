---
id: KIN-15
title: 'Phase F acceptance evidence: base against head comparison'
status: To Do
assignee: []
created_date: '2026-10-18 13:31'
updated_date: '2026-10-18 13:31'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-10
  - KIN-11
  - KIN-12
  - KIN-13
  - KIN-14
type: task
ordinal: 15000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F acceptance evidence, base against head (design note §5 "QA evidence after F3 and F4" and QA's verification plan, "Baseline at f574bda" and "Verification by criterion"). Base is `f574bda`, measured by QA in a copy at `$TMPDIR/qa-phase-f/base`; head is the branch after KIN-10 to KIN-14. Every comparison runs both trees the same way and goes into the PR, so "a web UI user on a Mac sees nothing change" (ROADMAP phase F's acceptance bar) is shown, not asserted. No code changes in this ticket: it is the qa-verifier's run that closes the item. If any comparison differs, the delivery-tracker files a bug ticket under `item:structure-phase-f` against the ticket that caused it.

Baseline (QA, measured): static 20 checks, 0 failed; live probe 164 passed, 0 failed, 1 n/a (`facet filter`), 0 unclassified, 42 notes; archive probe 21 passed; PaletteParityTest 2 tests. The Chrome harness is QA's headless-Chrome-over-CDP script `$TMPDIR/qa-phase-f/cdp-snap.js`, with no dependency and nothing committed; base captures are in `$TMPDIR/qa-phase-f/chrome-base-{served,export}/`. Two base runs were identical apart from a cold first load, hence the warm-up; moving the 1199px `@media` block changed the style hash in all 12 configurations under 1200px, so the comparison can see a cascade-order change. Exports differ in `exportedAt`, hence the data-script swap.

Not verifiable here, and to be reported as such rather than ticked: Safari and Firefox (Chrome only); CI; Android host tests (no SDK on this machine); how it looks, which needs a human — the hash diff proves "identical to before", not "looks right".
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Live probe, per-label diff of base and head reports (label plus pass/fail/n-a/note, not totals): 164 passed, 0 failed, 1 n/a (`facet filter`), 0 unclassified, 42 notes, and no label changes verdict
- [ ] #2 Archive probe, per-label diff: 21 passed, the only label difference being `app.js inlined` → `every file inlined`
- [ ] #3 Static checks: the first 20 lines of head output are byte-identical to base, including the mirror case counts (timeline 12, visits 17, pathScope 16, endpointShortcuts 11, timelineRuns 12, duplicates 23, hopByHop 4; 95 in all), fetch 1, WebSocket 1, other primitives 0, and the min-text list `.row .method, kbd, .jt-tw, .now-caret, .tl-twisty`; head reports 24 checks, 0 failed
- [ ] #4 Phase D's route matrix (`$TMPDIR/qa-d/matrix/QaRouteMatrix.kt`) base against head differs only on `/app.js`, `/style.css`, `/js/*` and `/css/*`; explicitly: `PUT /js/00-core.js` gives 405, unlisted files give 404, `/js/..%2f..`-style traversal gives 404, and `/api/*` is unchanged
- [ ] #5 Headless Chrome, 16 configurations (widths 1440, 1000, 1000 with the drawer open and 400; dark and light; reduced motion on and off), served page and export under `file:`, one discarded warm-up load per run, the head export's data script swapped for base's: computed-style, body-text and PNG hashes identical to base in every configuration, with zero page errors
- [ ] #6 The manifest is the only list: `grep -rn "app\.js\|style\.css\|js/[0-9][0-9]-"` over the Kotlin, the scripts and `index.html` finds no list outside `scripts.txt` and `styles.txt`; red: an entry added to the manifest only is picked up by the page, the export and both probes
- [ ] #7 No build step and no dependency: no `package.json` is added and no `build.gradle.kts` gains a dependency between base and head
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
