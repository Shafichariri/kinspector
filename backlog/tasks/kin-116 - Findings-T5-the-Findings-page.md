---
id: KIN-116
title: 'Findings T5: the Findings page'
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-115
  - KIN-66
  - KIN-70
  - KIN-82
  - KIN-84
type: feature
ordinal: 116000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B (web). ProbeFixture payments session, web/js/97-findings.js, web/css/90-findings.css, scripts.txt, styles.txt, index.html, 08-routes.js, 05-network.js, 90-sessions.js, 99-init.js, scripts/lib/findings-probes.js, scripts/lib/findings-refresh-checks.js, render-web-ui.js, render-web-ui-archive.js, check-web-static.js, measure-web-ui.js, ci.yml, structure/budgets.txt, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A Findings rail entry after Stats opens #/findings
- [ ] #2 The probe session reads "No findings in 16 calls in this session" and lists six rules as checked
- [ ] #3 The payments session shows the fixture counts under "Payment safety" (3 high, 6 medium, 1 info; 10 findings in 18 calls; 12 flagged rows; coverage 13 mutation calls, 2 bodies unknown — corrected from the design, which said 14 and 15; PaymentRulesTest asserts 13 and 12), with its coverage line
- [ ] #4 A call link opens #/txn/<id> and Back returns; #/findings?id=<id> scrolls to and marks that finding
- [ ] #5 The page text holds no planted key
- [ ] #6 Live, it asks at most once a second, never twice at once, only while Traffic or Findings is shown with live tail on (static findings refresh lines, each red with its line removed)
- [ ] #7 In an export it draws from the embedded report, refuses a category, and keeps the rail link
- [ ] #8 No sideways scroll at 400, 600, 1000 or 1440 px
- [ ] #9 Counts are stated before → after, and the ci.yml numbers raised to them
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
