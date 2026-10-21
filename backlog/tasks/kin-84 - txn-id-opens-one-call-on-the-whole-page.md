---
id: KIN-84
title: '#/txn/{id} opens one call on the whole page'
status: In Progress
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 14:36'
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
- [ ] #1 txn route live and in the export
- [ ] #2 a call past the ceiling shown, Back returns
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in dd44c74 and 30cd8ce. parseRoute/routeHash (js/08-routes.js) read and write #/txn/<id>?session=<s> as { page: 'txn', txn }; applyRoute draws it on the Traffic page and calls showTxnFull (js/35-detail.js): #panes.txn-full and #page-traffic.txn-page hide the list, divider, drawer chrome, toolbar and overview line; the call is looked up in allTransactions, then transactions, then read from GET /api/sessions/{id}/transactions/{txnId}; in an export a missing id says 'call <id> is not in this export'. #detail-full (the tools row's link) pushes the route; Back returns; Escape goes Back when the page pushed it and otherwise replaces to the session's list (leaveTxnFull); j/k do nothing there. 30cd8ce marks the call's row selected (markSelected) so Back lands on it. Checks: 'route parse' 13 addresses + 8 round trips (red: txn branch removed); live 'txn route' (hash, txn-full, detail, j inert, Back and Escape) and 'txn route past ceiling' on the 1203-call session (whole copy emptied as before it lands; red: lookup in transactions only); archive 'txn route in export' (red: txn-full not set). Ready for QA.
<!-- SECTION:NOTES:END -->
