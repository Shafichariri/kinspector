---
id: KIN-74
title: 'Route templates, their filter terms and the routeTemplates config key'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 12:39'
labels:
  - 'item:endpoint-latency-stats'
dependencies:
  - KIN-65
ordinal: 74000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:endpoint-latency-stats (run of 2026-10-20), §13. PR A.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 RouteTemplatesTest passes with each class, each documented miss and every override rule; red with the hex threshold at 16 and with the digit requirement dropped
- [ ] #2 Every term in RouteFilterTermTest parses and matches its transaction over the fixed corpus and 5,000 seeded random paths; red with trailing slashes normalised and with {id} emitted verbatim
- [ ] #3 DaemonConfig.load reads routeTemplates at start; an invalid entry is named once on stderr and skipped
- [ ] #4 PUT /api/config writes a valid routeTemplates list and refuses bad entries with a 400 naming them, file byte-identical; GET effective.routeTemplates is what was loaded at start
- [ ] #5 Decision paragraphs, DAEMON.md §6 section and the archive/ layer row updated; DaemonLayersTest passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR A, uncommitted on shafic/endpoint-latency-stats. archive/RouteTemplates.kt (heuristic, overrides, problems(), filterTerm, compose); DaemonConfig.routeTemplates + FileConfig.routeTemplates; ConfigFile.merge validates routeTemplates (array of strings, problems() first entry, 400); GET/PUT /api/config effective.routeTemplates = compiled at start. Tests: RouteTemplatesTest (9), RouteFilterTermTest (5, 5000 seeded paths), DaemonConfigTest +4, ConfigFileTest +3, ConfigRoutesTest +2. Reds shown: hex {16,} -> '/blobs/{id}' vs '/blobs/1a2b3c4d'; digit dropped -> deadbeefcafe read as {id}; trailing slash normalised -> '/users/1/profile/' term does not match; {id} verbatim -> 'path:*/users/{id}' does not match; load reads nothing; merge skips problems() -> bad entries Written; effective from file -> ["/articles/{slug}"] vs []. Docs: daemon-and-mcp.md (2 route paragraphs), DAEMON.md §6 routeTemplates, daemon AGENTS.md archive row. Ready for QA.
<!-- SECTION:NOTES:END -->
