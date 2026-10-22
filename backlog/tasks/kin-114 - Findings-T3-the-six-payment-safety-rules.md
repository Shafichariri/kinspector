---
id: KIN-114
title: 'Findings T3: the six payment-safety rules'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 08:49'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-113
type: feature
ordinal: 114000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR A. service/PaymentLedger.kt (the fold: compact mutation rows plus RedirectHops; digests and masked hints only), service/PaymentRules.kt (PaymentSource and R1-R6: duplicate mutation, key reused body changed, retry without a key, ambiguous resubmit, key missing on a payment path, retry with a key), PaymentRulesTest, docs/decisions/daemon-and-mcp.md, docs/DAEMON.md. Reads Shared body evidence for mutation and body knowability.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Each R1-R6 fixture in the design gives exactly the stated findings, and each named red proof fails
- [ ] #2 No duplicate is claimed when either body is truncated, omitted, missing or redacted
- [ ] #3 A 3xx predecessor is never a retry
- [ ] #4 NEVER_SENT classes never trigger Ambiguous resubmit
- [ ] #5 No evidence line or id carries a header value, body, error message or digest
- [ ] #6 The payments fixture gives R1 1, R2 1, R3 1, R4 1, R5 5, R6 1, and coverage.payment counts 15 mutation calls and 2 truncated bodies
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges (findings T2+T3 PR). service/PaymentLedger.kt, service/PaymentRules.kt (PaymentSource, version payment-1, NEVER_SENT), PaymentRulesTest (every fixture of the design's R1-R6 tables plus the payments session), daemon-and-mcp.md payment paragraph, DAEMON.md rule table. Red proofs run (each failed, restored): R1 >= for > in the chain (gap 3000 lost); R1 unchained (3-call run became 2); R2 grouped by key+route (two-route finding lost); R3 3xx predecessor read as a retry; R3 changed key read as safe; R4 NEVER_SENT emptied; R4 window from the failure's start; R5 case-sensitive substring; R6 emitted for an unsafe call; R1/R2 row state ignored when reading a body (one-unknown case found). DISCREPANCY with the design's counts, to settle before T5/T7: by the rules as written the payments fixture gives exactly R1 1, R2 1, R3 1, R4 1, R5 5, R6 1 (3 high, 6 medium, 1 info, 10 findings in 18 rows), but coverage.payment counts 13 mutation calls, not 15 (the fixture has 15 distinct calls, 2 of them GETs), and the findings name 12 distinct rows, not 14 (b1-b10 hex 1..a, f, 10). The test asserts 13 and 12. Coverage line: '13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated).' (6 = the R1 pair plus two R2 key groups of two). — bob
<!-- SECTION:NOTES:END -->
