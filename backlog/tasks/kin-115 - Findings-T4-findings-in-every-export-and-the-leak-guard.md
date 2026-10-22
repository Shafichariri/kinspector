---
id: KIN-115
title: 'Findings T4: findings in every export, and the leak guard'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 13:48'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-114
type: feature
ordinal: 115000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR A. service/Export.kt (findings, required engine, no default), service/ExportMarkdown.kt (## Findings between ## Routes and ## Timeline), service/Har.kt (entry notes), http/ExportRoutes.kt, mcp/ExportTools.kt, ExportTest, HarFindingsTest, FindingsLeakTest, docs/decisions/exports.md, docs/DAEMON.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The JSON/HTML export carries findings for the whole session (2001 rows), with no default; an empty session exports findings: [] with six rules
- [x] #2 The Markdown ## Findings sits between ## Routes and ## Timeline
- [x] #3 Named HAR entries carry finding <severity> <ruleId>: <message>
- [x] #4 HarTest passes unedited, and a no-finding HAR is byte-identical to base
- [x] #5 FindingsLeakTest finds no planted secret in REST, MCP, export findings, the Markdown section, HAR finding notes or accumulator fields; it is red with the raw key in masked and the error message in a note, and red with nothing planted
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges (findings T4 PR). service/Export.kt (SessionExportDocument.findings, no default; build takes a required FindingsEngine and folds the archived rows in file order), service/ExportMarkdown.kt (## Findings between ## Routes and ## Timeline), service/Har.kt (one 'finding <severity> <ruleId>: <message>' note per finding naming the entry), http/ExportRoutes.kt (HAR findings from the whole session whatever the filter), mcp/ExportTools.kt, McpTools/InspectorDaemon share one engine; FindingsEngine.byTxn. Tests: ExportTest (+3; build helper passes an engine), HarFindingsTest, FindingsLeakTest; HarTest not edited. Red proofs run (each failed, restored): export findings computed from queryTransactions; zero-count rules left out (6-rule check); findings section after the timeline; route passing no findings to the HAR; a 'findings: N' note on every entry (HarFindingsTest's plain-entry check); raw key kept as the hint (caught in the accumulator fields; the engine's guard had already replaced it in every reply); error message kept as the class (REST, MCP, Markdown, HAR notes and accumulators all fail); nothing planted (self-check fails). DEVIATION: Har.export's findingsByTxn has an emptyMap() default, not required, because HarTest must pass unedited and calls Har.export without it; the route always passes it and HarFindingsTest holds that. NOT A GUARD: the design's 'add a log.comment line and HarTest fails' was run and HarTest stayed green (it checks the comment's content, not bytes); the plain-entry check in HarFindingsTest is what holds 'nothing else changes'. — bob

Closed 2026-10-22 by the delivery tracker on QA's report qa/findings-pr-a-report.md (verdict: close yes, no blocking findings), PR #153, merge e751a25. Not verified by QA: MCP export_session called directly (it shares SessionExport with the REST export and is read by FindingsLeakTest); a 2001-row whole-session export (builder's ExportTest). The HAR's existing transport-error comment predates findings and carries the call's own error text, unchanged.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA on the findings session, 8 of 8 rows Verified: JSON export findings carry masked hints only; Markdown ## Findings between ## Routes and ## Timeline; HTML embeds the report; HAR entries carry 'finding <severity> <ruleId>: <message>'; a no-findings HAR is byte-identical to base (cmp, 4294 bytes), HarTest unedited and green. FindingsLeakTest red with nothing planted ('the fixture planted nothing to find'), with a raw key kept (caught in accumulator fields) and with both error-message guards removed. Daemon 460 tests, 0 failed.
<!-- SECTION:FINAL_SUMMARY:END -->
