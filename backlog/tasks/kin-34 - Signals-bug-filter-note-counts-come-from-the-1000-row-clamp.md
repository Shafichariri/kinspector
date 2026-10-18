---
id: KIN-34
title: 'Signals bug: filter-note counts come from the 1000-row clamp'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
updated_date: '2026-10-18 21:46'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 34000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Simon, Doug) on KIN-28 AC1 at scale, head e1711a3. On a 3016-call session: a filter matching 1200 calls draws no waterfall note; one note reads '996 calls hidden' where 3012 are; a no-match filter reads '1000 calls hidden'. The counts come from the 1000-row clamp on the rows read. The route's total is the session total and matched is the match count; state.matchTotal holds the total and is misnamed.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Unranged hidden calls = total - matched
- [ ] #2 Where only a capped range count exists, the note says 'at least'
- [ ] #3 The path's '0 of N' uses an uncapped N
- [ ] #4 state.matchTotal is renamed to what it holds
- [ ] #5 A probe or static check runs a session over 1000 calls, red at e1711a3
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). AC4: state.matchTotal renamed state.sessionTotal (it held the route's 'total', the session's call count) and state.matched added (the route's 'matched'); renderCounts reads sessionTotal; a live txn bumps both (the list takes it as one more match, so hidden stays put). AC1: hiddenByFilter (25-traffic-list.js) returns calls = sessionTotal - matched whenever no brushed stretch applies. AC2: in a brushed stretch it counts held rows only from the oldest mono at which both lists are still whole (each list is the newest rows; a list shorter than its route count is whole from its oldest row up), and returns atLeast when the stretch reaches below that floor; filterNote prints 'at least N calls'. AC3: the whole-session call copy is now read through the pager: the mirror block signalPages is renamed routePages and holds routePages(get, route, filter, rowsOf) with signalPages and transactionPages as its two shapes; syncAllTransactions pages under a filter, and without one when the list's page is short of sessionTotal. renderPath's N moved into countRecorded(list) in 50-path.js, unchanged in logic, so a check can call it. AC5, check-web-static.js '--- session state ---', a 3016-call stub (1200 to /v3/shop/bulk, screens Old@0 and New@2500) clamped at 1000 with total/matched as the daemon answers, run through the real loadTransactions: RED at e1711a3's web/js (the four lines): 'hidden past the clamp     : NO - " hidden by the filter path:/v3/shop/bulk", where 1816 calls hidden was due (3016 less 1200 matched)'; 'stretch past the clamp    : NO - " hidden by the filter path:/v3/shop/bulk" (0 calls) for a stretch the rows held cannot count; "at least" was due'; 'hidden when none match    : NO - "1000 calls hidden by the filter path:/no", where 3016 calls hidden was due'; 'path N past the clamp     : NO - "no countRecorded in js/50-path.js"...'; 41 checks, 4 failed. Broken one at a time on the fix: whole copy back to one limit=2000 read -> 'path N past the clamp: NO - "0 of 484 match / 0 of 516 match", where "0 of 2500 match / 0 of 516 match" was due' (and stretch NO); unranged count back to held rows -> 'hidden past the clamp: NO - "at least 1616 calls hidden...'; 'at least' prefix removed -> 'stretch past the clamp: NO - "50 calls hidden...'; pager stopping at a short page -> 'pages under a 500 clamp: NO - 500 ids' and 'calls under a 500 clamp: NO - 500 ids'. GREEN: 45 checks, 0 failed (+4 'calls ...' pager lines, +4 past-the-clamp lines). Doug's scale.js on his 3016-call session (copied, port 8241), matchTotal read as sessionTotal: path:/v3/shop/bulk -> waterfall '1816 calls hidden', timeline '1816 calls and 19 signals hidden', path '1816 calls and 13 signals hidden'; path:/v3/shop/stock -> 3012 everywhere; path:/nothing-at-all -> '3016 calls hidden'. Fixture probes: live 179, archive 26, unchanged. DEVIATION / overlap: paging the whole-session call copy is also the first two ACs of item:web-transactions-paged (allTransactions holds every call; one pager) - AC3 cannot hold without it. Not done here, left to that item: the list itself still stops at 1000 rows and its count still reads '1000 of 3016 calls' with no filter; no live probe on a >1000-call session; the archive. Known and not fixed: under a filter matching more than 1000 calls, a path visit older than the newest 1000 matches still reads '0 of N match' because the filtered rows stop at the clamp (same root as the list; same item).
<!-- SECTION:NOTES:END -->
