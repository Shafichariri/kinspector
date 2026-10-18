---
id: KIN-30
title: 'Signals: the release line says loaded rows'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
updated_date: '2026-10-18 21:02'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: chore
ordinal: 30000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel on KIN-24/KIN-25, head 5f6b610. Small. The release line owed for this item overstates or understates what shipped.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The line in the roadmap's owed release lines (with the others) reads: 'Web UI: a filter now applies to the signals loaded on the timeline and path views (rows arriving live follow the filter once item:web-live-rows-filtered lands), and a session with more than 1000 signals shows all of them.'
- [ ] #2 KIN-25's one-line release note is brought into agreement with it
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 9b8d3de. docs/ROADMAP.md, phase A's pending release-notes entry (the owed lines, after the Markdown-export line): item:signals-query-one-rule owes a fourth line, worded exactly as AC1, with why 'loaded' is load-bearing (05-network.js pushes a live signal into state.signals whatever the filter). The item's own 'gets a release-notes line when it ships' now points there. KIN-25's notes: the 'Release notes line' sentence replaced through backlog task edit --notes with the same wording, nothing else in those notes changed (diff: one line plus updated_date). No check to prove: prose only. Ready for QA.
<!-- SECTION:NOTES:END -->
