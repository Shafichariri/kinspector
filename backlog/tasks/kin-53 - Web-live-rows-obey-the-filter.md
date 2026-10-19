---
id: KIN-53
title: 'Web: live rows obey the filter'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 15:16'
updated_date: '2026-10-19 17:20'
labels:
  - 'item:web-live-rows-filtered'
dependencies:
  - KIN-52
type: bug
ordinal: 53000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap: docs/ROADMAP.md section 3, Bugs found not fixed, entry 'Live rows ignore the filter until the next reload.' (item:web-live-rows-filtered). The design choice (daemon says whether the row matches, or a throttled re-read of the filtered set) is left to the engineer's plan, written into this ticket before building. The plan must say whether it changes the daemon's live WebSocket message shape, any :inspector-model type, or a recorded decision in docs/decisions/; any of those is a human gate, stop and ask before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 With a filter set, a live call or signal the filter does not match does not appear in the list, the timeline or a path visit's contents. One it does match appears without a reload. render-web-ui.js drives one live row of each kind and fails with the push-everything line put back.
- [x] #2 state.allTransactions and state.allSignals still receive every live row, so chips, repeat marks, tab counts and the path skeleton still describe the whole session.
- [x] #3 No filter is evaluated in web/js. The match comes from the daemon.
- [x] #4 With no filter set, a live row costs no request.
- [x] #5 If the re-read option is chosen, it is throttled, and the design states the most requests a burst of 100 live rows can cause.
- [x] #6 Pausing is unchanged: it still drops rows and re-reads on resume (docs/decisions/web-ui.md).
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, engineer; no architect note on this small track).

Design chosen: a throttled re-read of the filtered set, not a match flag on the live message.
Why:
- No daemon change at all. The match comes from the same GET routes a load reads, so it is the daemon's parser by construction, and web/js evaluates nothing.
- A match flag needs the daemon to know each page's filter. /api/live is a broadcast SharedFlow with no per-connection state and reads no inbound frames; the page would have to send its filter as a query parameter (reconnecting the socket on every filter edit, which tangles with the away/redial logic and loses rows in the gap) or as an inbound frame. Either adds an input surface to an unauthenticated WebSocket that any web page can open (daemon-and-mcp.md: the control header cannot guard /api/live; two boundary gaps there are open with the maintainer). It also changes the live message shape, which these tickets name as a human gate. And a flag evaluated against a filter the page has since changed still needs a read to settle.
- The re-read costs one request per cycle for calls, at most once a second, on a page that already reads these routes on every filter keystroke.

Behaviour:
- No filter (state.filter empty, which KIN-52 makes the one test): unchanged. The row is pushed into the lists and no request is made.
- Under a filter: the row goes into allTransactions/allSignals at once (admitLive, as today), screens and tabs as today, sessionTotal++ (the session gained a call whatever the filter). It is NOT pushed into state.transactions/state.signals and `matched` is not touched. The kind is marked owed and a re-read is scheduled.
- rereadLive (15-session-data.js): fires LIVE_REREAD_MS = 1000 after the first owed row; reads only the kinds owed: the filtered call page (1 request, limit LIST_CEILING) and/or the filtered signals (signalPages, ceil(S/1000)+1 requests, S = signals the filter matches; 2 for S under 1000). It replaces state.transactions, sessionTotal, matched and/or state.signals, then redraws list, counts, tabs, waterfall, path and timeline (follow: false, so a reader scrolled back is not moved).
- Throttle: one timer, never two re-reads in flight; rows arriving while the timer is armed or a read is in flight only set the owed flag, and a new timer is armed only after the read lands. Bound: a burst of 100 live rows arriving within one second causes at most 2 cycles (the first row arms the timer; rows after it fires owe one more): at most 2 requests if they are all calls, at most 2 x (1 + ceil(S/1000) + 1) if signals are among them, so 6 for a filter matching fewer than 1000 signals. In general 100 rows over T seconds cause at most min(100, ceil(T) + 1) cycles.
- Overtaking: the re-read reads filter and session once, and drops its answer when the session, the filter or live tail changed, or a newer load started (callLoad/signalLoad, which forgetWholeSession bumps on pause, resume, reconnect and session change). A load already in flight when a re-read is sent is older; each list/signal write carries a ticket, and an older answer landing after a newer one is not written (the existing "older load dropped" rule, extended).
- Pausing unchanged: a paused page drops rows before any of this; a re-read that comes due while paused does nothing (liveTail is checked), and resume re-reads as before.

Files: web/js/00-core.js (state), 05-network.js (onmessage), 15-session-data.js (rereadLive, tickets in loadTransactions/loadSignals), 25-traffic-list.js (KIN-52). Probes in scripts/lib/live-filter-probes.js (new), wired with 3 lines in render-web-ui.js (budget 2755 -> 2758 by hand with the reason), typeFilter/press exported from filter-probes.js. CI UI_EXPECT_CHECKS rises by the new lines. A paragraph in docs/decisions/web-ui.md records the design (an addition; nothing recorded is rewritten).

Gate judgment: none applies. No :inspector-model type or wire protocol (the daemon is not touched), no api/ golden, nothing near the loopback/control boundary, no recorded decision contradicted (exports.md "disabled, not reimplemented" is what this keeps; web-ui.md pausing is unchanged). Size S: about 80 lines of web code, a probe file of about 200 lines.

Red before the change (render-web-ui.js on the CI fixture): live rows list/timeline/path NO (refused call and signal drawn), live rows whole NO, live paused no read NO.

Built (bob), not committed. Two departures from the plan above:
1. rereadLive does NOT compare callLoad/signalLoad. Any load that could overtake a re-read takes a newer ticket (readSent), so the ticket rule already drops the re-read; a check that could never fail alone was removed rather than kept. Drops happen on: the ticket rule, or the session, filter or live tail changing (stands()).
2. "live rows whole" was not red before the change (the whole-session copies already took live rows); it guards criterion 2 and was proven red by removing admitLive's push instead.

Changed: web/js/00-core.js (state.reread, readSent, readWritten), 05-network.js (onmessage: under a filter oweReread instead of push; matched only moves with no filter), 15-session-data.js (LIVE_REREAD_MS, oweReread, rereadLive; tickets in loadTransactions and loadSignals). Probes: scripts/lib/live-filter-probes.js (new; wired by 3 lines in render-web-ui.js, budget 2755 -> 2758 in structure/budgets.txt), scripts/lib/live-reread-checks.js (new; wired into check-web-static.js), typeFilter/press exported from filter-probes.js, the new state fields added to the stub states in follow-checks.js and session-state-checks.js. ci.yml UI_EXPECT_CHECKS 187 -> 197. docs/decisions/web-ui.md: one new paragraph ("Under a filter a live row waits for the daemon...").

Each view (list, timeline, path) is opened before its rows arrive and judged at arrival, before any re-read can land, and again after the daemon's answer. Green: `live rows list/timeline/path: yes (the matching call [and signal] drawn after 1 filtered call read, the refused rows never, no load)`, `live rows whole: yes (/stock counts 6, cache 5, state 8 ...)`, `live free no filter: yes`, `live paused no read: yes`.
Red proofs, each restored and green after:
- push-everything put back beside the re-read: list, timeline, path NO ("... drawn at arrival"), live paused no read NO.
- the handler as on HEAD (push only): list, timeline, path NO, paused NO.
- admitLive's push into the marked copy removed: `live rows whole: NO - /stock 4 -> 4 (6 due), cache 4 -> 4, state 7 -> 7`.
- liveTail taken out of rereadLive: `live paused no read: NO - 1 reads while paused, drawn while paused: true`.
- static (check-web-static.js): ticket rule out of loadTransactions -> `reread beats older load: NO`; out of loadSignals -> `reread beats older signals: NO`; out of rereadLive -> `older reread dropped: NO`; filter out of stands() -> `reread filter changed: NO - the list held c3,c1 under path:/a`; busy guard out of oweReread -> `reread burst of 100: NO - ... 2 out at once`; refused-filter guard out -> `reread refused filter: NO`.
Not a guard: `owed.timer === null` in oweReread only avoids stacking timers that would find nothing owed (rereadLive returns at once when no kind is owed); it has no observable effect and is not claimed as proven.
Burst bound (criterion 5), as checked by `reread burst of 100`: 50 calls and 50 signals, half before the timer fires and half while its read is held past the interval: 6 requests, in two re-reads, never two out at once.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), rounds 1 and 2. AC1: 'live rows list/timeline/path: yes'; Chrome, filter status>=500 | tag:state, header '2 of 27 calls' at arrival, only matching rows drawn; push-everything put back -> list, timeline, path NO (191/6). AC2: 'live rows whole: yes'; allTx 27 while the list holds 2; admitLive push removed -> NO. AC3: verified by reading the diff, the JS only tests typed.trim() and compares filter strings; matches come from the daemon routes. AC4: 'live free no filter: yes'; 0 list reads 2 s after a live call. AC5: static 'reread burst of 100: yes (6 requests, one out at a time)'; Chrome 100 rows in 1003 ms -> 3 list reads, with reads held 1.5 s -> 6 reads in two cycles, one at a time; busy guard removed -> '2 out at once'. AC6: 'live paused no read: yes'; liveTail check removed -> NO. Final runs: render-web-ui 197 passed 0 failed, check-web-static 87/0, archive 37/0, :repo-checks 35/0, :inspector-daemon:test 268/0. Design chosen: a throttled re-read under a filter, 1 s after the first row, never two in flight; 100 rows in a second -> at most 2 re-reads, 6 requests with signals under 1000 matches; no daemon or wire change. Burst bound counts re-read requests only; each live signal also reads /current, which predates this item.
<!-- SECTION:FINAL_SUMMARY:END -->
