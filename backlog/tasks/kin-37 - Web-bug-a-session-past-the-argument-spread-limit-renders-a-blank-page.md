---
id: KIN-37
title: 'Web bug: a session past the argument-spread limit renders a blank page'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 37000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. Introduced by KIN-34, which reads the whole-session call list in full. Math.min/max(...array) breaks at about 110-120k elements in Chrome. On 160,000 calls: RangeError: Maximum call stack size exceeded at sessionWindow (55-axis.js:38), 0 rows drawn, stuck at 'connecting…'. e1711a3 drew 1000 rows. Sites: 55-axis.js:32-38, 50-path.js:88, 70-timeline.js:108 and :151, 25-traffic-list.js:247, 45-visits.js:181-182. Harnesses: $TMPDIR/doug3/tools/huge.js and hugechrome.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 No spread of a row array into an argument list anywhere in web/js; loops instead
- [x] #2 A static check at 200k rows, red if any spread returns, green now
- [x] #3 On a >160k-call session in headless Chrome: rows and the axis are drawn, no page exception, and the dot reaches connected; recorded as a documented check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 1aaf3cb. Every spread of an array into an argument list in web/js is a loop now: 55-axis.js sessionWindow and the axis tallest bucket, 50-path.js lastMono, 70-timeline.js renderTimeline first/last and runRow from/to, 25-traffic-list.js wholeFrom, 45-visits.js waterfallGroups from/to, and 40-copy-export.js's one bounded spread (nearbyLines, capped at 31) so the rule has no exception.
NEW CHECKS (check-web-static.js, new section 'long session', scripts/lib/long-session-checks.js): 'no argument spread' scans every script with comments/strings blanked for a ... whose innermost bracket is a call's ( (not a parameter list); 'axis window 200k', 'waterfall 200k', 'stretch note 200k', 'timeline 200k', 'path 200k' run sessionWindow, waterfallGroups, hiddenByFilter({ranged}), renderTimeline (a 200k-observation run) and renderPath from the real files over 200,000 rows with a stub DOM. Static 45 -> 51 checks, 0 failed.
RED PROOFS (each file put back from HEAD alone, then restored): 25-traffic-list.js -> 'no argument spread: NO - 1 ... js/25-traffic-list.js:247' and 'stretch note 200k: NO - RangeError: Maximum call stack size exceeded'; 40-copy-export.js -> 'no argument spread: NO - 1 ... js/40-copy-export.js:116'; 45-visits.js -> NO 2 (181,182) and 'waterfall 200k: NO - RangeError'; 50-path.js -> NO 1 (:89) and 'path 200k: NO - RangeError'; 55-axis.js -> NO 3 (38,39,88), 'axis window 200k' and 'waterfall 200k' RangeError; 70-timeline.js -> NO 4 (108,109,151,152) and 'timeline 200k: NO - RangeError'; only runRow's spread put back -> NO 2 (153,154) and 'timeline 200k: NO - RangeError'. All green again after restore (51 checks, 0 failed). The scanner on HEAD's tree finds exactly the 12 sites the ticket lists (plus copy-export).
AC3 CHROME (documented check; harness kept in the engineer's scratch, tools/huge37.js, headless Chrome 131 on CDP 9477, 1440x900, Doug's 160,016-call session from $TMPDIR/doug3/huge): HEAD 3c08719 web (same as a86dde2) after 181 s: rows 0, axis hidden, 0 bars, conn 'connecting…', 5 page exceptions 'RangeError: Maximum call stack size exceeded at sessionWindow (55-axis.js:38:21)', one per view. 1aaf3cb after 24 s: rows 1000, axis drawn with 205 rects, conn 'connected', counts '1000 of 160016 calls', views path/all/waterfall/network each drew (2/1017/6/1002 children), exceptions: none. jsdom huge.js: 160016 whole, rows drawn 1000, 0 page errors, sessionWindow {from:900,to:169999}.
RUNS: live probe 182 passed 0 failed (ci.yml unchanged), archive 26 passed, :inspector-daemon:test --rerun 267/0, :repo-checks:test --rerun 35/0 (first run failed FileBudgetTest: web-audits.js 679 > 600 lines; the audit moved into long-session-checks.js instead of adding a budget). Chrome measurements on fixture and 3016-call session at 1440 and 400 (bob/measure.js, doug3 k33.js, simon3 m3.js, doug3 runjump.js): every output byte-identical to HEAD, including m3's two pre-existing FAILs (KIN-38). Client-name scan NOT RUN: no terms available to the engineer.

QA (fourth verify panel doug-4, charles-4, simon-4, head 8082e02): every AC of this ticket Verified; ACs ticked, ticket left In Progress because the item closes together. Panel reports filed as received; the per-AC command and decisive line are in the panel reports, not copied here.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at 8082e02 (fourth panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
