---
id: KIN-16
title: >-
  Phase F bug: nothing checks the cross-file 1199px @media override order,
  though web-ui.md says static check 4 pins it
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-13
type: bug
ordinal: 16000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (architect S1, confirmed by QA). docs/decisions/web-ui.md says the cross-file 1199px @media block's load order is 'Pinned by static check 4 ... and by the concatenation proof'. Neither checks order, and the proof is uncommitted. Repro (QA): rename css/35-list.css to css/08-list.css and reorder styles.txt. Static check stays 24/0, :inspector-daemon:test, :inspector-ui:jvmTest, live probe 164 and archive probe 21 all stay green, while headless Chrome shows style and PNG changes in all 12 configs under 1200px.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A zero-dependency static check in scripts/lib/web-audits.js, printed in the '--- scripts ---' section, fails when, for a selector inside an @media block, a plain (non-@media) rule with the same selector loads later in styles.txt order
- [ ] #2 Proven red by renaming css/35-list.css to css/08-list.css with styles.txt reordered, and green again once restored
- [ ] #3 The first 24 lines of check-web-static.js output stay byte-identical
- [ ] #4 docs/decisions/web-ui.md 'Pinned by' names the new check and no longer cites the uncommitted concatenation proof
- [ ] #5 A comment above #panes in css/10-panes.css points at the override in css/35-list.css
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
