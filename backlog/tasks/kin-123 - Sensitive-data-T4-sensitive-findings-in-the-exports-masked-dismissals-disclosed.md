---
id: KIN-123
title: >-
  Sensitive data T4: sensitive findings in the exports, masked, dismissals
  disclosed
status: To Do
assignee: []
created_date: '2026-10-22 09:40'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-122
  - KIN-115
type: feature
ordinal: 123000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Files: Findings' export code if needed (service/Export.kt, service/ExportMarkdown.kt, service/Har.kt, http/ExportRoutes.kt, mcp/ExportTools.kt), ExportTest, SensitiveLeakTest (on KIN-115's FindingsLeakTest harness), docs/decisions/exports.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 JSON, HTML and Markdown exports and the HAR comments carry sensitive findings with masked hints only
- [ ] #2 Dismissed findings are listed with their reason; red with dismissed findings filtered out of the export
- [ ] #3 SensitiveLeakTest covers all four forms
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
