---
id: KIN-40
title: >-
  Web bug: path stations say '0 of N match' for visits whose calls match, past
  1000 matches
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
updated_date: '2026-10-19 00:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 40000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. On the 3016-call session under path:/v3/shop (3015 match), Home, Product and Cart read '0 of 2/2/5 match', because their calls are older than the newest 1000 loaded rows. Beside it, the note says '1 call hidden' and the header '1000 of 3016 calls'.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A visit older than the oldest loaded filtered row does not claim 0 matches: it says the calls are not loaded, or it counts correctly
- [ ] #2 A check on a >1000-match session, red against a86dde2
- [ ] #3 The header's count and the note agree, or the note says what its count covers
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit d321929. 50-path.js markUnloaded(list): when state.transactions is shorter than state.matched (the list stopped at the route's clamp), each visit whose whole-session skeleton (visits() over allTransactions, as countRecorded asks) holds a call older than the oldest held call is marked unloaded. emptyVisitText then says 'calls not loaded' (station and log) instead of '0 of N match' / 'nothing recorded'; a visit with calls drawn adds ' · older calls not loaded' to its log stats (' · calls not loaded' when it draws only signals) and to the station's tooltip; an empty visit's body reads 'calls not loaded — the list holds the newest N of M'. 25-traffic-list.js filterNote appends ' — newest N of M matches loaded' whenever the list is short of the route's matches (AC3: the header's '1000 of 3016 calls' and the note's '1 call hidden' now sit beside 'newest 1000 of 3015 matches loaded'). Applies with no filter too: unfiltered on the 3016-call session the same visits read 'nothing recorded' before.
NEW CHECK (check-web-static.js, long session section, checkPathPastTheClamp in scripts/lib/long-session-checks.js): real renderPath over a stub DOM, 2816 calls (all but the first to /v3/shop) over Home 0, Idle 300 (no calls), Cart 500, Checkout 1000, the list holding the newest 1000 matches. 'path clamp path:/v3/shop' and 'path clamp no filter': Home and Cart 'calls not loaded', Idle 'nothing', Checkout's log stats carry 'older calls not loaded', the filtered note carries 'newest 1000 of 2815 matches loaded'. 'path clamp path:/v3/auth' (one match, not clamped): Cart still '0 of 500 match', Home '1 network'. Static 61 -> 64 checks, 0 failed.
RED PROOFS: a86dde2's 50-path.js + 25-traffic-list.js -> 'path clamp path:/v3/shop: NO - Home reads "0 of 300 match" ... Cart reads "0 of 500 match" ... Checkout reads "1000 network", without "older calls not loaded"; the note reads "1 call hidden by the filter path:/v3/shop — clear filter", without what its count covers' and 'path clamp no filter: NO - Home reads "nothing" ... Cart reads "nothing" ...'. markUnloaded(list) call removed -> both NO. The note's line removed -> 'path clamp path:/v3/shop: NO - the note reads ...'. The log suffix removed -> both NO on Checkout. Visits marked by start alone (visit.from < oldest) -> both NO 'Idle reads "calls not loaded", where "nothing" was due'. Each restored -> 64/0.
REAL DATA (scale3.js on the 3016-call session, jsdom): path:/v3/shop stations were 'before first=0 of 2 match | Home=0 of 2 | Product=0 of 2 | Cart=0 of 5 | Product=0 of 1 | Checkout=1000 network · 249 failed', now 'calls not loaded' x5 and 'Checkout=1000 network · older calls not loaded · 249 failed'; path note '1 call and 13 signals hidden by the filter path:/v3/shop — newest 1000 of 3015 matches loaded — clear filter'. path:/v3/shop/stock (4 matches, not clamped) unchanged: '0 of 2/2/2/5/1 match', 'Checkout=4 network · 3 failed'.
MEASUREMENTS: first wording ('— the newest 1000 of 3015 matches are loaded') wrapped the note at 1440 on the 3016-call session under path:/v3/shop and moved the sticky box 14px (timeline ceiling 282->296, path ceiling 347->361, station gap 0->1, measure.js still 88/0); shortened to '— newest N of M matches loaded', after which every layout number in measure.js, k33.js, m3.js and runjump.js is identical to KIN-38's run at 1440 and 400 on both sessions; only m3's 'texts' lines differ (the note's words, under the shop filter). Live probe 182/0, archive 26/0, :inspector-daemon:test 267/0, :repo-checks:test 35/0 (first run failed FileBudgetTest, 50-path.js at 606 lines; my own comments trimmed to 600, no budget entry added), huge37 on 160,016 calls green.
OPEN, NOT FIXED (outside the ticket): the waterfall's stub visits read 'no matching calls' / 'no calls' for visits whose calls were not loaded (45-visits.js:238); recorded in web-ui.md as not yet taking this rule.
<!-- SECTION:NOTES:END -->
