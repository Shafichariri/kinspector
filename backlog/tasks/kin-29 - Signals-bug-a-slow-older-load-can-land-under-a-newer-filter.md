---
id: KIN-29
title: 'Signals bug: a slow older load can land under a newer filter'
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
ordinal: 29000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Charles) on KIN-24, head 5f6b610, with a vm harness (race.js under the scratchpad's h/). Should-fix. loadTransactions has no guard against an older call finishing last, and syncAllSignals re-reads state.filter after awaiting the pager. A slow unfiltered load landing after a tag:cache load leaves state.signals: c1,s1 under tag:cache.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The filter is read once per load and passed down to syncAllSignals
- [x] #2 A generation counter discards a load superseded by a newer one
- [x] #3 The harness scenario is red without the counter and green with it, as a static or probe check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 35ee0b8. loadTransactions reads state.filter once and passes it to syncAllTransactions(filter, isLatest), loadSignals(filter) and so syncAllSignals(filter, isLatest); loadSignals(filter = state.filter) when called alone (pull, browser). state.callLoad and state.signalLoad (00-core.js) count loads; each load drops its answer after every await once a newer one started. Separate counters because a pull reloads signals alone and must not abort a list load's render. Static checks (session-state-checks.js runs 15-session-data.js in a vm, the race.js scenario adapted): 'older signal load dropped' and 'older call load dropped'. RED signal counter off (isLatest = () => true): 'older signal load dropped : NO - signals c1,s1 under tag:cache, where c1 was due'. RED call counter off: 'older call load dropped   : NO - the list holds t1,t2 under path:/b, where t2 was due'. Against the pre-KIN-29 file both NO, 33 checks 2 failed. GREEN 33 checks, 0 failed. Live probe unchanged (171). NOTE FOR QA: the panel's race.js as written seeds state without signalLoad, so against this code ++undefined is NaN and every load drops itself; add signalLoad: 0 to its state before re-running it. Filter pass-down has no separate red: with the counters in place a re-read of state.filter is unobservable, because every filter change starts a new load. Ready for QA.

QA (second verify panel, head e1711a3): AC1-3 Verified. Not closed; the item closes together.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at e1711a3 (second panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
