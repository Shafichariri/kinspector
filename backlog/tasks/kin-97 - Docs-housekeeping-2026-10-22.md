---
id: KIN-97
title: Docs housekeeping (2026-10-22)
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:docs-housekeeping-2026-10-22'
dependencies: []
ordinal: 97000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-22 by the maintainer on the product owner's proposal. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:docs-housekeeping-2026-10-22 in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PLAN (bob, branch shafic/housekeeping-and-expanded-guard, one PR with KIN-98 as row 16b allows).
Criteria (ROADMAP, item:docs-housekeeping-2026-10-22):
1. web-ui.md 'A scan that finds nothing to check fails, and says so' names the Chrome readings PR #133 put under the rule: shell <w> rail, room 400 rows, toolbar fits and one row, tab never enters closed, traffic reaches and the top bar's count wording; each marked shown red or resting on review, as KIN-87's notes record.
2. The Stats route-break paragraph no longer gives main's break count as a fact ('29 breaks'; QA saw 30). It names the break that reproduces, http://127.0.0.1:808|0, and says the count depends on fonts.
Source for red/review: KIN-87 notes fix round: red on a mutant tree - shell <w> rail at all four widths, room 1000 tab never enters closed, room 400 rows (repeat mark), the four toolbar readings, 20 topbar count readings, traffic reaches (request half); the socket half of traffic reaches was not run red -> rests on review.
Files: docs/decisions/web-ui.md only. Docs only, no guard: 'shown red' here is a reading of KIN-87's record; check = grep of the two paragraphs.

RESULT (bob). web-ui.md edited: the empty-scan paragraph now names PR #133's Chrome cases by file and marks each shown red except the socket half of 'traffic reaches' (review), from KIN-87's fix-round notes; the Stats paragraph drops '29 breaks' as a fact, says the count depends on the fonts Chrome finds (29 and 30 seen) and names 'http://127.0.0.1:808|0' as the break that reproduces. Docs only; no guard to prove. check-web-static.js 116/0 (DecisionsTest via :repo-checks:test green). Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #143 merge c608ec8 on detached copies. AC1: grep -c '29 breaks' docs/decisions/web-ui.md gives 0; the paragraph now reads 'depends on the fonts Chrome finds (29 on one machine, 30 on another); the break that reproduces is http://127.0.0.1:808|0'. The empty-scan paragraph names shell <w> rail, room 400 rows' repeat marks, room <w> toolbar fits and one row, room 1000 tab never enters closed, traffic reaches and the top bar's count wording, each with its file and marked shown red except the socket half of traffic reaches (rests on review), matching KIN-87's fix-round notes and QA's own KIN-87 round 2. Docs only, so no new guard; check-web-static.js 116, 0 failed; :repo-checks:test --rerun green (DecisionsTest, FileBudgetTest). Identity check 3 commits pass; client-name scan no match. Verdict: close yes, no blocking findings.
<!-- SECTION:FINAL_SUMMARY:END -->
