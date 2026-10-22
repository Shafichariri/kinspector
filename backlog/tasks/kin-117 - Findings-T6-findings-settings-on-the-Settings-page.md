---
id: KIN-117
title: 'Findings T6: findings settings on the Settings page'
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-113
  - KIN-114
type: feature
ordinal: 117000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B (web). web/js/80-settings.js, web/index.html, web/css/90-findings.css, scripts/lib/findings-probes.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Settings lists every rule of effective.findings.rules with an enabled box and a severity select, and the five payment settings
- [ ] #2 A save sends one findings object holding only non-default rule entries and changed payment fields, carrying other sub-objects through untouched; a rule back at its defaults is removed
- [ ] #3 The daemon's 400 text is shown
- [ ] #4 Success reads "saved — findings use it now"
- [ ] #5 A probe stubs the PUT and checks the bodies (red with the whole effective object sent)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
