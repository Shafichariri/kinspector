---
id: KIN-47
title: 'Web: a live row arriving while a marked but empty copy is re-read is lost'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 11:01'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies:
  - KIN-46
type: bug
ordinal: 47000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the first verify panel (qa doug, architect charles-2, ux simon; base c21aaf0, head 0fbced1); all three agreed. Cause: syncAllTransactions and syncAllSignals in inspector-daemon/src/main/resources/web/js/15-session-data.js skip the read only when the copy is marked AND non-empty, so a marked, empty copy is re-read on a filtered load. During that read admitLive pushes into the old copy and returns before reaching the buffer, and the landed read then replaces the copy. Reproduced by doug and charles-2: a scratch harness prints "live row 0 time(s)" for calls and for signals. Fix, per charles-2: in admitLive, push into the marked copy as now, AND ALSO push into an open buffer for the session (state.arriving[kind] with buffer.sessionId === state.sessionId) whether or not the copy is marked. Reword the doc comment sentence "With no copy marked and no read in flight the row is dropped" to match.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Committed static checks "mid-read calls empty" and "mid-read signals empty" in scripts/lib/session-state-checks.js are NO on 0fbced1 and yes with the fix.
- [x] #2 Committed check "mid-read overtaken kept": an overtaken read's closeArrivals clears only its own buffer, so a row arriving while the newer read is held is in the landed copy once. It is red with an unconditional clear. (KIN-46 DoD asked for this; no check holds it today.)
- [x] #3 docs/decisions/web-ui.md, after "...for both copies.", adds: "A copy already marked is read again when it is empty, and the read replaces it, so a live row goes into an open buffer whether or not its copy is marked (`mid-read calls empty`, `mid-read signals empty`)." The "all are closed" sentence is then true.
- [x] #4 Text fixes: in ProbeFixtureTest.kt the sameRequest comment says "five" fields (it lists five). In structure/budgets.txt the reason on the render-web-ui.js entry becomes "...from 2744 in KIN-36 for wiring and reporting the waterfall's three empty-text lines, and from 2748 in KIN-44 for wiring and reporting the long session's lines, whose body lives in scripts/lib/long-session-probes.js; the whole-session refresh and filtered-pane probe bodies live in scripts/lib/filter-probes.js; split after phase F, ..." (rest of the existing reason kept).
- [x] #5 Static stays green (node scripts/check-web-static.js); the expected check counts change in the same commit.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in ce2b54a. admitLive now pushes onto a marked copy AND into an open buffer for the session (state.arriving[kind], buffer.sessionId === state.sessionId) whether or not the copy is marked; doc comment reworded. New static lines in scripts/lib/session-state-checks.js: 'mid-read calls empty', 'mid-read signals empty' (checkMidReadEmpty, from charles-2's draft), 'mid-read overtaken kept' (checkMidReadOvertaken, calls). Red proofs: checks written first against 0fbced1's 15-session-data.js -> both empty lines 'NO - the landed copy holds the live row 0 time(s)', 79 checks 2 failed; with the fix 79/0. closeArrivals made unconditional ('state.arriving[kind] = null;') -> 'mid-read overtaken kept: NO - the newer read's copy holds the live row 0 time(s)', 79 checks 1 failed; restored 79/0. web-ui.md sentence added after 'for both copies.' verbatim. Text fixes: ProbeFixtureTest 'five fields'; budgets.txt render-web-ui.js reason reworded as AC#4. check-web-static.js has no pinned count; no probe line added, so UI/ARCHIVE_EXPECT_CHECKS unchanged. Ready for QA.

Commit is f0b8e74 (amended to rewrap the web-ui.md sentence to the file's line width; wording unchanged).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit f0b8e74. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). 'mid-read calls empty', 'mid-read signals empty', 'mid-read overtaken kept' in static, each red proof re-run in round 2.
<!-- SECTION:FINAL_SUMMARY:END -->
