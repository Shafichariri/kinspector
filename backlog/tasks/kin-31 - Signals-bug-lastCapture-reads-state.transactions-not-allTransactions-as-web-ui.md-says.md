---
id: KIN-31
title: >-
  Signals bug: lastCapture reads state.transactions, not allTransactions as
  web-ui.md says
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 31000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel on KIN-24, head 5f6b610. Small. docs/decisions/web-ui.md says lastCapture reads allTransactions; the code (85-processes.js) must be checked and made to agree.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 lastCapture reads state.allTransactions, as web-ui.md says it does, or web-ui.md is corrected if the code is right, with the reason recorded
- [ ] #2 A probe or static check covers it, red then green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
