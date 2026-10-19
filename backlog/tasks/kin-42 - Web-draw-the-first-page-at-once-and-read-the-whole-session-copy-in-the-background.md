---
id: KIN-42
title: >-
  Web: draw the first page at once and read the whole-session copy in the
  background
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-19 05:04'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-40
type: bug
ordinal: 42000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the fourth verify panel (head 8082e02), blocking. Regression from KIN-34, which reads the whole-session call list in full even with no filter. Measured on 160,016 calls in Chrome: 25.6 s of 'waiting for an app with inspector-stream to connect' under a header saying '1000 of 160016 calls', dot on 'connecting…', no tab bar, filter or axis; e1711a3 drew in under 3 s. Cause: 15-session-data.js:116-129 waits for syncAllTransactions before drawing, and 99-init.js:129-132 waits for all of that before connectLive(). The maintainer chose (2026-10-19) to fix this properly now rather than narrow KIN-34 back.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The list, tab bar, timeline, axis and counts draw from the first page as soon as it lands: rows within 2 s on the 160k session in headless Chrome; the live socket connects then, and the dot shows the socket's state, not the load's
- [ ] #2 'waiting for an app' and every 'nothing recorded' or 'no calls' text never appear while the whole-session copy is still being read; in that time empty states and filter notes say the session is still being read (with a count where known)
- [ ] #3 When the copy lands, everything that depends on it redraws (endpoint chips, repeat marks, path counts and 'calls not loaded' markers, axis and notes) without moving the scroll position
- [ ] #4 A live call or signal arriving while the whole-session read is in progress is buffered and merged by id when the read lands; this closes the mid-read window recorded as open in web-ui.md (scratchpad c3/midread.js), which is removed from the open list
- [ ] #5 A newer load, a session switch or a forget during the background read cancels it (load counters)
- [ ] #6 Checks: a jsdom or static check for the in-progress empty-state text, the post-read redraw and the mid-read merge, each red at 8082e02; the Chrome timing on 160k recorded as a documented check
- [ ] #7 web-ui.md describes the progressive read with no completeness claims
- [ ] #8 No regression in KIN-24..40's checks or the panels' Chrome measurements (scope bar on top, first row visible, station landing, run toggle, huge-session draw)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
- [ ] #4 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #5 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #6 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
