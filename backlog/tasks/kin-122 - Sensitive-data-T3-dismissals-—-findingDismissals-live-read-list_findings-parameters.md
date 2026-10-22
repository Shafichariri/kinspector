---
id: KIN-122
title: >-
  Sensitive data T3: dismissals — findingDismissals, live read, list_findings
  parameters
status: To Do
assignee: []
created_date: '2026-10-22 09:40'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-121
  - KIN-113
type: feature
ordinal: 122000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Files: service/FindingDismissals.kt, archive/FindingSettingsSource.kt, archive/ConfigFile.kt, http/ConfigRoutes.kt, service/FindingsService.kt, http/FindingsRoutes.kt, mcp/FindingsTool.kt, inspector-daemon/src/test/resources/mcp-tools-list.json, docs/DAEMON.md, docs/decisions/daemon-and-mcp.md, test FindingDismissalsTest.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A dismissal matches rule, route, location and path exactly; null matches only null
- [ ] #2 findingDismissals applies on the next read, through Findings' live read, in the daemon and in a second service instance standing for inspector mcp, with no restart
- [ ] #3 Adding a dismissal rescans no body, and ruleVersion does not change
- [ ] #4 Dismissed findings are returned marked; findingCount and the severity counts exclude them; list_findings and REST hide them unless includeDismissed, and always report dismissedCount
- [ ] #5 No MCP tool writes a dismissal
- [ ] #6 Shown red with dismissals read at start and with the rule version computed over them
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
