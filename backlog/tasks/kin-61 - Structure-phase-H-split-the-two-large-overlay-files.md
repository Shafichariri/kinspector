---
id: KIN-61
title: 'Structure phase H: split the two large overlay files'
status: Done
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 10:50'
labels:
  - 'item:structure-phase-h'
dependencies: []
ordinal: 61000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:structure-phase-h in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Roadmap criteria (docs/ROADMAP.md, Phase H):
1. Each file is split along a named seam. No architect on this row, so the builder names it: rows out of InspectorList.kt into ListRows.kt; the menu, filters sheet, section builders and term edits out of ListChrome.kt and InspectorList.kt into ListSheets.kt.
2. ListRenderTest and ListControlsTest pass unchanged.
3. The 360dp screenshots are compared by eye before and after.
4. InspectorOverlay.kt keeps its name.
5. The ratchet records the new, smaller budgets.
Out of scope: any change to the list's behaviour or look. Bound by the brief: a pure move, no public API change, JVM facade of public functions unchanged.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR #103. Moved by line ranges and checked by a sorted-line comparison. The only differences are 4 private->internal lines (SignalRow, RunHeader, MarkerDivider, TransactionRow), the new header comments, one section divider and the moved import comment.
- Tests: inspector-ui jvmTest 134/0 before and after; jvmTest sources untouched.
- Screenshots: 12 PNGs byte-identical (cmp), and two opened by eye. Red: STRIPE_WIDTH 3->4dp gives 5 DIFF PNGs. Use jvmTest --rerun, or a cached test task leaves stale PNGs.
- JVM API: javap -public base vs head shows InspectorOverlayKt identical. All differences are in the four list facades, which hold only internal and private declarations.
- Budgets: both entries removed by tighten. Red: ListSheets.kt padded to 602 lines makes FileBudgetTest fail.
- Release-clean: self-test FINDS, plain run clean.
- Locally unverified: there is no Android SDK or xcrun here, so the full ./gradlew build ran in CI only (build and release-swap green).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #103, merge 4dc21d2, base fb8e54e. Seams named in the PR and file headers: rows (ListRows.kt) vs list body (InspectorList.kt); bars (ListChrome.kt) vs what opens over the list (ListSheets.kt). Pure move: sorted-multiset compare of trimmed non-blank lines base vs head differs only in imports, package, header KDoc, one divider and four private->internal declarations. ListRenderTest/ListControlsTest unchanged: jvmTest diff empty; ':inspector-ui:jvmTest --rerun' base 134/0, head 134/0, identical names. Facade: git diff of InspectorOverlay.kt, InspectorTheme.kt, inspector-noop-ui, jvmTest is empty; javap -public: 361 member lines outside the four list facades identical, InspectorOverlayKt.InspectorOverlay(boolean, Function2, Composer, int, int) unchanged. Screenshots: 12/12 inspector-ui/build/screenshots PNGs byte-identical (cmp) with --rerun; list-dark and sheet-light read by eye. Ratchet: InspectorList.kt (758) and ListChrome.kt (703) lines removed (files 351/360/333/492); repo-checks green; red: ListSheets.kt padded to 607 lines fails FileBudgetTest 'Over 600 lines with no budget'. Release swap: -Pinspector=off :sample:desktop:build exit 0; check-release-clean.sh self-test 'CANARY FOUND ... SELF-TEST PASSED', plain run 'PASSED: no capture code found'. NOT run locally (no Android SDK, no working xcrun): full ./gradlew build, the -Pinspector=off build on Android and iOS targets and the klib half of check-release-clean.sh; these rest on CI (build, release-swap, repo, web green on the PR; main push on 4dc21d2 succeeded).
<!-- SECTION:FINAL_SUMMARY:END -->
