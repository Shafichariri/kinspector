---
id: KIN-173
title: >-
  CI: the web job's WebSocket-check comment says no other step opens a
  WebSocket; measure-web-ui.js --only site does
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-30 14:05'
updated_date: '2026-10-30 14:27'
labels:
  - 'item:producer-react-native'
dependencies: []
type: bug
ordinal: 173000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
.github/workflows/ci.yml:132-133 claims no other step opens a WebSocket. scripts/lib/measure-cdp.js:88,118 opens Node's global WebSocket, reached by 'measure-web-ui.js --only site' through launchChrome/onPage. The sentence 'Its live round trip uses Node's' refers to test/live-roundtrip.js, which does not exist until KIN-166. The KIN-162 ticket's design paragraph repeats the first claim and needs the same correction.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The ci.yml comment states only what is true today
- [x] #2 The round-trip sentence is removed or deferred to KIN-166
- [x] #3 The KIN-162 design paragraph no longer repeats the false claim
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
ci.yml comment above 'Node has a global WebSocket' now names both users (the producer, and measure-web-ui.js --only site via scripts/lib/measure-cdp.js); the live round-trip sentence is dropped (KIN-166). AC #3 (KIN-162 design paragraph) left to the architect, per instruction.

QA round 2: comment now ends 'rather than inside the `site` measurement' (the producer's unit tests use fake-ws.js and pass without a global WebSocket).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
#1-3 Verified. The CI comment is true now ('rather than inside the site measurement'); the round-trip sentence is deferred to KIN-166; the KIN-162 ticket text is corrected. A replies-bypass-the-gate test was added and proven red. Name scan: no match for 8 terms (QA round 1); rerun before commit.
<!-- SECTION:FINAL_SUMMARY:END -->
