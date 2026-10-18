---
id: KIN-22
title: >-
  Phase F bug: check 5's doc claims its list of misses is exact, and the
  decision omits @media-against-@media order
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 15:35'
updated_date: '2026-10-18 15:55'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-21
type: bug
ordinal: 22000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA verifying KIN-21 at 526dab7 (AC#2 'No false claim remains' Failed).

Reproduction (QA probe: $TMPDIR/qa3/probe5.js; each shape passed to auditMediaOverrideOrder returns 'yes (1 pair(s) compared...)'):
1. The doc comment in scripts/lib/web-audits.js says 'what it does not resolve is exactly this. Missed: ...' but six unlisted shapes are also missed: an earlier plain !important; combinator spacing (#p > .a vs #p>.a); vendor aliases (-webkit-user-select vs user-select); logical vs physical (margin-left vs margin-inline-start); 'all: unset'; CSS nesting.
2. An @media block loading before another @media block that overrides it is never compared. Live today: in css/35-list.css the prefers-reduced-motion '#detail-pane { transition: none }' must load after the 1199px block's '#detail-pane { transition: ... }'; moving it above stays '25 checks, 0 failed'.
3. docs/decisions/web-ui.md says a second declaration after ';' or '}' on a column-0 line is refused, but 'const a = 1; /* x */ const stop = 2;' exits 0.
4. 'one prefixing the other' should say the code requires a '-' at the boundary.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The check 5 doc comment and web-ui.md make no completeness claim about misses ('among them', not 'exactly this'), and both state that @media-against-@media order is not checked, naming the reduced-motion/1199px pair in 35-list.css as the live case held by review and by keeping both blocks in one file.
- [x] #2 web-ui.md's checks 2/3 sentence is true as written for a comment between the two declarations (either the scan refuses it, proven exit 2, or the sentence excludes it).
- [x] #3 The prefix wording matches the code (a '-' is required at the boundary).
- [x] #4 No code behaviour change beyond, optionally, the checks 2/3 refusal; first 24 lines of 'node scripts/check-web-static.js' byte-identical; static still 25 checks, 0 failed.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit f05603b. CHOICE checks 2/3: REFUSE. declaresAgain now skips blanks and block comments after the ';'/'}' (DECLARES_AGAIN). RED/GREEN (copy of tree, 'const a = 1; /* x */ const stop = 2;' appended to js/50-path.js): fixed audit exit 2 'harness: js/50-path.js:542: cannot read the names declared by: const a = 1; /* x */ const stop = 2;'; HEAD's audit exit 0 '232 names'; line removed exit 0 '25 checks, 0 failed'. Unit: 'function a() {} /* x */ /* y */ function b() {}' refused; 'const a = 1; /* x */' and 'const a = 1; // const b' still read as one name. CHECK 5 WORDING: doc comment and web-ui.md say 'among the misses'/'among its false reports', no completeness claim; both state @media-against-@media order is never compared and name the 35-list.css reduced-motion/1199px #detail-pane pair, held by review and one file. Prefix wording now 'one whose name is the other's followed by -'. CSS: comment only, above the reduced-motion block. STATIC: full output byte-identical to pre-change (not just first 24 lines), 25 checks, 0 failed. PROBES (fixture, daemon 8231, jsdom 29.1.1): live 'checks: 164 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes'; archive 'checks: 21 passed, 0 failed'; export contains the new CSS comment. GRADLE --rerun: daemon 259/0, repo-checks 35/0, ui jvmTest 134/0. QA probe5.js unchanged in behaviour (no code change to check 5). check-client-names.sh not run (no terms here).

QA at f05603b: AC#1-#4 Verified; KIN-21 AC#2 now Verified. QA found the checks 2/3 text in web-ui.md and topLevelDeclarations' doc still absolute ('is refused'; 'Three shapes are missed silently'): regex literals (const R = /\(/; const stop = 2;), a leading } on a continuation line, and a leading statement (kinA(); const stop = 2;) escape the scan, identically at 76b6c99. Conductor, 2026-10-18: fixed here by framing, not by listing more shapes - the scan is a column-0 line heuristic, not a JavaScript parser; the doc says so once, gives the known misses as examples ('among them'), and makes no absolute claim. No further shape-hunting on this ticket.

Engineer, commit b733aa4 (wording only, per the conductor's framing rule). topLevelDeclarations' doc, check-web-static.js's header, the doc lines of auditOneDeclarationPerName/auditWindowNames, and web-ui.md now say checks 2/3 are a column-0 line heuristic, not a JavaScript parser; known misses given 'among them' (indented; after a block comment on the line; later names of a declaration whose first line ends inside brackets; after a regex literal, a leading statement or a leading }); a second declaration on a column-0 line is refused 'when the scan can see it'. web-ui.md check 5 prefix phrase now 'one name followed by - at the start of the other' (all three places). STATIC: 'node scripts/check-web-static.js 2>&1' cmp-identical to f05603b's output, exit 0, 25 checks, 0 failed. GRADLE: ':repo-checks:test --rerun' 35/0 (needs -Porg.gradle.java.installations.paths pointing at a JDK 21; this machine has only 17 on the default path). No probes: no served file changed. Not pushed.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA at f05603b: AC#1-#4 Verified; wording pass b733aa4 re-verified all KIN-22 ACs. Check 5 claims no complete list of misses; the decision records @media-against-@media order; checks 2-3 described as a column-0 line heuristic. Static output byte-identical across 76b6c99/f05603b/b733aa4; repo-checks 35/0. DoD no-client-names left unticked: the term list is kept out of the repository, so it was not runnable here; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
