---
id: KIN-35
title: 'Signals bug: two more ways the whole-session copies go stale'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
updated_date: '2026-10-18 22:45'
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
- [x] #1 The call cache's failure fallback does not mark the session, same as KIN-27 AC3
- [x] #2 forgetWholeSession bumps callLoad and signalLoad, and afterSessionChange uses it
- [x] #3 Each is red then green as a static check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). S1: syncAllTransactions' failure fallback keeps the filtered rows as a stand-in and sets allSessionId = null (same as syncAllSignals, KIN-27 AC3). S4: forgetWholeSession() also bumps state.callLoad and state.signalLoad, so a whole-session read sent before the gap is overtaken and drops its answer; afterSessionChange's else branch calls forgetWholeSession() instead of hand-clearing (allSessionId was left set). Static checks, scripts/lib/session-state-checks.js (four new lines under '--- session state ---'; the stub routes now snapshot their rows when the request arrives, as Doug's s4 harness did, or a held read would see rows that arrived later). RED at 5c0fa40 (= e1711a3 web code), all four: 'whole calls read failure  : NO - the filtered t2 was cached as the session, and the next load held t2'; 'forget drops calls read   : NO - the next load held t1,t2, without t3, which arrived in the gap'; 'forget drops signals read : NO - the next load held c1,s1, without g1, which arrived in the gap'; 'session change forgets    : NO - allSessionId S, allSignalsSessionId null, callLoad 4 (was 4), signalLoad 7 (was 7)'; 37 checks, 4 failed. Each bump removed alone: without state.callLoad++ -> 'forget drops calls read: NO' and 'session change forgets: NO ... callLoad 4 (was 4)'; without state.signalLoad++ -> 'forget drops signals read: NO' and 'session change forgets: NO ... signalLoad 7 (was 7)'. GREEN: 37 checks, 0 failed. Live probe 178 passed, archive 26 passed, counts unchanged (ci.yml untouched). web-ui.md's stale-copies paragraph names the new checks.

QA (third verify panel, head a86dde2): AC1-3 Verified. Not closed: the item closes together.
<!-- SECTION:NOTES:END -->
