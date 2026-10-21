---
id: KIN-76
title: 'Route stats in the JSON, HTML and Markdown exports'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 12:39'
labels:
  - 'item:endpoint-latency-stats'
dependencies:
  - KIN-75
ordinal: 76000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:endpoint-latency-stats (run of 2026-10-20), §13. PR A.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 export.json routeStats equals the service's unfiltered, unlimited report; a session with no calls has routes []; formatVersion still 1
- [ ] #2 export.md has ## Routes before ## Timeline with {id} routes, n<20 cells, retries in the attempts cell, | escaped
- [ ] #3 HAR output unchanged (HarTest untouched)
- [ ] #4 SessionExport.build takes the templates as a required parameter; both callers pass config.routeTemplates
- [ ] #5 Exports paragraph in docs/decisions/exports.md; DAEMON.md §7 export table mentions Routes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR A, uncommitted. SessionExportDocument.routeStats (no default; formatVersion 1); SessionExport.build(templates) required, both callers pass config.routeTemplates; Markdown ## Routes before ## Timeline; HAR untouched (HarTest unchanged, green). Reds: empty-list default on routes -> key missing; built from queryTransactions -> 1000 vs 2001. NOT in this PR: archiveSource answering /stats from archive.routeStats (web/, T4/PR B) and the archive probe's stats offline lines; exports.md paragraph worded for what PR A ships. Ready for QA.
<!-- SECTION:NOTES:END -->
