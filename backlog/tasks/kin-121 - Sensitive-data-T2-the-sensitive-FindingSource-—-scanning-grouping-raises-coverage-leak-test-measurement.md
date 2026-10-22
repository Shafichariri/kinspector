---
id: KIN-121
title: >-
  Sensitive data T2: the sensitive FindingSource — scanning, grouping, raises,
  coverage, leak test, measurement
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 11:32'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-120
  - KIN-113
  - KIN-112
  - KIN-127
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
Ready for QA once its PR merges (unblocked by KIN-127, the JsonPaths carve-out). Files: service/SensitiveSource.kt (new), archive/FindingSettings.kt (FindingRuleCatalog.SENSITIVE_RULES appended to ALL; FindingRuleInfo.defaultEnabled so email and phone ship off), service/Findings.kt (FindingSession.enabled reads defaultEnabled), service/FindingsEngine.kt (one line in FindingSources.all; the drop and enabled checks read source.rules, so a custom rule's findings are kept), http/ConfigRoutes.kt (effective enabled from defaultEnabled), service/SensitiveValues.kt (xapikey is a secret name, decided 2026-10-22), mcp-tools-list.json (category enum gains sensitive), DAEMON.md (§6 findings.sensitive, §7 rule table), daemon-and-mcp.md (three paragraphs), inspector-daemon/AGENTS.md. Tests: SensitiveSourceTest (19), SensitiveLeakTest, SensitiveParityTest; the payment tests that pinned a one-family catalogue now name both. DEVIATION, needed by the masked-only invariant: a route segment that matches a value rule is written • in the finding's route (a JWT holds dots and an IBAN has no lowercase, so the route heuristic leaves both literal and the route label would print them), and the source asks RouteTemplates.keyOf directly rather than FindingSession.routeOf, whose memo is keyed by the raw path and so kept a URL card in the cache. FOR FINDINGS: the payment source still uses that memo; its accumulator holds raw paths. Red proofs (each run, seen failing, restored): raise applied to response bodies -> 'a response is never raised for its host' expected <medium> but was <high>; dedupe removed (key per rule) -> fold count expected <6> but was <8>, accumulator expected <3> but was <4>, and the dedupe test; engine checking ids against FindingRuleCatalog.ALL -> the custom rule's count expected 1 but was 0; row written before its body in SessionWriter.append -> 'the row was written before its body'; fold through queryTransactions -> SensitiveParityTest expected <1203> but was <1000>; SensitiveLeakTest: hint returning the value (accumulator: the card and its windows), a note carrying the value (REST: the card), key masking removed (REST: the JWT key's windows), route masking removed (REST: NL91ABNA0417164300), session.routeOf memo (accumulator: the URL card). Measurement (one-off test, deleted): 10,000 POSTs with ~4 KB JSON bodies each side, 83 MB on disk, cold category=sensitive read 2706 ms (budget 5 s), after 100 appended rows 41 ms (budget 100 ms), heap delta of the cache entry ~3.7 MiB (~375 B per row, both sources); 160,000-row huge-shaped session (no body files) cold 316 ms (budget 3 s), 160000 bodies counted not captured. Memory only; no disk cache needed. — bob
<!-- SECTION:NOTES:END -->
