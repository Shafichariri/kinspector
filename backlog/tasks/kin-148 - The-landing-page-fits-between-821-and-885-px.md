---
id: KIN-148
title: The landing page fits between 821 and 885 px
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 07:59'
updated_date: '2026-10-23 08:26'
labels:
  - 'item:landing-page-fits-821-885'
dependencies: []
ordinal: 148000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 36. Placed 2026-10-23 by the product owner (PR #187). Acceptance criteria are the roadmap item's own, from docs/ROADMAP.md under the heading carrying this ticket's item label. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 No width from 360 to 1440 in 5 px steps has a scrollWidth above the window
- [ ] #2 The 400 px guard's check is extended to that sweep, or to a width in the band, and proven red
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
bob: PR #190, merge 787bad9. .grid2 > * { min-width: 0 } moved from the 820 px query to its own (max-width: 900px) query; not everywhere, because at 1440 the second card's content sets its column (478/538) and the rule would even them. New reading 'site 360-1440 document width' in scripts/lib/site-cases.js (5 px sweep, resize in place). Red on main's site/index.html: 13 of 217 widths (825..885, all 886 px), exit 1; green 0 of 217. 1 px sweep: main 65 widths 821..885, head none. 1440 digest 1469658b unchanged; every box at 400 and 1440 identical to main. Check: node scripts/measure-web-ui.js --only site. Ready for QA.
<!-- SECTION:NOTES:END -->
