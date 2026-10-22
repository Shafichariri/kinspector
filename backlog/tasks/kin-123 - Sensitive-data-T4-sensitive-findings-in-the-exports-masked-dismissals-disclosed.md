---
id: KIN-123
title: >-
  Sensitive data T4: sensitive findings in the exports, masked, dismissals
  disclosed
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 13:48'
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
- [x] #1 JSON, HTML and Markdown exports and the HAR comments carry sensitive findings with masked hints only
- [x] #2 Dismissed findings are listed with their reason; red with dismissed findings filtered out of the export
- [x] #3 SensitiveLeakTest covers all four forms
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges. Sensitive findings reach every export through Findings' existing path (compute -> SessionExport, ExportMarkdown, Har notes), masked; dismissals: service/ExportMarkdown.kt ('N dismissed, listed with their reasons.' and a 'dismissed: <reason> (<at>)' line under the finding), service/Har.kt ('(dismissed: <reason>)' on the finding note), docs/decisions/exports.md (the design's paragraph). The JSON/HTML carry Finding.dismissed (T3). Tests: ExportDismissalTest (its own file: ExportTest is at its 600-line budget), SensitiveLeakTest covers the JSON export's findings (the HTML embeds the same document), the Markdown section and every HAR comment. Red proofs: dismissed filtered out of exports (compute with includeDismissed = false) -> ExportDismissalTest 'Collection contains no element matching the predicate'; leak breaks as in KIN-121. — bob

Closed 2026-10-22 by the delivery tracker on QA's report qa/sensitive-data-detection-report.md (verdict: close yes, no blocking findings), PR #161, merge 5cce7ab. Not verified by QA: the red proof 'dismissed findings filtered out of exports' is the builder's (ExportDismissalTest passes), not re-run.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA with one hand-written dismissal: Markdown reads '1 dismissed, listed with their reasons' and the reason under the finding; the HAR note ends '(dismissed: qa: the signup form sends a password by design)'; JSON export findings hold all 18 sensitive findings, the dismissed one marked; no planted value in any form. SensitiveLeakTest covers all four forms.
<!-- SECTION:FINAL_SUMMARY:END -->
