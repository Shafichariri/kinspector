---
id: KIN-118
title: 'Findings T7: severity badge on rows and a Findings section in the detail pane'
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-116
type: feature
ordinal: 118000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B or C (web). Q1 answered 2026-10-22: keep both; the badge never says duplicate or repeat and cites no repeat group. web/js/97-findings.js, 25-traffic-list.js, 35-detail.js, css/90-findings.css, scripts/lib/findings-probes.js, scripts/measure-web-ui.js, docs/decisions/daemon-and-mcp.md, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 On the payments session exactly the 12 flagged rows carry .finding-badge with the severity of their first finding in words, no hue, and never "duplicate" or "repeat"
- [ ] #2 At 400 px path tails and byte counts stay whole, with the badge short
- [ ] #3 A flagged row's detail shows a Findings section with title, message, this row's evidence and open in Findings; unflagged rows show none; #/txn/<id> shows it live and in an export
- [ ] #4 A reply that changes a badge redraws the list without refetching the detail's bodies
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
