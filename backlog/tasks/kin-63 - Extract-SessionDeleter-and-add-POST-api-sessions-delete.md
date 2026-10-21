---
id: KIN-63
title: Extract SessionDeleter and add POST /api/sessions/delete
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 04:53'
labels:
  - 'item:web-foundation-shell'
dependencies: []
ordinal: 63000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Single delete and clear answer exactly as before (SessionDeletionTest unedited and green)
- [ ] #2 Bulk answers per id in order with the single route's messages; latest, active, unknown and unsafe ids refused per item
- [ ] #3 400 on a malformed body, 415 on non-JSON; registered inside controlGated
- [ ] #4 Red proof: dropping the active check fails single, clear and bulk tests
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (uncommitted, working tree). service/SessionDeleter takes (repository, sessionsDir, activeIds): sessionsDir added because SessionRepository keeps its config private and the 500 message has always named config.sessionsDir. Bulk DTOs and body parsing live in http/SessionRoutes.kt; JSON body reader (415/400) is receiveJsonObject in http/Responses.kt. Red proof: active check dropped -> SessionBulkDeleteTest refused case, SessionDeletionTest single (409->200) and clear (kept missing) all fail. Bulk route outside controlGated -> ControlHeaderCoverageTest red (415 instead of 403). SessionDeletionTest unedited.
<!-- SECTION:NOTES:END -->
