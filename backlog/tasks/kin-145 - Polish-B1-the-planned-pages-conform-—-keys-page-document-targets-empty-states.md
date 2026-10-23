---
id: KIN-145
title: >-
  Polish B1: the planned pages conform — keys, page document, targets, empty
  states
status: To Do
assignee: []
created_date: '2026-10-23 05:16'
labels:
  - 'item:web-ui-polish'
dependencies:
  - KIN-102
  - KIN-103
  - KIN-104
  - KIN-105
  - KIN-106
  - KIN-107
  - KIN-139
  - KIN-140
  - KIN-116
  - KIN-117
  - KIN-118
  - KIN-119
  - KIN-124
ordinal: 145000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR B. Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9 B1/B2; questions answered by the 2026-10-22 decision records. Deps A1–A6 (KIN-102..107), session diff T5/T6 (KIN-139/140), findings T5–T8 (KIN-116..119), detection T5 (KIN-124).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 g d opens #/diff and g f opens #/findings live (page keys diff, page keys findings, each red with its letter removed); in an export g f opens Findings and g d does nothing
- [ ] #2 #page-findings carries page-doc
- [ ] #3 At 1440 and 400 every visible .btn on Diff and Findings, dismiss and restore included, is at least 24px tall, and every .settings-note/.page-prose is at most 72ch at 1440
- [ ] #4 Each why-state on Diff and Findings makes none of the three forbidden claims of §3 P10 (one probe line per page)
- [ ] #5 shell <w> page diff fits and findings fits stay green
- [ ] #6 The Pace red is repeated and the counts are raised
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
