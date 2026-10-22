---
id: KIN-98
title: 'Guard: expanded unfolded judges a visit with failures'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:guard-expanded-unfolded-has-failures'
dependencies: []
ordinal: 98000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-22 by the maintainer on the product owner's proposal. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:guard-expanded-unfolded-has-failures in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PLAN (bob, branch shafic/housekeeping-and-expanded-guard, shared PR with KIN-97).
Criteria (ROADMAP, item:guard-expanded-unfolded-has-failures):
1. The probe opens a station whose visit holds >=1 failed call, chosen from the daemon's rows, not from what the page drew. On the fixture it prints a count of >=1 failure drawn.
2. With no visit holding a failure in the session it reads 'n/a (no visit holds a failure)', never yes.
3. Proven red: an expanded line made to drop its failed calls' status fails the line.
4. If INSPECTOR_UI_EXPECT_CHECKS moves, the change says why.
Design: probePath already reads the daemon's transactions and screen signals for 'route matches'. Split those into visits independently of web/js visits() (same reason as route: a probe calling the same function agrees with its bugs): starts = consecutive distinct screens by mono (seq on a tie), plus a leading visit when anything precedes the first screen; a call belongs to the last start at or before it. Failure = error or status >= 400 (isFailure). The first visit holding one is the station clicked; with none, the probe still opens stops()[1] for 'station opens line' and expanded unfolded reads n/a (no visit holds a failure). scan.expandedUnfolded gets the daemon's count for the chosen visit and also fails when the opened line draws fewer failures than the daemon holds; the yes line names the visit and the count. Out of scope: failures unfolded, fixture content.
Files: scripts/render-web-ui.js (probePath only, minimal - another session may touch the file), scripts/lib/scan-verdicts.js, docs/decisions/web-ui.md (one sentence), ci.yml only if the pinned count moves (expected not to: yes stays yes).
Proofs: red with the expanded line's .pv-status classes stripped of the failure class in web/js (mutant), red-check that n/a path appears with a session with no failure, Pace: re-prove 'failures unfolded' red (KIN-55 leak).

RESULT (bob). Built as planned, with the station choice in scan-verdicts.js (stationToOpen/failingVisit) so render-web-ui.js stays at its 2354-line budget (+2/-2 lines there).
Green, fresh fixture on :8108: 'station opens line : yes (Checkout)', 'expanded unfolded  : yes (1 expanded line, 4 failures drawn in Checkout)', checks 262 passed 0 failed 1 n/a; archive 48 passed. Base (origin/main) on the same fixture: 'yes (Home)', 'yes (1 expanded line, 0 failures drawn)'.
Red 1 (criterion 3): 50-path.js pathItem status span without its s<class> for failed calls: 'expanded unfolded  : NO - an expanded line hides a failure (Checkout)', 261 passed 1 failed, exit 1. The same mutation on origin/main's tree stays green: 'yes (1 expanded line, 0 failures drawn)', 262 passed.
Red 2 (daemon count): that mutation plus isFailure => false (page counts no failure at all): 'NO - Checkout holds 4 failures, its open line draws 0', exit 1.
n/a (criterion 2): fixture copy with its 500/503 rows set to 200: 'expanded unfolded  : n/a (no visit holds a failure)', station still opens (Home).
Pace: KIN-55 leak (live-filter-probes.js lines restoring expandedVisits commented out): 'failures unfolded  : NO - found no summarised line (1 already open: Checkout), so this checked nothing', 261 passed 1 failed.
Count: UI_EXPECT_CHECKS 262 unmoved (yes stays yes); ci.yml untouched.
check-web-static.js 116/0; :repo-checks:test --rerun green. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #143 merge c608ec8 on detached copies. Green on the CI fixture: 'station opens line : yes (Checkout)', 'expanded unfolded : yes (1 expanded line, 4 failures drawn in Checkout)', 263 passed, 0 failed, 1 n/a (matches ci.yml 263); base 85e9552 read 'yes (1 expanded line, 0 failures drawn)'. Red: pathItem's status span without its failure class gives 'expanded unfolded : NO - an expanded line hides a failure (Checkout)', 262/1, exit 1; the same mutation on base stays green. Daemon-count branch reached by keeping that mutation and removing the pv-failed header (NOT by the hand-over's isFailure=>false recipe, which reaches the hiding branch): 'NO - Checkout holds 4 failures, its open line draws 0'. n/a: fixture with every 5xx set to 200 gives 'expanded unfolded : n/a (no visit holds a failure)' and the station still opens (Home). KIN-55 leak still turns 'failures unfolded' red. render-web-ui.js stays at 2354 lines; INSPECTOR_UI_EXPECT_CHECKS unmoved. Not exercised: the same-millisecond tie branch of failingVisit. Verdict: close yes.
<!-- SECTION:FINAL_SUMMARY:END -->
