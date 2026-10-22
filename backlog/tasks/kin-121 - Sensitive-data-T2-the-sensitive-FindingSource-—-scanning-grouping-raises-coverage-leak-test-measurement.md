---
id: KIN-121
title: >-
  Sensitive data T2: the sensitive FindingSource — scanning, grouping, raises,
  coverage, leak test, measurement
status: To Do
assignee: []
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 10:47'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-120
  - KIN-113
  - KIN-112
type: feature
ordinal: 121000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Also depends on item:session-diff T1 ('Body evidence, JSON key paths, SessionStamp, RouteKey.mutation, and the stats and Markdown extractions'), not filed yet: this ticket calls JsonPaths.walk, JsonPaths.group and JsonPaths.isAtOrUnder in service/BodyShape.kt, which that ticket adds under the names its design §10 fixes. KIN-112 carved out the part of it that exists (BodySide, BodyState, BodyEvidence, JsonPaths.redactedPaths). Files: service/SensitiveSource.kt, service/FindingsEngine.kt (one line in FindingSources.all), archive/FindingSettings.kt (FindingRuleCatalog.SENSITIVE), tests SensitiveSourceTest, SensitiveLeakTest.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Locations url, query, reqBody, resBody (and request headers and response set-cookie for credentials over http only); content types routed as decided; at most 1 MiB scanned; a truncated or unparseable body gets value rules only with the note 'truncated: path unknown'
- [ ] #2 redacted[] (both body forms, headers, query) and the placeholder are skipped through JsonPaths.isAtOrUnder over JsonPaths.redactedPaths; no second reader exists
- [ ] #3 One evidence per row, location and path, strongest claim first
- [ ] #4 One finding per rule, route, location and JsonPaths.group-ed path, built through session.finding; at most 5 evidence; txnIds, callIds and evidence in timeline order; txnCount; at most 1000 groups with groupLimitReached
- [ ] #5 SensitiveSource.rules lists the shipped rules and each custom rule; a custom rule's finding reaches the reply and the reply's rules lists every sensitive rule
- [ ] #6 The accumulator retains no NetworkTransaction; the heap per row is in the handover
- [ ] #7 coverage.sensitive carries rows, bodies, scanned, notCaptured, truncated, binary, overScanLimit, groupLimitReached, firstPartyHosts and a daemon-built line
- [ ] #8 Raises request-side only; an empty firstPartyHosts raises nothing; a findings.rules severity sets the base and the raises apply
- [ ] #9 An incremental fold equals a cold scan; SessionWriter.append writes bodies before the row
- [ ] #10 REST and MCP return the same ids in the same order on a 1203-row session with bodies
- [ ] #11 SensitiveLeakTest green over REST, MCP and the cache; red with the mask returning the value, a message interpolating it, and key masking removed
- [ ] #12 The measurement (10,000 calls with 4 KB bodies each side cold and after 100 appended rows; the 160k-row huge fixture cold) is in the handover
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
NOT STARTED, blocked (2026-10-22, bob): this ticket calls JsonPaths.walk, JsonPaths.group and JsonPaths.isAtOrUnder in service/BodyShape.kt, which item:session-diff's T1 adds (its design §10 fixes the names and signatures as a contract with this item). Session diff is not built and its tickets are not filed; main has only JsonPaths.redactedPaths, carved out as KIN-112. Building the walker here would be building another item's work (the KIN-34 trap), so it waits for the coordinator: either session diff T1 lands first, or the three functions are carved out of it into a ticket of their own as KIN-112 was. KIN-120 (T1) merged in #156; KIN-122 (T3, generic dismissals) was built ahead of this ticket because it needs nothing from it.
<!-- SECTION:NOTES:END -->
