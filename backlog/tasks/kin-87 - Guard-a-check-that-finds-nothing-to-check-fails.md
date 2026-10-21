---
id: KIN-87
title: 'Guard: a check that finds nothing to check fails'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 18:15'
updated_date: '2026-10-21 22:28'
labels:
  - 'item:guard-probe-finds-nothing-fails'
dependencies: []
ordinal: 87000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Batch 1 of the order decided 2026-10-21. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. Built without QA before merge; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:guard-probe-finds-nothing-fails in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
ACCEPTANCE CRITERIA (copied from docs/ROADMAP.md, item:guard-probe-finds-nothing-fails)
1. On the CI fixture, `failures unfolded` fails when it sees 0 summarised lines, and `expanded unfolded` fails when it sees no expanded line. When either passes, it prints how many it checked.
2. The same rule, generalised. A probe in scripts/render-web-ui.js or scripts/lib/ that scans for a shape (rows, lines, bars, elements matching a selector) and passes when none breaks the rule also asserts that it found at least one, and prints the count. Named as a heuristic: currentHasAge, barsVisible, the lane list behind unknownTagRendered, noRepeatedHost, uncoveredNameTheirHost in render-web-ui.js, onTop in lib/measure-cases.js. Already done in places: everyHeaderHasOne, labelled, the CSS brace audit.
3. When a probe's shape is legitimately absent from a fixture, the probe says so in an n/a (...) or a note(), never in a yes.
4. Proven both ways: a KIN-55-style leak (a path visit left open before the path probe) turns the run red and the failure names the empty scan; removing the leak makes it green.
Out of scope: repairing what a leaking probe leaves; a page per probe; changing what any check judges beyond the empty case. A fixture check going yes -> n/a moves INSPECTOR_UI_EXPECT_CHECKS and the change says which and why.

RULE APPLIED: 0 found -> NO "found no <shape>, so this checked nothing" where the shape is due; n/a (why) only where an independent source (the daemon, or the page's own whole-session state for the scope bar) says the shape cannot exist. A count compared with an independent truth (daemon rows, archive markers) is NOT an empty scan: at zero it still asserts nothing spurious was drawn, so those are left alone.

INVENTORY (file: check - scans - on 0 today - plan). Fixture baseline: 251 passed, 1 n/a; every shape below is present on the fixture, so no yes->n/a is expected.
render-web-ui.js
- failures unfolded (errorsUnfolded): .pv-visit lines, summarised = 'expand this visit' chip - yes (0 summarised lines) [KIN-55] - NO when 0 summarised and a line is left open (the leak); n/a when no line is open and none summarised (every line already shows all it holds); prints summarised count.
- expanded unfolded: lines after a station opens one - yes - count .pv-open lines; 0 -> NO; prints count.
- currentHasAge: #current .current-item - true - 0 items -> NO when the daemon's /current holds values, n/a when it holds none; prints count.
- unknownTagRendered (lane list of .tl-signal with unknown tags): 'no unknown tags' unclassified, judged from the DOM - daemon's signals decide: unknown tags held and none drawn -> NO; none held -> n/a.
- noRepeatedHost: stripped rows - true - bar shown and 0 stripped -> NO; prints count.
- uncoveredNameTheirHost: rows outside the bar - true - n/a when every row the list holds is under the bar (page's scope over held rows); else 0 drawn -> NO; prints count.
- barsVisible (.wf-bar), oldestFirst (.wf-group), everyCallOnce + noneLost (waterfall .wf-row vs list rows) - yes - 0 -> NO when the daemon holds calls, n/a when it holds none; print counts.
- map matches log (path stations vs log lines, both from the page) - yes (0 stations) - 0 -> NO when route expects visits.
- sort flip restores: rowIds().every over rows after flipping back - true - 0 rows after -> NO; n/a with no rows before; prints count.
- start times shown: clocks === rows - yes (0 of 0) - n/a (no rows) like statusFirst.
- duplicate badges: badges === .row.duplicate - yes (0) - n/a when no repeats in this session (same as repeat counts calls).
- unanchored chips: chips not path:*/ - yes (0) - 0 chips -> n/a (no endpoint chips).
- hop-by-hop stripped: seeded header names - true - 0 lines -> n/a (the row seeded no headers); prints count.
- already guarded, left as is: everyHeaderHasOne, labelled, statusFirst, sort flip reverses (>1), runs, timeline tie, filter path/tag, tag browser filters, chain numbered, focus, glance, axis.
- left as is (compared with an independent truth): route matches, path every call once, signal tabs count, repeat counts calls.
lib/
- shell-probes.js `self has kill btn`: peer rows marked self - yes (0) when no self row - NO when no row is this daemon.
- shell-probes.js `rail is navigation`: rail controls - yes with 0 links - NO when no rail link.
- shell-probes.js `route pages`: loop over rail links - yes over none - NO when none; prints count.
- list-room-probes.js `exportMenu` (empty archive only): exports.every(hidden) - yes over none - NO when none.
- stats-probes.js `stats rail in export`: a rail item that is missing counts as hidden - NO when missing.
- measure-cases.js onTop (BAR_ON_TOP): hidden bar -> no row at all - row ok:false 'hidden' when the page holds a scope, ok:null n/a when it holds none; detail prints the points checked.
- web-audits.js auditMinTextSize: font-size rules - yes over none - NO when it read no px font-size; prints count. auditHiddenPanes: listed panes setting display - yes over none - NO when none; prints count.
- already guarded: auditStickyTop, auditCssClosesPerFile, auditMediaOverrideOrder, auditOneDeclarationPerName, auditWindowNames, auditUseStrict, row parts, list-room-cases room 400 rows, current-guard probes (skip when no values).
render-web-ui-archive.js (shares the pattern): `daemon controls hidden` and `pages hidden in export` count a missing node as hidden -> NO when a selector matches nothing.
Out of scope, check-web-static.js lib/*-checks.js: they build their own stub inputs in-process, so a scan there cannot be emptied by another probe's leftover state; the shared CSS audits above are fixed where they live.

INVENTORY ADDENDUM (fix round after QA, measure-web-ui.js cases; file: check - verdict)
lib/shell-cases.js
- shell <w> rail: scans #rail .rail-item, passed over none (QA's finding) - FIXED: 0 -> NO found no rail item; detail prints the count.
- shell <w> topbar fits, picker time, page <name> fits, shell back: one named element each; a missing one throws or fails - not scans, left.
- shell <w> controls: a fixed list of three selectors, a missing one reads :hidden and fails - left.
- shell <w> tabs reachable: scans the page's availableViews(), which always holds traffic, against the drawn tabs; prints the view count - left.
- stats <s> first draw: a reading (ok null), not judged - left.
- stats 400 route breaks / routes whole, stats 1440 routes one line: already require a route cell with a server (KIN-93); line breaks are printed - left.
lib/topbar-cases.js
- topbar <...> count: no word in #counts is 0 lines, which already failed as 'wrapped or clipped' - WORDING ONLY: now says NO found no word in #counts.
- topbar <...> controls: a reading (ok null) - left.
lib/list-room-cases.js
- room <w> doc width closed, closed shadow, divider, hint whole, bands, views keep place: single elements or values - not scans, left.
- room 1000 close disjoint served/export: already requires actions > 0 - left.
- room 1000 tab never enters closed: passed over a closed drawer holding nothing - FIXED: 0 controls in #detail -> NO; detail prints the count.
- room 400 rows: rows > 0 already; its repeat-mark part passed over no mark - FIXED: 0 marks while state.duplicates marks a held row -> NO; n/a in the detail when no held row is a repeat.
- room <w> toolbar fits / one row: an empty row fits - FIXED: 0 controls -> NO (one row already failed at 0 lines, now worded).
- room page exceptions: none thrown is the rule itself, not a scan - left.
lib/live-traffic-cases.js
- traffic reaches: passed over a recorder that recorded nothing - FIXED: no request or no socket recorded -> NO; detail prints origins and socket hosts.
- traffic hosts: already hosts.size > 0 - left.
- traffic burst reads: an upper bound, so 0 reads is within it; a page that did no re-read cannot agree with the daemon, which burst counts judges - left.
- traffic burst counts, the four races: compared with the daemon's answer, and the races fail with 'no re-read held' - left.
- traffic exceptions: absence is the rule - left.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on shafic/guard-probe-finds-nothing-fails (from b297b00), not committed. Ready for QA.
Fixture counts unchanged: live 251 passed / 1 n/a before and after; archive 48 before and after; static 107. No yes -> n/a, so UI_EXPECT_CHECKS / ARCHIVE_EXPECT_CHECKS in ci.yml are untouched.
Rule: an empty scan says NO - found no <shape>, so this checked nothing; n/a only where the daemon's rows, /current or the page's whole-session state say the shape cannot exist. Recorded once in docs/decisions/web-ui.md ("A scan that finds nothing to check fails, and says so").
Red proofs, each restored and re-run green:
- KIN-55 leak (the two expandedVisits restore lines in lib/live-filter-probes.js commented out): baseline code stayed green with "failures unfolded : yes (0 summarised lines in this session)"; with this change "failures unfolded : NO - found no summarised line (1 already open: Checkout), so this checked nothing", checks 250 passed 1 failed, exit 1.
- One run with the station click in probePath removed and the classes wf-bar, peer-self, current-item renamed in web/js: "expanded unfolded : NO - found no expanded line, so this checked nothing", "every bar visible : NO - found no waterfall bar ...", "now rows show age : NO - found no now row ...", "self has kill btn : NO - found no row marked as this daemon ...", exit 1.
- Exported page with #live-btn and the sessions rail link renamed: "daemon controls hidden : NO - found no #live-btn, .export-item ...", "pages hidden in export" and "stats rail in export : NO - found no rail link for sessions ...", exit 1.
- measure-web-ui.js --only layout with renderScopeBar forced hidden: 36 "scope bar: hidden (the page holds a scope, so this measured nothing)" FAIL rows, exit 1; on the base code those readings are skipped silently (reasoned from the code, not run). Clean: 124 readings, 0 failed, nothing moved against b297b00.
- auditMinTextSize('a{color:red}') and auditHiddenPanes('.x{display:flex}') return the NO lines (direct call).
- lib/scan-verdicts.js functions driven on stub jsdom pages for empty, absent (n/a), good and bad scans: every branch as designed.
Reasoned only, not run red: rail is navigation, route pages, exportMenu (empty archive only), sort flip restores, map matches log, start times shown, duplicate badges, unanchored chips, hop-by-hop stripped, onTop's n/a branch.
Finding, out of scope: on the clean fixture run "expanded unfolded" opens Home, which holds no failure ("1 expanded line, 0 failures drawn"); the failure-bearing visit is never expanded there, so the expanded half of the rule is exercised only weakly. Needs placing by the product owner.

Fix round (QA: shell <w> rail passed over no rail item), branch shafic/guard-probe-finds-nothing-fails-fix from 7b37d7a, not committed. Ready for QA.
Changed: scripts/lib/shell-cases.js, list-room-cases.js, live-traffic-cases.js, topbar-cases.js. Inventory addendum in the plan.
Red, on one mutant copy of this tree (rail-item renamed in index.html; toolbar-row children display:none; dup-badge class renamed; renderCounts empties #counts; the traffic recorder not recording origins; openThenClose not opening a row): 'shell <w> rail: NO - found no rail item, so this checked nothing (168px, 0 rail items)' at all four widths; 'room 1000 tab never enters closed: NO - found no control in the closed drawer'; 'room 400 rows: NO - found no repeat mark (3 rows held are repeats)'; the four toolbar readings 'NO - found no toolbar control'; 20 topbar count readings 'NO - found no word in #counts'; 'traffic reaches: NO - found no request'. 95 readings, 32 failed; the only other failure is shell back (navigation needs the rail).
Green: measure-web-ui.js --only shell,topbar,room,traffic --base <origin/main worktree>: base 95 / head 95 readings, 0 failed, moved against base: nothing. check-web-static.js 110/0. :repo-checks:test --rerun green (36 tests).
Not run: render-web-ui.js (no shared probe changed); the traffic socket half was not run red (the case needs the live socket open to start).
Not done here, by instruction: expanded unfolded's weak exercise on the fixture (follow-up in the hand-over).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug) round 1 on b1bbcac (PR #128): FAILED criterion 2. 'shell <w> rail' (scripts/lib/shell-cases.js) passed over zero rail items: with every class="rail-item" renamed in the served index.html, 'node scripts/measure-web-ui.js --only shell' read 'ok shell 1440 rail: inside (168px)' (and at 1000, 600, 400), and the inventory listed none of shell-cases.js, topbar-cases.js, list-room-cases.js, live-traffic-cases.js. No ticket filed for it (recorded here, by instruction). Fix round PR #133 (5b21593), QA round 2: PASSED. Rail with 0 rail items: 'FAIL shell <w> rail: NO - found no rail item, so this checked nothing (..., 0 rail items)' at all four widths, exit 1; green fixture '5 rail items'; with only #133's hunk reversed it reads 'ok ... inside' over none again. Inventory addendum covers the four files (shell x9, topbar x2, room x8, traffic x5). QA's own spot-check: 'room 400 rows' repeat-mark scan, red with the dup-badge class renamed: 'NO - found no repeat mark (3 rows held are repeats)'. Criteria 1,3,4,5 verified in round 1: 'failures unfolded : yes (1 summarised line, 6 lines in all)'; KIN-55 leak at base 'yes (0 summarised lines in this session)' 251 passed, at head 'NO - found no summarised line (1 already open: Checkout), so this checked nothing' 250 passed 1 failed exit 1, leak removed 251 passed exit 0; UI_EXPECT_CHECKS/ARCHIVE_EXPECT_CHECKS unmoved. Also: check-web-static.js 110/0, :repo-checks:test --rerun green, measure --only shell,room 61 readings 0 failed. DoD 2 ticked on QA's close verdict: the checks read from the code and not run red (rail is navigation, route pages, exportMenu, sort flip restores, map matches log, start times shown, duplicate badges, unanchored chips, hop-by-hop stripped, onTop n/a branch, socket half of traffic reaches) are listed as not proven both ways. Open, unplaced: 'expanded unfolded' opens Home with 0 failures on the fixture; web-ui.md does not yet name the Chrome cases fixed in #133.
<!-- SECTION:FINAL_SUMMARY:END -->
