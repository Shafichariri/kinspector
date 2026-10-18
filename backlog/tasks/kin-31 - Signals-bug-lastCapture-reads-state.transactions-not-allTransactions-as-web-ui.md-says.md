---
id: KIN-31
title: >-
  Signals bug: lastCapture reads state.transactions, not allTransactions as
  web-ui.md says
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
updated_date: '2026-10-18 21:27'
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
- [x] #1 lastCapture reads state.allTransactions, as web-ui.md says it does, or web-ui.md is corrected if the code is right, with the reason recorded
- [x] #2 A probe or static check covers it, red then green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit d3843c6. Code was wrong, doc right: lastCapture (85-processes.js) now reads state.allTransactions with state.allSignals. web-ui.md's allTransactions paragraph now names the banner as a reader. New static section '--- session state ---' in check-web-static.js (scripts/lib/session-state-checks.js runs 85-processes.js in a vm). RED (old line): 'last capture whole        : NO - newest row in this session 2026-01-01T09:00:01.000Z, the session's newest is 2026-01-01T09:00:09.000Z', 30 checks, 1 failed. GREEN: 'last capture whole        : yes (newest row in this session is 09:00:09, ...)', 30 checks, 0 failed. Live probe unchanged at 169 on that commit (lastCapture line still yes). Ready for QA.

QA (second verify panel, head e1711a3): AC1-2 Verified. Not closed; the item closes together.
<!-- SECTION:NOTES:END -->
