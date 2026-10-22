---
id: KIN-122
title: >-
  Sensitive data T3: dismissals — findingDismissals, live read, list_findings
  parameters
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 10:02'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges. BUILT AHEAD OF KIN-121 (T2): dismissals are generic (the design says 'built generic: a payment finding dismisses the same way') and need nothing from T2 or from session diff's JsonPaths, so the criteria are shown on payment findings; T2 is blocked on the JsonPaths walker (see the coordinator report). Files: archive/FindingSettingsSource.kt (live() returns settings, dismissals and error from one read; dismissals kept apart from FindingSettings so never in ruleVersion; a bad findingDismissals keeps the last good list), service/FindingDismissals.kt (exact four-part match, null only null), service/FindingsEngine.kt (Rules.dismissals; report() marks, counts and lists; compute() for exports keeps dismissed findings marked), service/Findings.kt (FindingsReport.dismissedCount, additive), service/FindingsService.kt, http/FindingsRoutes.kt (includeDismissed=true|false, else 400), mcp/FindingsTool.kt (includeDismissed, description), mcp/ToolGroup.kt (booleanProperty, flag), http/ConfigRoutes.kt (effective.findingDismissals), archive/ConfigFile.kt doc, mcp-tools-list.json regenerated, docs/DAEMON.md (§6 findingDismissals, §7, §8), docs/decisions/daemon-and-mcp.md (config sentence, live-read paragraph, the dismissal paragraph). A rule's count still includes dismissed findings, so a rule whose findings were all set aside never reads 'checked, none found'; findingCount and high/medium/info exclude them. The CLI 'inspector findings' takes no new flag: it hides dismissed findings and prints dismissedCount. Tests: FindingDismissalsTest (6), FindingsRouteTest (+1, shape +dismissedCount), ConfigRoutesTest (+1). Red proofs (each run, seen failing, restored): dismissals read at start (live() keeps the first list) -> 4 FindingDismissalsTest cases fail, e.g. 'a dismissal applies on the next read here and in a second service' expected <1> but was <0>; rule version computed over dismissals -> 'dismissals are not part of the rule version ==> expected: <716d5de5> but was: <1f5060e8>'; ConfigFile imported into mcp/FindingsTool.kt -> 'no MCP tool can write a dismissal' was [FindingsTool.kt]; a dismissFinding argument on list_findings alone -> was [list_findings: dismissFinding]. — bob
<!-- SECTION:NOTES:END -->
