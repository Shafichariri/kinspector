---
id: KIN-27
title: >-
  Signals bug: under a filter the whole-session signal copy goes stale after
  pause-and-resume or a reconnect
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 27000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Doug) on KIN-24 AC4, head 5f6b610. Blocking. syncAllSignals (web/js/15-session-data.js:149) reuses its cached copy whenever the session matches. Neither setLiveTail(on) (25-traffic-list.js:409) nor the wasAway branch of ws.onopen (05-network.js:224) clears it. Repro: load the fixture and filter path:/v3; setLiveTail(false); append two screen signals and one signal with a new tag to signals.jsonl; setLiveTail(true). The path map and tabs miss them until the filter is cleared. Base showed them, because it fetched tag:screen on every load. Harnesses: $TMPDIR/doug-sig/harness/stale.js, and stale.js under the scratchpad's h/.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Both setLiveTail(on) and the wasAway branch of ws.onopen clear allSignalsSessionId and allSessionId; the latter also fixes the older identical staleness of the endpoint chips
- [x] #2 A probe or static check reproduces the scenario: red with either clear removed, green with both in place
- [x] #3 The syncAllSignals failure fallback no longer caches the filtered set as the whole session
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 6e72337. forgetWholeSession() (15-session-data.js) clears allSessionId and allSignalsSessionId; called by setLiveTail(on) before loadTransactions and by the wasAway branch of ws.onopen. syncAllSignals' failure fallback keeps the filtered rows as a stand-in but sets allSignalsSessionId = null. Probe: two live lines in render-web-ui.js, bodies in new scripts/lib/filter-probes.js (probeWholeSessionRefresh): under tag:screen it rewrites the daemon's unfiltered answers from the moment the gap opens (one signal under a new tag -> a tab; one more call to the busiest endpoint -> its chip +1); nothing written to the archive; stop request stubbed. RED with the setLiveTail clear removed: 'resume refreshes   : NO - tab for 'probe-resumed' false, /stock counts 4 where 5 was due, under tag:screen', 170 passed, exit 1. RED with the onopen clear removed: 'reconnect refreshes: NO - tab for 'probe-reconnected' false, /stock counts 4 where 5 was due', 170 passed, exit 1. GREEN: both yes, 171 passed. AC3 static: 'whole read failure' — RED with allSignalsSessionId = sessionId in the catch: 'NO - the filtered c1 was cached as the session, and the next load held c1'; GREEN 'yes (the failed read left c1 unmarked, and the next load read c1,s1)'. ci.yml UI 169->171; budgets render-web-ui.js 2729->2735 by hand. Not done (no widening): syncAllTransactions' failure fallback still marks its stand-in as the session — same shape, outside AC3; worth a ticket. Ready for QA.

QA (second verify panel, head e1711a3): AC1-3 Verified. Not closed; the item closes together.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at e1711a3 (second panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
