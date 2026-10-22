---
id: KIN-115
title: 'Findings T4: findings in every export, and the leak guard'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 08:57'
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
- [ ] #1 The JSON/HTML export carries findings for the whole session (2001 rows), with no default; an empty session exports findings: [] with six rules
- [ ] #2 The Markdown ## Findings sits between ## Routes and ## Timeline
- [ ] #3 Named HAR entries carry finding <severity> <ruleId>: <message>
- [ ] #4 HarTest passes unedited, and a no-finding HAR is byte-identical to base
- [ ] #5 FindingsLeakTest finds no planted secret in REST, MCP, export findings, the Markdown section, HAR finding notes or accumulator fields; it is red with the raw key in masked and the error message in a note, and red with nothing planted
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges (findings T4 PR). service/Export.kt (SessionExportDocument.findings, no default; build takes a required FindingsEngine and folds the archived rows in file order), service/ExportMarkdown.kt (## Findings between ## Routes and ## Timeline), service/Har.kt (one 'finding <severity> <ruleId>: <message>' note per finding naming the entry), http/ExportRoutes.kt (HAR findings from the whole session whatever the filter), mcp/ExportTools.kt, McpTools/InspectorDaemon share one engine; FindingsEngine.byTxn. Tests: ExportTest (+3; build helper passes an engine), HarFindingsTest, FindingsLeakTest; HarTest not edited. Red proofs run (each failed, restored): export findings computed from queryTransactions; zero-count rules left out (6-rule check); findings section after the timeline; route passing no findings to the HAR; a 'findings: N' note on every entry (HarFindingsTest's plain-entry check); raw key kept as the hint (caught in the accumulator fields; the engine's guard had already replaced it in every reply); error message kept as the class (REST, MCP, Markdown, HAR notes and accumulators all fail); nothing planted (self-check fails). DEVIATION: Har.export's findingsByTxn has an emptyMap() default, not required, because HarTest must pass unedited and calls Har.export without it; the route always passes it and HarFindingsTest holds that. NOT A GUARD: the design's 'add a log.comment line and HarTest fails' was run and HarTest stayed green (it checks the comment's content, not bytes); the plain-entry check in HarFindingsTest is what holds 'nothing else changes'. — bob
<!-- SECTION:NOTES:END -->
