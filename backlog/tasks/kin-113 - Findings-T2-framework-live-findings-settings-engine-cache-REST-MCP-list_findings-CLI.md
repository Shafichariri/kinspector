---
id: KIN-113
title: >-
  Findings T2: framework, live findings settings, engine, cache, REST, MCP
  list_findings, CLI
status: To Do
assignee: []
created_date: '2026-10-22 08:24'
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
