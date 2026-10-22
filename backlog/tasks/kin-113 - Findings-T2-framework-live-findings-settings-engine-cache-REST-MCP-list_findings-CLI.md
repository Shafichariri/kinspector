---
id: KIN-113
title: >-
  Findings T2: framework, live findings settings, engine, cache, REST, MCP
  list_findings, CLI
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 08:49'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-111
  - KIN-112
type: feature
ordinal: 113000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR A. archive/FindingSettings.kt (catalogue with the six payment ids, the parsed findings key, its validation), archive/FindingSettingsSource.kt (the live read), service/Findings.kt, service/FindingsEngine.kt (FindingSources.all, the single registry), service/FindingsService.kt (LRU of 4 per session real path and ruleVersion), http/FindingsRoutes.kt, mcp/FindingsTool.kt, Main.kt (inspector findings), ConfigFile, ConfigRoutes, DaemonConfig, InspectorDaemon, McpTools, mcp-tools-list.json, docs/DAEMON.md, docs/decisions/daemon-and-mcp.md (the live-read narrowing, recorded), inspector-daemon/AGENTS.md. Tested with a test-only source.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /api/sessions/{id}/findings answers 200 with sessionId, ruleVersion, total, findingCount, high, medium, info, rules (six payment ids, catalogue order, with counts), findings and coverage; 404 for an unknown session; 400 naming the categories for an unknown category
- [ ] #2 Each Finding has exactly the design's fields, with txnIds and callIds capped at 200 and txnCount the total
- [ ] #3 The unregistered-id drop and the reply's rules read each source instance's rules, not FindingRuleCatalog.ALL: a test source with a settings-built custom rule id keeps its finding and lists the rule (red with the check reading ALL)
- [ ] #4 list_findings returns the same report, an unknown category is isError, and the golden holds the design's description verbatim
- [ ] #5 inspector findings prints the same report
- [ ] #6 PUT /api/config refuses an unknown rule id, an unknown sub-key, a path:-prefixed pattern and an out-of-range window, each with a 400 naming it; findings: null removes the key
- [ ] #7 An edit to the findings key changes the next reply, in the daemon and in a second service instance, with no restart; an unchanged file is not re-parsed; a broken hand edit keeps the last good settings and sets configError; red both ways
- [ ] #8 GET /api/config effective.findings reflects the file as last read
- [ ] #9 A disabled rule has no findings and is listed enabled: false; an override changes severity; the order is severity, then time
- [ ] #10 A masked with more than ten unmasked characters becomes ••••, red both ways
- [ ] #11 The fold equals a cold compute; the list is collected again only when the row count or ruleVersion changed; a finding on row 1203 is found
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges (findings T2+T3 PR). Built: archive/FindingSettings.kt (catalogue, findings key, problem/read), archive/FindingSettingsSource.kt (live read: stat on every read, re-parse on size/mtime/fileKey change, last good kept on a broken edit with configError), service/Findings.kt (model, FindingMask, FindingSource/FindingSession/FindingRow/FindingAccumulator; FindingSource gains note(ruleId) for the rule row's note), service/FindingsEngine.kt (FindingSources.all, ruleVersion, collect, report, compute, parseCategory), service/FindingsService.kt (LRU 4 by real path + ruleVersion, IndexCursor fold), http/FindingsRoutes.kt, mcp/FindingsTool.kt (description verbatim; golden regenerated), Main.kt (inspector findings), ConfigFile/DaemonConfig/ConfigRoutes (effective.findings, findingsConfigError), daemon-and-mcp.md (config.json sentence narrowed, routeTemplates sentence, three paragraphs), DAEMON.md §6/§7/§8, inspector-daemon/AGENTS.md. Tests: FindingSettingsTest, FindingSettingsSourceTest, ConfigFileTest (+4), ConfigRoutesTest (+4; two existing asserts compare effective without findings), FindingsEngineTest, FindingMaskGuardTest, FindingsServiceTest, FindingsRouteTest, mcp/FindingsToolTest, FindingsCliTest (bad category exits 1, in a child JVM), McpProtocolTest list. Red proofs run (each failed, restored): PathTerm check dropped; parse cached forever (edit unseen, also in the second service); parse on every call (counter 5 not 1); ConfigFile skipping FindingSettings.problem (400 became 200); effective.findings fixed at start (5000 expected, 3000 seen); severity dropped from the order; registered ids read from FindingRuleCatalog.ALL (custom-rule test: expected [test.custom.mine] but was []); mask check removed (raw 4111111111111111 survives); every hint rejected (•••• 4242 became ••••); collect on every read (expected 1 but was 4); fold through queryTransactions (expected 1203 but was 1000); cache key without ruleVersion (edit unseen); description reworded (golden fails); CLI ignoring --category. Deviation: the row-1203 case puts the finding on the OLDEST of 1203 rows, because queryTransactions keeps the newest 1000 and would still see the 1203rd. — bob
<!-- SECTION:NOTES:END -->
