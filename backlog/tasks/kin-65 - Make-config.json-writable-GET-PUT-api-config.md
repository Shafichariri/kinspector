---
id: KIN-65
title: 'Make config.json writable: GET/PUT /api/config'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 04:53'
labels:
  - 'item:web-foundation-shell'
dependencies: []
ordinal: 65000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Merge of known keys; unknown file keys kept; unknown request key 400 by name; invalid values 400
- [ ] #2 Malformed file 409 and left untouched; atomic rename
- [ ] #3 Flags reported and winning; nothing applied before restart
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (uncommitted). archive/ConfigFile (read/merge, lock, temp+ATOMIC_MOVE, symlink kept), DaemonConfig.flags, http/ConfigRoutes. Values are written with a hand indenter because kotlinx prettyPrint re-encodes numbers (2.50 -> 2.5), which broke 'kept as it was'. Red: in-place write -> file-key assertion fails; unknown-key preservation dropped -> fails. Note: PUT /api/config under a transparent gated node of its own stays 405 (nothing below /api/config), so that break is not red there; a gated path node of its own (route{handle}) is red in the new WrongMethodStatusTest case (403 for POST/DELETE/PATCH) and in ReadRoutesOpenTest.
<!-- SECTION:NOTES:END -->
