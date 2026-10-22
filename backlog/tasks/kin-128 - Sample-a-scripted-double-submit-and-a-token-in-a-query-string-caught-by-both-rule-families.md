---
id: KIN-128
title: >-
  Sample: a scripted double submit and a token in a query string, caught by both
  rule families
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 12:18'
labels:
  - 'item:sample-demo-flow'
dependencies: []
type: feature
ordinal: 128000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §6 → 'A sample demo flow that both rule families catch' (order row 30a, lane S). The sample server gains a payment-shaped mutation route; the shared demo flow (sample/shared/kotlin) posts one body to it twice inside the duplicate window with no idempotency key, and makes one call with a fake JWT in its query string. Desktop autofire and a button run it; Android and iOS get the same button through the shared file. Surfaces: the sample app and its docs. No library API, no noop twin, no golden file, no rule or daemon change. Hosts are loopback only; values are a published test card number and a JWT whose signature is the words 'not a real signature'.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The sample server gains a payment-shaped mutation route, and the desktop sample's scripted run (-Dinspector.sample.autofire=true) sends one request body to it twice within the duplicate window with no idempotency key, and makes one call with a token in its query string.
- [ ] #2 On a fresh sample session with the daemon's default config, GET /api/sessions/{id}/findings and MCP list_findings each return at least one Duplicate mutation finding at high citing both calls, and at least one query-string token finding from detection whose evidence is masked: the token's value appears nowhere in the response.
- [ ] #3 The Findings page shows both on that session.
- [ ] #4 sample/README.md says how to run the flow and what each finding should show, in steps a newcomer can follow from a clean clone.
- [ ] #5 -Pinspector=off still builds the sample, and the release guard's check and self-test are unchanged.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
