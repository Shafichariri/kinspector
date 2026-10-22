---
id: KIN-139
title: 'Session diff T5: the Diff page'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:26'
updated_date: '2026-10-22 22:26'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-137
  - KIN-138
  - KIN-135
  - KIN-66
  - KIN-70
  - KIN-124
  - KIN-102
  - KIN-103
  - KIN-104
  - KIN-105
  - KIN-106
  - KIN-107
  - KIN-108
modified_files:
  - inspector-daemon/src/main/resources/web/js/96-diff.js
  - inspector-daemon/src/main/resources/web/css/88-diff.css
  - inspector-daemon/src/main/resources/web/scripts.txt
  - inspector-daemon/src/main/resources/web/styles.txt
  - inspector-daemon/src/main/resources/web/index.html
  - inspector-daemon/src/main/resources/web/js/08-routes.js
  - scripts/lib/diff-probes.js
  - scripts/lib/diff-render-checks.js
  - scripts/lib/shell-checks.js
  - scripts/lib/shell-cases.js
  - scripts/render-web-ui.js
  - scripts/render-web-ui-archive.js
  - scripts/check-web-static.js
  - .github/workflows/ci.yml
  - structure/budgets.txt
  - docs/decisions/web-ui.md
type: feature
ordinal: 139000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR B (lane W), cut after PR A merges, after detection T5 (KIN-124) and polish PR A (KIN-102 to KIN-108). Design: session-diff note §9.1, §9.3, §12 T5 and §14 (the web-ui.md paragraph). The page draws the daemon's comparison and decides nothing; no mirror block, no case file. Rail order is Traffic, Stats, Findings, Diff, Sessions.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #/diff, #/diff?a=…, #/diff?a=…&b=… parse and round-trip (route parse); a PAGES row diff with live: true and a rail link between Stats and Sessions; rail is navigation passes
- [ ] #2 #/diff with no ids shows the latest run against its previous one and is not rewritten
- [ ] #3 The table draws the daemon's order, kinds, loud mark and verdicts; an unknown direction shows the daemon's why and no removed; n<5 is printed from the reply (check-web-static.js diff draws kinds, diff unknown verbatim, diff verdict verbatim, diff stale dropped, each red as in the design's §12)
- [ ] #4 The live probe's diff lines from diff default pair to diff same session, plus diff restored, pass on the CI fixture, including diff long session whole (1201 calls removed) and diff truncated unknown; the Pace-rule red of the next probe's guard is in the hand-over
- [ ] #5 The page does not re-read on a live message; a recording B shows the note and refresh
- [ ] #6 In an export the Diff rail link is hidden and #/diff lands on Traffic (diff hidden in export); 1a's export lines still pass
- [ ] #7 At 1440, 1000, 600 and 400 px the document does not scroll sideways on #/diff (shell <w> page diff fits); QA renders 1440 and 400 with an open detail row, with screenshots
- [ ] #8 UI_EXPECT_CHECKS and ARCHIVE_EXPECT_CHECKS raised in the same commit; index.html and render-web-ui.js budgets raised by hand with reasons; fetch still called once across scripts.txt's files; nothing new under 11 px
- [ ] #9 The web-ui.md paragraph of the design's §14 is in
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Build the Diff page (design session-diff.md §9.1, §9.3, §12 T5) in PR B on shafic/session-diff-pr-b, criteria #1-#9 as the plan:
1. 08-routes.js: PAGES row diff (live: true) after Findings; parseRoute/routeHash carry a and b; applyRoute calls diffRoute(route, entering).
2. js/96-diff.js + css/88-diff.css: pickers, swap, filter, since per side, refresh, Markdown link (a link, not fetch), summary, notes, table in its own sideways scroll, detail rows, unchanged toggle, states, ticket pair; no live re-read.
3. index.html: #page-diff and the rail link, under 600 lines with no budget entry (room made by trimming comments whose history is already in web-ui.md); controls built by wireDiffPage.
4. Checks: check-web-static --- diff --- (diff draws kinds, unknown verbatim, verdict verbatim, stale dropped) over a stub DOM; route parse cases; page keys (g d now real); live probe lib/diff-probes.js wired into render-web-ui.js with no new lines; archive diff hidden in export; shell/polish page lists gain diff; contact sheet gains the Diff page.
5. ci.yml counts; web-ui.md paragraph; DAEMON.md's held sentence (PR A hand-over, departure 6).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B (web), branch shafic/session-diff-pr-b from abb3622; not committed yet.

Built: PAGES row diff (live: true) after Findings; parseRoute/routeHash carry a and b; applyRoute calls diffRoute(route, entering). js/96-diff.js and css/88-diff.css. index.html gains #page-diff and the rail link and stays at 595 lines (T6 included) with no budget entry: the page's controls are built by wireDiffPage, and two Traffic comments whose history web-ui.md already records were cut to their why. render-web-ui.js stays at its 2354 budget: the probe is wired into the existing require line and statsLines line. web-ui.md paragraph and rail paragraph; DAEMON.md's held sentence (PR A hand-over, departure 6).

Departures from the design note:
- diff mutation loud cannot hold as worded (PR A hand-over, departure 5): PR A's order puts the added DELETE /v3/shop/cart/items (loud) first. The probe keeps PR A's order rule and looks the orders row up: it holds that POST …/orders/submit is .diff-loud, calls 2 → 1, and every row above it is loud.
- The diff-render checks run the real files over a stub DOM, not jsdom: check-web-static.js needs nothing installed.
- One error line, #diff-error, not #diff-filter-error: the page cannot tell which parameter a 400 is about without parsing the daemon's words. Every 404/400 text is drawn there verbatim.
- #diff-b has a first option 'latest session' (value empty), mirroring #diff-a's 'previous run of B', so #/diff is shown as the address says.
- Live lines split by ticket: T5 adds 12 (default pair … header added, same session, restored), T6 the 3 Sessions/caveat lines.
- No budget raised (instruction for this PR overrides the criterion's 'raised by hand').

Counts: static 134 → 138, live 305 → 317 (T5) → 320 (T6), archive 60 → 61.

Red proofs (each restored and seen green):
- diff draws kinds: rows sorted by route on the page → NO - rows drawn /v1/alpha /v1/b /v1/cart …, the reply's order is /v1/orders /v1/cart …
- diff unknown verbatim: unsure drawn under the heading 'removed' → NO - the unsure keys' heading reads "removed"
- diff verdict verbatim: page compares latencySamples < 5 → NO - four: "n<5" where "slower +60 ms (+60%)" was due
- diff stale dropped: ticket check removed → NO - drawn "/v1/older" … after the older landed last
- route parse: diff row taken out of PAGES → NO - #/diff -> {page: traffic, known: false} …
- page keys (updated honestly: g d is real now; tried with the diff row taken out, and in an export).
- diff long session whole: daemon fold cut at 1000 rows → NO - calls "998 → —"; the daemon counts 998
- diff truncated unknown: daemon reads a truncated body as empty (known) → NO - feed shape "empty → json"
- diff default pair, explicit pair, mutation loud, status mix, type changed, redacted not compared, latency not judged, header added, same session, restored: one break each, one run, each NO (details in the hand-over report).
- diff hidden in export: PAGES row live: false → NO - rail link shown; #/diff?a=x&b=y went to #/diff?a=x&b=y
- Pace: chip marks active's guard broken (expects 2 active chips) → chip marks active: false, failed: chip marks active; diff restored yes.

- shell <w> page diff fits: .diff-scroll's overflow-x removed → FAIL at 1000 (page 992 of 956), 600 (743 of 556) and 400 (743 of 356); fits at 1440. Green with it back.
- Contact sheet showed the table box shrinking as an item of the page's flex column (rows below the window cut, scrolled inside the box) and the narrow detail running past the box's visible edge; fixed with flex: none and a sticky, window-wide detail body below 700px.
- measure-web-ui.js --only polish,shell,room,layout --base abb3622: head 238 readings, 0 failed. Moved: shell <w> page diff fits no page → fits (4 widths), polish 1440/400 page targets short → at least 24px. The base's 6 failures are this tree's cases asking a base with no Diff page.
<!-- SECTION:NOTES:END -->
