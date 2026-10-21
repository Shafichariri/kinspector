---
id: KIN-76
title: 'Route stats in the JSON, HTML and Markdown exports'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 12:18'
updated_date: '2026-10-21 16:25'
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
- [x] #1 export.json routeStats equals the service's unfiltered, unlimited report; a session with no calls has routes []; formatVersion still 1
- [x] #2 export.md has ## Routes before ## Timeline with {id} routes, n<20 cells, retries in the attempts cell, | escaped
- [x] #3 HAR output unchanged (HarTest untouched)
- [x] #4 SessionExport.build takes the templates as a required parameter; both callers pass config.routeTemplates
- [x] #5 Exports paragraph in docs/decisions/exports.md; DAEMON.md §7 export table mentions Routes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR A, uncommitted. SessionExportDocument.routeStats (no default; formatVersion 1); SessionExport.build(templates) required, both callers pass config.routeTemplates; Markdown ## Routes before ## Timeline; HAR untouched (HarTest unchanged, green). Reds: empty-list default on routes -> key missing; built from queryTransactions -> 1000 vs 2001. NOT in this PR: archiveSource answering /stats from archive.routeStats (web/, T4/PR B) and the archive probe's stats offline lines; exports.md paragraph worded for what PR A ships. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR A, 0a5a1c9), Verified. AC1: export.json routeStats.routes equals REST stats routes exactly, formatVersion 1; empty-session routes key is the engineer's red, covered by ExportTest in the 357-test daemon run. AC2: export.md has '## Routes' right before '## Timeline', /users/{id}, '/orders/{id} ... n<20 | n<20', /pay attempts '3 (2 retries)', route /p\|q escaped. AC3: /api/sessions/<id>/har byte-identical on base cc9dbce and head (64,818 bytes, 50 entries); HarTest untouched and green. AC4: SessionExport.build's required parameter is compile-checked and the whole daemon suite is green (357, 0 failed); QA did not separately read the two call sites. AC5: exports.md and DAEMON.md §7 present; export.html contains routeStats, its offline page deferred to KIN-77 (PR B). DoD2: the reds (build from queryTransactions 'expected <2001> but was <1000>', routes default dropped) are the engineer's record, not repeated by QA. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
