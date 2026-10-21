---
id: KIN-84
title: '#/txn/{id} opens one call on the whole page'
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-79
ordinal: 84000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 txn route live and in the export
- [x] #2 a call past the ceiling shown, Back returns
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in dd44c74 and 30cd8ce. parseRoute/routeHash (js/08-routes.js) read and write #/txn/<id>?session=<s> as { page: 'txn', txn }; applyRoute draws it on the Traffic page and calls showTxnFull (js/35-detail.js): #panes.txn-full and #page-traffic.txn-page hide the list, divider, drawer chrome, toolbar and overview line; the call is looked up in allTransactions, then transactions, then read from GET /api/sessions/{id}/transactions/{txnId}; in an export a missing id says 'call <id> is not in this export'. #detail-full (the tools row's link) pushes the route; Back returns; Escape goes Back when the page pushed it and otherwise replaces to the session's list (leaveTxnFull); j/k do nothing there. 30cd8ce marks the call's row selected (markSelected) so Back lands on it. Checks: 'route parse' 13 addresses + 8 round trips (red: txn branch removed); live 'txn route' (hash, txn-full, detail, j inert, Back and Escape) and 'txn route past ceiling' on the 1203-call session (whole copy emptied as before it lands; red: lookup in transactions only); archive 'txn route in export' (red: txn-full not set). Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified in Chrome. AC1: live 'txn route', archive 'txn route in export' pass; the unknown id reads 'no transaction no-such-id' served and 'call no-such-id is not in this export' in the export. AC2: oldest call b0000000 of the 1203-call session, not among the held rows, opens on its own page; Back and Esc return to #/traffic. DoD2: QA ran the driver on base and head, and base has no such route. QA ran no mutation on the txn route, so the engineer's red proofs rest on the hand-over. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
