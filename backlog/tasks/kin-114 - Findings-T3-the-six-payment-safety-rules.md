---
id: KIN-114
title: 'Findings T3: the six payment-safety rules'
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
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
