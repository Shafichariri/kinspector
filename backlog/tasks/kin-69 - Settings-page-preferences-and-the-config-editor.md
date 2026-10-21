---
id: KIN-69
title: 'Settings page: preferences and the config editor'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:03'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-65
  - KIN-66
ordinal: 69000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 settings config green
- [ ] #2 Port read-only; flagged keys disabled with the flag named; says changes apply when the daemon restarts
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Settings page (#page-settings, js/80-settings.js): the two browser preferences unchanged, and the config.json editor over GET/PUT /api/config: each key's value with what the daemon runs on beside it, port read-only, a flagged key disabled with the flag named, signal caps per tag (blank = no cap) plus a row to add a tag; save sends only what changed, once, through controlFetch; a refusal is shown beside the form; "applies when the daemon restarts" with a link to Processes.
Lines: settings config; limit 3 caps to / limit 0 hides / limit restored to / limit summary (moved, now driven on the Settings page).
<!-- SECTION:NOTES:END -->
