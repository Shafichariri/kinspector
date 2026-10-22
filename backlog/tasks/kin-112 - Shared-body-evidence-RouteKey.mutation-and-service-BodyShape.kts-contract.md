---
id: KIN-112
title: 'Shared body evidence: RouteKey.mutation and service/BodyShape.kt''s contract'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-111
type: feature
ordinal: 112000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). Carved out of item:session-diff's T1 on 2026-10-22; built to the names and signatures of the session-diff design §10, which are a contract: diff T1 later adds walk, group, foldIdKeys and isAtOrUnder to the same JsonPaths object and renames nothing built here, and item:sensitive-data-detection's T2 calls it. Daemon archive/ and service/ only; no public API, no golden file, no change to what capture records. Lands after findings T1 and before findings T2, inside findings PR A; findings T3 is the first ticket that reads it.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 archive/RouteTemplates.kt has RouteKey.mutation, a computed property and the one definition of mutation (POST, PUT, PATCH, DELETE); equality and every constructor call are unchanged, and the route-stats tests pass unedited
- [x] #2 service/BodyShape.kt has BodySide (REQ("req"), RES("res")), BodyState with all seven states (complete, empty, truncated, omitted, missing, notJson, unparsed), BodyEvidence.state(txn, side) from the row only, BodyEvidence.read(repository, dir, txn, side) (bytes only when the body is whole, any content type) and JsonPaths.redactedPaths(txn, side): Set<String> reading both body:req:/body:res: and the older body:$.x
- [x] #3 A truncated body is truncated and never complete, and an omitted one is omitted, each shown by a test proven red with the state check removed
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA. Merged in PR #151 (merge 428745a). RouteKey.mutation (computed, outside the constructor) in archive/RouteTemplates.kt; service/BodyShape.kt: BodySide, BodyState (7 states, key words), BodyEvidence.state (row only, never UNPARSED; a ref whose file is gone is read() == null and callers name it missing), BodyEvidence.read(repository, dir, txn, side) (empty array for EMPTY, bytes for COMPLETE/NOT_JSON, null otherwise), JsonPaths.redactedPaths (both forms). BodyShapeTest. Red: truncated check removed -> 'a truncated body is truncated and never read' fails (expected TRUNCATED but was COMPLETE); omitted check removed -> 'an omitted body is omitted for each reason' fails (expected OMITTED but was MISSING); DELETE dropped from the mutation set fails the mutation test. For diff T1: UNPARSED is assigned by the diff's own parse step; state() never returns it. The findings T2/T3 PR adds an overload read(state, file) for a fold that keeps only the row's state; read(repository, dir, txn, side) calls it. — bob
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #151 merge 428745a; item stays open. AC1: 'val mutation: Boolean get() = method in MUTATION_METHODS' sits outside the constructor, equality unchanged, route-stats tests unedited and green. AC2: BodySide, all seven BodyState keys, BodyEvidence.state, BodyEvidence.read and JsonPaths.redactedPaths(txn, side) match session-diff design section 10 exactly for what this ticket builds; one documented addition, an overload read(state, file); walk/group/foldIdKeys/isAtOrUnder belong to diff T1, not a gap here. AC3 re-run: removing 'truncated -> BodyState.TRUNCATED' fails BodyShapeTest (expected TRUNCATED but was COMPLETE) and PaymentRulesTest R2 'one unknown' (expected 0 but was 1); the omitted proof is the builder's and was not repeated. Verdict: close yes, no blocking findings.
<!-- SECTION:FINAL_SUMMARY:END -->
