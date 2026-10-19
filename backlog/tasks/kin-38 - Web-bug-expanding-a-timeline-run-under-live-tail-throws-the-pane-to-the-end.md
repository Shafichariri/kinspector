---
id: KIN-38
title: 'Web bug: expanding a timeline run under live tail throws the pane to the end'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
updated_date: '2026-10-19 05:04'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 38000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking. Introduced by KIN-33. On a 3016-call session with live on: oldest-first, scrollTop goes 24 -> 29934; newest-first, 29800 -> 0; similar at 400. e1711a3 moved at most 34px. A plain row click does not move it. Cause: the follow in 70-timeline.js:121-125 now scrolls #list-pane on every re-render. Harness: $TMPDIR/doug3/tools/runjump.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 With live on, expanding or collapsing a run leaves scrollTop unchanged in both orders at 1440 and 400
- [x] #2 A newly arriving row is still followed when the pane was already at the end it follows, as logAtEnd does in 50-path.js
- [x] #3 A check that is red against a86dde2: a jsdom probe if it can see it, otherwise a documented Chrome check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit c69dd4c. renderTimeline({ follow = true }) measures, before replacing its rows, whether #list-pane is at the end it follows (newest-first: scrollTop <= 8; oldest-first: within 8px of the foot), and follows only when liveTail && follow && (fresh || atEnd). fresh is state.timelineFresh, set by applyView when the timeline goes from hidden to shown (as pathFresh is), so opening the view still follows once. A run's toggle redraws with { follow: false }. 00-core.js gains the timelineFresh field.
NEW CHECKS (check-web-static.js, section 'live follow', scripts/lib/follow-checks.js; the stub DOM in long-session-checks.js now keeps children, classes and listeners): real 70-timeline.js and applyView over a stub pane (scrollHeight 30000, clientHeight 800), both orders: 'run toggle' (expand and collapse at 12000), 'run toggle at end', 'arrival at end' (a new signal, pane within 5px of its end -> followed), 'arrival scrolled' (pane at 12000 -> stays), 'opening' (applyView with the timeline hidden -> follows). Static 51 -> 61 checks, 0 failed.
RED PROOFS: a86dde2's 70-timeline.js + 20-navigation.js -> 'run toggle oldest: NO - expand 12000 -> 30000, collapse 12000 -> 30000', 'run toggle at end oldest: NO - 29200 -> 30000', 'arrival scrolled oldest: NO - 12000 -> 30000', 'run toggle newest: NO - expand 12000 -> 0 ...', 'arrival scrolled newest: NO - 12000 -> 0' (the arrival-at-end and opening lines stay green there, as they should: that is the behaviour kept). Toggle back to renderTimeline() alone -> 'run toggle at end oldest: NO - 29200 -> 30000'. atEnd dropped from the condition -> both 'arrival scrolled' NO. applyView's timelineFresh line removed -> 'opening oldest: NO - 12000 -> 12000', 'opening newest: NO - 12000 -> 12000'. Each restored -> 61 checks, 0 failed.
CHROME (headless Chrome 131, CDP 9477, fresh profile per run): Doug's runjump.js, run click, before -> after: 3016-call session 1440 oldest 24->24 (was 24->29934), 1440 newest 29800->29800 (was ->0), 400 oldest 95->95 (was ->30706), 400 newest 30510->30510 (was ->0); fixture 1440 oldest 236->236 (was ->415), newest 81->81 (was ->0), 400 oldest 302->302 (was ->540), newest 137->137 (was ->0). Row clicks unchanged (14947, 14917, 15328, 15308; 154, 192, 220, 248). Simon's m3.js 'expanding a run keeps place' FAIL -> ok at 1440 and 400 on both sessions (after 0, was 414.5/539.5/29933.5/30705.5); fails 2 -> 0. AC2 in Chrome (tools/arrive38.js, a signal pushed and renderTimeline() called): at the end it follows, oldest-first 284->310 (=max) fx 1440, 406->433 fx 400, 29800->29827 big 1440, 30572->30599 big 400; newest-first 0->0 everywhere; scrolled to the middle it stays (155, 217, 14914, 15300 oldest; 181, 243, 14940, 15326 newest).
EVERY OTHER NUMBER UNCHANGED: measure.js 88/0 and k33.js 0 FAIL on both sessions byte-identical to KIN-37's run; m3.js differs only in the two run lines and the fails count; 'timeline as rendered' still 29799.5/284 (opening follows). Live probe 182/0, archive 26/0, :inspector-daemon:test 267/0, :repo-checks:test 35/0, huge37 on 160,016 calls still green (rows 1000, axis 205 rects, connected, no exceptions).
OBSERVED, NOT FIXED (outside the ticket): renderList's own follow (25-traffic-list.js, 'Follow the newest row') also scrolls the shared pane whenever it runs, including while the timeline is shown — a live call arriving while the reader is scrolled back in the timeline moves the pane through renderList, not renderTimeline. Same shape as ROADMAP's 1b item 'Each traffic view opens where it was left'.

Measured, the observation above: on the 3016-call session at 1440 with the timeline shown and the pane at 14900, one call pushed and renderList() called (what the socket does for a 'txn') -> scrollTop 0. Not touched by c69dd4c.

QA (fourth verify panel doug-4, charles-4, simon-4, head 8082e02): every AC of this ticket Verified; ACs ticked, ticket left In Progress because the item closes together. Panel reports filed as received; the per-AC command and decisive line are in the panel reports, not copied here.
<!-- SECTION:NOTES:END -->
