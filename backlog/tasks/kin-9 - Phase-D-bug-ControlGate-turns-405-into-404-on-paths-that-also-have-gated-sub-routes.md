---
id: KIN-9
title: >-
  Phase D bug: ControlGate turns 405 into 404 on paths that also have gated
  sub-routes
status: To Do
assignee: []
created_date: '2026-10-18 12:20'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-6
type: bug
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA on KIN-8 #2 (route matrix, base 1db168b vs head ed00b73): 45 of 855 rows differ, all one kind. Since C9 (5d12bde), POST/PUT/DELETE on /api/sessions, /api/server, /api/peers and /api/mcp return 404 where base returns 405. /api/recording, which has no gated sub-route, stays 405. Bisected: e88af44 equals base, 5d12bde equals head. Likely cause (unconfirmed): the transparent gate node puts <P>/... routes in a sibling subtree, so Ktor reports the path failure from that subtree instead of the method failure on <P>. Contradicts KIN-6's definition of no route changes (method, path, status and body unchanged).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 PUT, POST and DELETE on /api/sessions, /api/server, /api/peers and /api/mcp return 405 at head, as at base
- [ ] #2 A new ControlGateTest case: a wrong method (e.g. PUT) on the open GET path beside a gated sub-route returns 405; proven red against the current gate and green after the fix
- [ ] #3 ControlHeaderCoverageTest, ReadRoutesOpenTest and ControlGateTest cases 1 to 8 still pass unchanged
- [ ] #4 The full base-vs-head route matrix (KIN-8 #2) is identical: 0 differing rows
- [ ] #5 If no gate shape can preserve 405 without changing behaviour elsewhere, the gate commit is dropped per the design fallback and the eight header checks are restored, and that is reported
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
