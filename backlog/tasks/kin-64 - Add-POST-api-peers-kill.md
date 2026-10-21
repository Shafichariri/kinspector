---
id: KIN-64
title: Add POST /api/peers/kill
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
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
- [x] #1 Per-pid results in request order through PeerRegistry.kill
- [x] #2 Self refused with the registry's reason; a live non-inspector process refused and left running
- [x] #3 400/415; registered inside controlGated
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (uncommitted). One KillOutcome.toResult mapping shared by single and bulk route. Red proofs: NoSuchProcess->signalled fails PeerBulkKillTest order case and single-route 404; identity re-check bypassed in ProcessHandlePeerRegistry -> 'the bulk route signalled a process the registry should have refused'; bulk route not calling kill -> 3 cases fail. WrongMethodStatusTest: a transparent gated node of its own for POST /api/peers/kill turns PUT/POST/DELETE /api/peers into 404 (existing loop catches it).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR A, e9f7131, qa/web-foundation-shell-prA-report.md), live. AC1/AC2: {pids:[peer,sleep,self,999999,peer]} answers in order: peer signalled, sleep refused 'not an inspector process', self refused 'that is this daemon', 999999 noSuchProcess, repeat answered once; afterwards peer dead, sleep alive, daemon alive. AC3: [], ['1'], [0], [-5], [1.5], force:'yes', an extra key and 101 pids give 400; text/plain without the header is refused 403 by the gate; R1 (controlGated -> run in PeerRoutes) fails ControlHeaderCoverageTest 'POST /api/peers/kill -> 415 (no header)' and PeerRegistryTest.killing requires the control header.
<!-- SECTION:FINAL_SUMMARY:END -->
