---
id: KIN-124
title: >-
  Sensitive data T5: the page — dismiss, dismissed fold, no-hosts hint,
  first-party hosts in Settings, probes
status: To Do
assignee: []
created_date: '2026-10-22 09:40'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-122
  - KIN-123
  - KIN-116
  - KIN-117
type: feature
ordinal: 124000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Web, after findings PR B. Files: web/js/98-findings-dismiss.js, web/css/92-findings-dismiss.css, web/js/80-settings.js, web/scripts.txt, web/styles.txt, ProbeFixture.kt, ProbeFixtureTest.kt, scripts/lib/sensitive-probes.js, scripts/render-web-ui.js, scripts/render-web-ui-archive.js, scripts/lib/measure-cases.js, .github/workflows/ci.yml, structure/budgets.txt, docs/decisions/web-ui.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The per-width spec holds at 1440, 1000, 600 and 400, live and in an export
- [ ] #2 A dismissal needs a reason, reads the config afresh and sends only findingDismissals through controlFetch
- [ ] #3 The finding moves to dismissed (n) only after the daemon's reply; restore removes the entry
- [ ] #4 Dismiss and restore are hidden in an export; the fold is drawn there
- [ ] #5 The page's Findings subtree never shows a planted raw value, live or exported
- [ ] #6 Settings edits findings.sensitive.firstPartyHosts through Findings' configPatch(), which carries the rest of findings.sensitive as the file holds it
- [ ] #7 The sensitive coverage line and every sensitive rule's line show on the Findings page; with no firstPartyHosts the page says nothing is raised for where data was sent and links Settings
- [ ] #8 Probe fixture plants without new rows; every existing probe count unchanged except the new lines
- [ ] #9 Each probe line was shown red, and the Pace neighbour too
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
