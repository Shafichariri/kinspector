---
id: KIN-46
title: >-
  Web: a live row that arrives during the whole-session read is in the copy once
  when the read lands, for calls and signals
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 10:07'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies: []
modified_files:
  - inspector-daemon/src/main/resources/web/js/15-session-data.js
  - inspector-daemon/src/main/resources/web/js/05-network.js
  - inspector-daemon/src/main/resources/web/js/00-core.js
  - scripts/lib/session-state-checks.js
  - scripts/lib/follow-checks.js
  - docs/decisions/web-ui.md
type: feature
ordinal: 46000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Part of item:web-transactions-paged (docs/ROADMAP.md, "The web traffic list stops at 1000 calls and does not say so"), the first criterion folded in on 2026-10-19: a live row that arrives during the whole-session read is in the copy when the read lands, and is in it once, for both `state.allTransactions` and `state.allSignals`, with a committed check that fails when the mark-on-land rule is put back. Design approved by the maintainer on 2026-10-19. KIN-42 (the background read) is not part of this item: the whole-session read stays awaited and uncapped, as today.

Read first: docs/decisions/web-ui.md, the paragraphs "The web UI keeps an unfiltered copy of the session, and two features depend on it." and "The two cached copies have gone stale in the ways below".

The defect. A live row joins a copy only once the copy is marked as the session's (`state.allSessionId`, `state.allSignalsSessionId`), and the mark is set when the read lands. So a row that arrives between the read's first page and its last is in neither the read nor the copy. KIN-34's paging widened that window from one request to every page. It is open on any load made while the socket is listening: a filter typed, a resume, a reconnect, a pull.

The change.
- New `admitLive(kind, row)` in `inspector-daemon/src/main/resources/web/js/15-session-data.js`, `kind` being `transactions` or `signals`. If that copy is marked for `state.sessionId`, it pushes onto the copy, as today. Otherwise, if a whole-session read of that kind is in flight for this session, it pushes onto that read's buffer. Otherwise it does nothing.
- `ws.onmessage` in `web/js/05-network.js` calls `admitLive` in place of its two direct pushes into the copies (`if (state.allSessionId === state.sessionId) state.allTransactions.push(...)` and the signals line). Its pushes into `state.transactions`, `state.signals` and `state.screens`, and the `liveTail` check before them, are unchanged; a paused page still drops rows, and resume still forgets.
- The buffers: `state.arriving = { transactions: null, signals: null }` in `web/js/00-core.js`, each `{ sessionId, rows: [] }` while a read is in flight.
- `syncAllTransactions` and `syncAllSignals`, in the branch that reads through `transactionPages` / `signalPages`, open their buffer synchronously before the first page request. When the read lands and the load is still latest: the copy is the read plus every buffered row whose id the read does not hold, then it is marked and the buffer is cleared. On failure the buffer is cleared, and the stand-in stays unmarked, as KIN-27 and KIN-35 require. A read that has been overtaken clears the buffer only if it is still its own (`state.arriving[kind] === buffer`), never a newer load's.
- `forgetWholeSession` also sets both buffers to null. The `callLoad` / `signalLoad` counters are unchanged.
- Why opening the buffer before the first request is enough: `SessionManager.append` (`inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/SessionManager.kt`) stores the row through `SessionWriter.append`, which flushes, before `_live.tryEmit`. A row broadcast before the buffer opens is already in what the read returns.
- `routePages` and the daemon's clamp are not touched.

The proof, in `scripts/lib/session-state-checks.js`, for each copy. The route stub must read its rows when it answers, not when the request arrives (`signalRoute` reads them before its `behave` wait today):
- `mid-read calls kept` / `mid-read signals kept`: a session of 1500 rows, so the read takes two pages. The stub holds the request for `offset=1000`, the check calls `admitLive` with a new newest row and adds it to the stub's rows, then releases the page. The landed copy holds the row.
- `mid-read calls once` / `mid-read signals once`: the stub holds the first page instead, and the row is admitted and added before it answers, so the read returns the row and the buffer holds it too. The landed copy holds it exactly once.
The stub states (`sessionState` and the states in `scripts/lib/follow-checks.js`, which loads `15-session-data.js` with `05-network.js`) gain the `arriving` field.
Red proofs: with the mark-on-land rule put back (`admitLive` pushing only into a marked copy, no buffer), both "kept" lines report NO. With the merge done as a concatenation instead of by id, both "once" lines report NO.

Regression. The existing whole-session checks stay green: `forget drops calls read`, `forget drops signals read`, `whole read failure`, `whole calls read failure`, `session change forgets`, `older call load dropped`, `older signal load dropped`, the live-follow checks, and the live probe's `resume refreshes`, `reconnect refreshes` and `drop refreshes`.

docs/decisions/web-ui.md, paragraph "The two cached copies have gone stale in the ways below":
- The opening "and all but the last are closed" becomes "and all are closed".
- The sentence beginning "Open: a live row that arrives while the whole-session read is between pages is in neither the read nor the copy" and ending "`item:web-transactions-paged` owns it." is replaced by the rule: from the first page request, a live row is buffered and merged by id when the read lands, for both copies, because the stored-then-broadcast order means a row broadcast earlier is already in the read; the four check names hold it.
Keep each decision in one place: `DecisionsTest` fails on a second copy.

Check counts. `check-web-static.js` has no pinned count; if the live probe gains a line, `UI_EXPECT_CHECKS` in `.github/workflows/ci.yml` changes in the same commit.

No public API, no schema, no change to the security boundary.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 admitLive(kind, row) in web/js/15-session-data.js puts a live call or signal into its copy when the copy is marked for the session, else into the in-flight whole-session read's buffer for that session (state.arriving in web/js/00-core.js); ws.onmessage in web/js/05-network.js calls it in place of its two direct pushes into the copies
- [x] #2 syncAllTransactions and syncAllSignals open their buffer before the first page request; on landing the copy is the read plus buffered rows whose ids the read lacks, then marked; a failed read clears the buffer and leaves the stand-in unmarked; an overtaken read never clears a newer load's buffer; forgetWholeSession clears both buffers
- [x] #3 check-web-static.js reports 'mid-read calls kept', 'mid-read signals kept', 'mid-read calls once' and 'mid-read signals once' yes, with stub routes that read their rows when they answer
- [x] #4 Red proof: with the mark-on-land rule put back (no buffer), both 'kept' lines report NO; with a concatenating merge instead of a merge by id, both 'once' lines report NO; restored, all yes
- [x] #5 The existing whole-session checks stay green: forget drops calls read, forget drops signals read, whole read failure, whole calls read failure, session change forgets, older call load dropped, older signal load dropped, the live-follow checks, and the live probe's resume, reconnect and drop refreshes
- [x] #6 docs/decisions/web-ui.md: 'all but the last are closed' becomes 'all are closed', and the 'Open: a live row that arrives while the whole-session read is between pages ...' sentence is replaced by the buffer-and-merge rule naming the four checks; DecisionsTest passes
- [x] #7 routePages and the daemon's clamp are unchanged; the whole-session read stays awaited and uncapped
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built. Red: admitLive with no buffer branch -> both 'kept' NO (0 times in 1500 rows), 76 checks 2 failed; withArrivals as read.concat(buffer.rows) -> both 'once' NO (2 times in 1502 rows), 2 failed; restored 76/0. Route stubs: added readAt ('arrival' default, 'answer' for the four new checks) rather than switching signalRoute/callRoute to answer-time reads, because 'forget drops ... read' depends on a held read answering from before the gap and would stop catching its defect. The overtaken-read rule (closeArrivals only clears its own buffer) is implemented but not pinned by its own check; the ticket names none. Live: resume/reconnect/drop refreshes yes, 185 passed; archive 35. No live line added, UI_EXPECT_CHECKS unchanged. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit 0fbced1. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). 'mid-read calls/signals kept' and 'once' yes in static; red proofs (no buffer; concatenating merge) re-run in round 2. KIN-47 completed the doc sentence.
<!-- SECTION:FINAL_SUMMARY:END -->
