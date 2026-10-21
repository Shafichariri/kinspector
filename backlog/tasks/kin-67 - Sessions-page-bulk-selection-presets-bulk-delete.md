---
id: KIN-67
title: 'Sessions page: bulk selection, presets, bulk delete'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
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
- [x] #1 session presets, sessions bulk, row delete, deletes disabled, clear all arms green
- [x] #2 Recording sessions never selectable
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Sessions page in index.html (#page-sessions) and js/90-sessions.js: table with checkboxes, select all, sessionPresets (pure; keep last N / older than D days; never a recording session — no endedAt or on /api/recording), delete selected (n) through confirmThen and one POST /api/sessions/delete via controlFetch, per-item results under #sessions-status, row open (navigate to traffic with ?session=) and row ✕ (single DELETE route, as before). Every delete path ends in afterSessionChange.
Lines: session presets (static), sessions bulk, row delete (was header delete), deleteArms (moved from the top bar), deletes disabled, clear all arms (now stubbed too). The bulk probe's stub catches every session-deleting route, so a page that broke the batch into single DELETEs is counted, never sent.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR B, a7e3946, qa/web-foundation-shell-report.md), Chrome with two ended sessions and one recording. AC1: session presets, sessions bulk, row delete, deletes disabled and clear all arms pass; first click on 'delete selected (2)' sends no request and arms 'delete 2? permanent', the second sends one POST /api/sessions/delete with X-Inspector-Control 1 and {ids:[del2,del1]}; status 'deleted 2 of 2 - freed 1.0 KB' with a line per id. AC2: the recording row's checkbox is disabled, 'keep last 0' ticks the two ended rows and not the recording one; only its folder remains on disk.
<!-- SECTION:FINAL_SUMMARY:END -->
