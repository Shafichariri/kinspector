---
id: KIN-119
title: 'Findings T8: flagged rows only view toggle'
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-118
type: feature
ordinal: 119000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B or C (web). web/js/97-findings.js, 25-traffic-list.js, index.html, css/90-findings.css, scripts/lib/findings-probes.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 ⋮ → flagged rows only leaves the 14 flagged rows with a band saying what it counts and that it is on; off restores 18
- [ ] #2 It is not remembered across a reload
- [ ] #3 A no-findings session says "No calls with findings among the calls loaded" with "show all calls"
- [ ] #4 The band passes sticky top
- [ ] #5 25-traffic-list.js stays ≤ 600 lines
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
