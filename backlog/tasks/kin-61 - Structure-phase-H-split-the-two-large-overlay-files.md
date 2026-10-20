---
id: KIN-61
title: 'Structure phase H: split the two large overlay files'
status: In Progress
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-20 20:49'
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
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:structure-phase-h in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
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
