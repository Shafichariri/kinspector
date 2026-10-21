---
id: KIN-93
title: At 400px the Stats page keeps a route's host whole
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 21:14'
updated_date: '2026-10-21 22:28'
labels:
  - 'item:web-stats-route-breaks-whole'
dependencies:
  - KIN-92
ordinal: 93000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 22 (§3 → Bugs found). Acceptance criteria are the roadmap item's own (docs/ROADMAP.md, heading carrying this label); the engineer copies them into the plan. Builds on item:host-port-everywhere's statsOrigin (PR #129).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:web-stats-route-breaks-whole holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Acceptance criteria (docs/ROADMAP.md, item:web-stats-route-breaks-whole):
1. At 400px, served and exported, no line of a route cell ends inside a host label, a port or a path segment. A host breaks only at a '.', a ':' or a '/'.
2. A route too long to break that way is still on screen in full within the table's own sideways scroll, and the page itself never scrolls sideways ('shell <w> page stats fits' stays green).
3. A Chrome reading in measure-web-ui.js at 400px is proven red with 'overflow-wrap: anywhere' put back.

Plan:
- web/js/95-stats.js: the route cell's origin and path are drawn with <wbr> after each '.', ':' and '/' of the origin (none inside an IPv6 literal's brackets, one after '://') and after each '/' of the path; the cell's text is unchanged.
- web/css/85-stats.css: drop overflow-wrap: anywhere and the flex items' min-width: 0 under 700px, so a segment longer than the cap widens the held column inside .stats-scroll instead of being cut or broken.
- scripts/lib/measure-fixtures.js: a 'routes' session (the probe plus calls on a port, an IPv6 loopback, a long path and an unbreakable host label).
- scripts/lib/shell-cases.js: 'stats 400 route breaks served/export' read per character in Chrome; asserts it found route cells and prints the count; plus 'stats 400 routes whole' (no span clipped, document fits). Wired into measure-web-ui.js under shell.
- Prove red with overflow-wrap: anywhere put back, then green.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built, uncommitted, on shafic/web-stats-route-breaks-whole (base 66c1bad). Ready for QA.

Change: 95-stats.js statsBreakable cuts the route cell's server after runs of . : / (never inside [..], never after a leading /) and its path after /, with <wbr> between pieces. 85-stats.css (<700px) holds each piece nowrap, drops overflow-wrap: anywhere, and moves the 150px cap from the cell to the label (134px + min-width: min-content), so a piece wider than the cap widens the column inside .stats-scroll. Above 700px wbr is display: none, because Chrome breaks at a <wbr> even under nowrap; this was found from the 1440 screenshot.

Guards (measure-web-ui.js --only shell, lib/shell-cases.js, new 'routes' session in lib/measure-fixtures.js): stats 400 route breaks served/export, stats 400 routes whole served/export, stats 1440 routes one line served. Static: check-web-static 'stats route pieces' (109 -> 110 checks; not pinned in CI). UI_EXPECT_CHECKS 251 and ARCHIVE_EXPECT_CHECKS 48 unchanged.

Red proofs: main's tree gives 29 breaks incl. http://127.0.0.1:808|0. main's 85-stats.css with the new JS: red (hyphen breaks). nowrap hold removed: red. Cap back on the cell: routes whole red. No 1440 wide rule: one line red (3 breaks). Servers under another class: NO - found no route cell with a server. Static: red with brackets ignored and with the leading / cut.

Deviation: overflow-wrap: anywhere added back ON ITS OWN stays green. The pieces are nowrap, so the property cannot break inside them. The criterion's red proof is met with main's stylesheet put back instead. The QA plan should use that or remove the hold.

measure --base origin/main 6f55741, --only shell: moved = stats 400 route breaks served/export mid-word -> at . : /; head 42 readings 0 failed, base 2 failed.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #132 merge 7b37d7a, one round, no blocking findings. 'measure-web-ui.js --only shell' head: 42 readings, 0 failed; 'stats 400 route breaks served' and '... export' read 'at . : /' (17 route cells, 23 line breaks); 'stats 400 routes whole' whole, document 400 of 400; 'stats 1440 routes one line' one line; 'shell <w> page stats fits' green. Red: base 85-stats.css alone -> mid-word, 33 breaks, 3 failed; base CSS+JS -> 'http://127.0.0.1:808|0', 2 failed; nowrap hold removed -> 2 failed; overflow-wrap: anywhere alone stays green (the item's recorded deviation, confirmed). check-web-static.js 109 -> 110; 'stats route pieces' red with bracket depth ignored: 'NO - http://|[::|1]:|8080 ...'. Cell textContent unchanged. Not run by QA: render-web-ui.js, archive probe (CI's web job), other Chrome sections. Note: web-ui.md says main gave 29 breaks, QA saw 30 (font-dependent).
<!-- SECTION:FINAL_SUMMARY:END -->
