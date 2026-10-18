---
id: KIN-9
title: >-
  Phase D bug: ControlGate turns 405 into 404 on paths that also have gated
  sub-routes
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 12:20'
updated_date: '2026-10-18 12:40'
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
- [x] #1 PUT, POST and DELETE on /api/sessions, /api/server, /api/peers and /api/mcp return 405 at head, as at base
- [x] #2 A new ControlGateTest case: a wrong method (e.g. PUT) on the open GET path beside a gated sub-route returns 405; proven red against the current gate and green after the fix
- [x] #3 ControlHeaderCoverageTest, ReadRoutesOpenTest and ControlGateTest cases 1 to 8 still pass unchanged
- [x] #4 The full base-vs-head route matrix (KIN-8 #2) is identical: 0 differing rows
- [x] #5 If no gate shape can preserve 405 without changing behaviour elsewhere, the gate commit is dropped per the design fallback and the eight header checks are restored, and that is reported
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Fixed in 753d9cf. Cause (Ktor 3.5.0 bytecode): RoutingResolveContext.updateFailedEvaluation prefers a deeper failure when the trail is only transparent/constant selectors; the transparent gate node made the gated subtree's FailedPath (0.0) one level deeper than the open GET's FailedMethod (0.02), so it won. Order-dependent: /api existed before the first controlGated. Fix: controlGated adds no node; it installs ControlGate on each handler node its block adds (identity diff), install-once kept. New: ControlGateTest case 9 (red before: 'PUT /p expected 405 but was 404'), case 4b (keeps the DuplicatePluginException guard reachable), WrongMethodStatusTest (red before on exactly the 12 QA rows). Route dump identical to pre-gate (32, no gate node). Live serve --no-usb: 15/15 rows 405. Daemon 247/0, repo-checks 35/0. Cases 1-8, ControlHeaderCoverageTest, ReadRoutesOpenTest unchanged. Gate kept, so the fallback was not needed. Proofs under KIN-9 in phase-d-proofs.md. Ready for QA.

Review follow-ups in c1f2a4a: controlGated returns Unit; KDoc warns that a node getting its first handler inside the block gates everything below it; the Ktor rule stated exactly (higher quality OR deeper, only while the trail is all constant/transparent) in ControlGate.kt and daemon-and-mcp.md; the reverse case (gated route first, open route on the same node) recorded, with the guards that catch it. Daemon 247/0, repo-checks 35/0.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA at 753d9cf: all 36 matrix rows are 405; case 9 red against the old gate, green after the fix; guards unchanged; route matrix identical. AC #5 (fallback) is n/a because the gate was kept; ticked as not triggered. DoD #3 (no client names) is NOT verified and left unticked: the term list is deliberately kept out of the repo, so it cannot be checked here by design; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
