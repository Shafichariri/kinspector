---
id: KIN-64
title: Add POST /api/peers/kill
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 04:53'
labels:
  - 'item:web-foundation-shell'
dependencies: []
ordinal: 64000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Per-pid results in request order through PeerRegistry.kill
- [ ] #2 Self refused with the registry's reason; a live non-inspector process refused and left running
- [ ] #3 400/415; registered inside controlGated
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (uncommitted). One KillOutcome.toResult mapping shared by single and bulk route. Red proofs: NoSuchProcess->signalled fails PeerBulkKillTest order case and single-route 404; identity re-check bypassed in ProcessHandlePeerRegistry -> 'the bulk route signalled a process the registry should have refused'; bulk route not calling kill -> 3 cases fail. WrongMethodStatusTest: a transparent gated node of its own for POST /api/peers/kill turns PUT/POST/DELETE /api/peers into 404 (existing loop catches it).
<!-- SECTION:NOTES:END -->
