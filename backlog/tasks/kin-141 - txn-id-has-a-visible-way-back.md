---
id: KIN-141
title: '#/txn/<id> has a visible way back'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 19:26'
updated_date: '2026-10-23 07:23'
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
- [x] #1 The #/txn page shows a control that leaves it, keyboard-reachable and named for where it goes; it does what Esc does (leaveTxnFull): Back when ⤢ pushed the page, otherwise the session's list
- [x] #2 At 400px it is on screen, and in an export it works from the embedded data
- [x] #3 A probe line presses it in both cases; it is proven red with the control removed
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. index.html: #txn-back, a .btn btn-sm btn-quiet with an aria-hidden SVG arrow and the text 'traffic', aria-label 'Back to the traffic list', title with (Esc), first in #detail-tools.
2. css/10-panes.css: drawn only under #panes.txn-full (class display, not [hidden]); min-height var(--target-min); left of the row.
3. js/99-init.js: click -> leaveTxnFull, so it is Esc's handler, not a copy.
4. Live probe 'txn back' (lib/list-room-probes.js, in probeColumns): not drawn off the page; after ⤢ it goes Back (forward reopens the page); from an address it replaces with the session's list.
5. Archive probe 'txn back in export': pressed on an embedded call's page, lands on traffic with no request.
6. Chrome 'polish <w> txn back <where>' at 1440 and 400, served and exported: inside the window, at least 24px tall, hit at its centre, a click there leaves the page; NO on an empty scan.
7. ci.yml counts, web-ui.md #/txn paragraph; red proofs with the control removed, plus a Pace neighbour.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built uncommitted on shafic/web-txn-page-way-back (stacked on polish part 3, 5646aae).
Control: #txn-back '← traffic' (svg arrow + word, aria-label 'Back to the traffic list', title '(Esc)'), first in #detail-tools, drawn only under #panes.txn-full (css/10-panes.css), min-height --target-min; click is leaveTxnFull itself (js/99-init.js).
Checks: live 'txn back' (lib/list-room-probes.js, probeColumns): off-page not drawn; after ⤢ Back (Forward reopens); from there replaces with #/traffic?session=…, no entry added. Archive 'txn back in export'. Chrome 'polish <w> txn back <where>' at 1440/400, served/exported.
Counts: live 304->305, archive 59->60 (ci.yml), static 134 unchanged.
Red proofs: live NO with #txn-back removed and with the click wired to navigate-replace (Pace: 'txn route' NO with Escape disabled, same run); archive NO with #txn-back removed (Pace: 'txn route in export' NO with its sentence changed, same run); Chrome NO on an empty scan (show rule removed) and 'outside the window' (translateX(-400px)).
measure --only polish,room,shell vs 5646aae: only the four 'polish <w> txn back' readings moved, NO -> inside, hit, leaves; head 110/0 failed.
Not done: the control scrolls away with a long call's page (not sticky); Escape still works there.

Closed 2026-10-23 by the delivery tracker. PR #183, merge abb3622. QA report: qa/web-txn-page-way-back-report.md (run folder of 2026-10-20), verdict close - yes.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Row 28, the back control on #/txn. QA (Doug): static 134/0; live probe 305/0; archive 60/0; Chrome head 346 readings, 1 failed (run toggle big 1440 oldest-first, a known flake, 6/0 when run alone). By hand: a fresh tab opened on #/txn/<id> left for the list on a real click; through the expand button, Back then Forward worked. Reds R1 (control and wiring removed), R2 (always opens the list), R3 (Chrome show rule removed). Merge abb3622 (#183). QA report web-txn-page-way-back-report.md.
<!-- SECTION:FINAL_SUMMARY:END -->
