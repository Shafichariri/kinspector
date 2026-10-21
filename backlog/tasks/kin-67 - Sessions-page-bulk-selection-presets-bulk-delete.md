---
id: KIN-67
title: 'Sessions page: bulk selection, presets, bulk delete'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:03'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-63
  - KIN-66
ordinal: 67000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 session presets, sessions bulk, row delete, deletes disabled, clear all arms green
- [ ] #2 Recording sessions never selectable
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Sessions page in index.html (#page-sessions) and js/90-sessions.js: table with checkboxes, select all, sessionPresets (pure; keep last N / older than D days; never a recording session — no endedAt or on /api/recording), delete selected (n) through confirmThen and one POST /api/sessions/delete via controlFetch, per-item results under #sessions-status, row open (navigate to traffic with ?session=) and row ✕ (single DELETE route, as before). Every delete path ends in afterSessionChange.
Lines: session presets (static), sessions bulk, row delete (was header delete), deleteArms (moved from the top bar), deletes disabled, clear all arms (now stubbed too). The bulk probe's stub catches every session-deleting route, so a page that broke the batch into single DELETEs is counted, never sent.
<!-- SECTION:NOTES:END -->
