---
id: KIN-35
title: 'Signals bug: two more ways the whole-session copies go stale'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 35000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Doug), head e1711a3. (S1) The syncAllTransactions failure fallback (15-session-data.js:271-284) still marks the filtered stand-in as the whole session, which now produces the false 'no calls were recorded' text; KIN-27 AC3 fixed only the signal copy. (S4) forgetWholeSession does not invalidate loads already in flight, so a load from before the reconnect gap re-marks the cache with old rows. afterSessionChange's else branch (90-sessions.js:96-104) hand-clears and leaves allSessionId set. Harness: $TMPDIR/doug2/h/txfail.js and Doug's in-flight case.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The call cache's failure fallback does not mark the session, same as KIN-27 AC3
- [ ] #2 forgetWholeSession bumps callLoad and signalLoad, and afterSessionChange uses it
- [ ] #3 Each is red then green as a static check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
