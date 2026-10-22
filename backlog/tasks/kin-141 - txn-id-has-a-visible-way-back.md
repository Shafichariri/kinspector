---
id: KIN-141
title: '#/txn/<id> has a visible way back'
status: To Do
assignee: []
created_date: '2026-10-22 19:26'
labels:
  - 'item:web-txn-page-way-back'
dependencies:
  - KIN-102
type: bug
ordinal: 141000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 28, docs/ROADMAP.md §3 → Bugs found, not fixed (lane W, after polish A1, KIN-102). From the hand-over and QA on item:web-foundation-list-room (1b). A web UI user who opened a call on its own page, from ⤢ or from a pasted address, has only the browser's Back and Esc to leave it, and nothing on the page says so; a call opened in a new tab has no Back to press. Out of scope: changing what Back and Esc do; the size of the ⤢ and ⇥ glyphs (Web UI polish's). Overturns no recorded decision: web-ui.md records that Back and Escape leave the page, not that nothing else may. Web UI only. Size S.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The #/txn page shows a control that leaves it, keyboard-reachable and named for where it goes; it does what Esc does (leaveTxnFull): Back when ⤢ pushed the page, otherwise the session's list
- [ ] #2 At 400px it is on screen, and in an export it works from the embedded data
- [ ] #3 A probe line presses it in both cases; it is proven red with the control removed
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
