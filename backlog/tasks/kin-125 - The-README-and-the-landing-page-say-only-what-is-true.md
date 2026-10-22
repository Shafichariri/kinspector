---
id: KIN-125
title: The README and the landing page say only what is true
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 10:51'
updated_date: '2026-10-22 10:51'
labels:
  - 'item:public-claims-true'
dependencies: []
type: docs
ordinal: 125000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §3 → Bugs found, not fixed → 'The README and the landing page say things that are not true' (order row 13b, lane S). Surfaces: README.md and site/index.html only. No public API, no INTEGRATION.md change.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 README's phone sentences say what docs/STATUS.md says, no more: the overlay ran on one iPhone over USB and nobody looked at it there; the Android sentence states only what the record proves either way. The rest of the 'Honest gaps' paragraph is re-read against STATUS and kept only where it holds.
- [ ] #2 README's count is STATUS's last full count (886, 0 failures) with STATUS's caveat that it is old; never a total made by adding per-module counts.
- [ ] #3 The sponsor box says sponsoring pays for maintenance and what is on the roadmap, links the roadmap, names no unstarted item and nothing on the page reads as a promise of proxy capture.
- [ ] #4 Every other hardware, count or future-feature claim in README.md and site/index.html is read against STATUS and the roadmap, and the change lists each one checked (the landing page's Android row among them).
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Android: the maintainer has not yet said whether the 2026-08-17 Android run (commit ef9058e, 'First run on an Android device') was the sample's build or a consuming app's. The README and landing page therefore state only what holds either way: the overlay ran once on an Android device on 2026-08-17, in a build that predates the Android sample (added 2026-09-18, 42706f1), and the Android sample has not been run on a physical phone (STATUS → Not verified; the overlay commits since say 'no build of this has run on a phone'). The maintainer may sharpen this sentence once they answer.
<!-- SECTION:NOTES:END -->
