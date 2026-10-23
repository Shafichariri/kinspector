---
id: KIN-157
title: A waterfall group past the clamp undercounts with no caveat
status: To Do
assignee: []
created_date: '2026-10-23 11:02'
labels:
  - 'item:web-waterfall-group-says-unloaded'
dependencies: []
ordinal: 157000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
To be placed as order row 32a by the product owner at the next close-out. QA's follow-up to row 32 (KIN-151): on a long session a non-stub waterfall group whose visit began before the oldest held call counted only the loaded calls and said nothing; the path says older calls not loaded there. Reuses row 32's markUnloaded/oldestHeld rule; claims no count of the unloaded calls.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A waterfall group whose visit began before the oldest held call says its count is of the loaded calls and that older calls are not loaded, with no count of those
- [ ] #2 A group wholly within the held calls reads as before
- [ ] #3 While the whole-session copy is read it says what row 32's stubs say
- [ ] #4 A probe line proven red (static over the 3016-call stub, and the Chrome waterfall groups reading)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
