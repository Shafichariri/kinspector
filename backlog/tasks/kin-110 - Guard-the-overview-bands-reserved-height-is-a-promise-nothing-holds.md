---
id: KIN-110
title: 'Guard: the overview band''s reserved height is a promise nothing holds'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 07:40'
labels:
  - 'item:guard-overview-band-reserved'
dependencies: []
ordinal: 110000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 16e. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:guard-overview-band-reserved holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, branch shafic/scripts-guards-16d-16e). Criteria, copied from ROADMAP §3 → Guards, item:guard-overview-band-reserved:
1. With only .axis-hint.axis-reading { min-height: 24px } removed, 'copy lands huge' fails, by comparing the band's height during and after the read or by bounding the move at the scope bar's measured height rather than a fixed 48 px.
2. A check CI runs also fails with that rule removed (e.g. a static reading in check-web-static.js, like 'closed drawer css'); if none can, web-ui.md says the rule is held only by the measure run.
3. Each check proven red with the rule removed and green restored; if INSPECTOR_UI_EXPECT_CHECKS or the static count moves, say why.
Out of scope: changing the band, the rule or the 33 px; KIN-42's other four follow-ups; running measure in CI. No web/ file is touched.
Build: (a) scripts/lib/measure-cases.js copyLandsRow bounds the first visible row's move at #scope-bar's measured height after landing (+1 px rounding) in place of 48 px: the decision says rows move only by the scope bar's height, and the bound follows the bar if polish restyles it. (b) scripts/lib/layout-audits.js gains auditOverviewBand, reported by check-web-static.js as 'overview band css': .axis-hint.axis-reading in css/25-axis.css sets a min-height equal to .axis-svg's height. The static count is not pinned in ci.yml (the step only needs exit 0), so ci.yml should not move; INSPECTOR_UI_EXPECT_CHECKS is render-web-ui.js's and is untouched. One sentence in web-ui.md naming both checks.
<!-- SECTION:NOTES:END -->
