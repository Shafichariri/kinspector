---
id: KIN-41
title: 'Web bug: a live call or marker moves the pane while the timeline is shown'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-19 05:04'
updated_date: '2026-10-19 06:07'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-40
type: bug
ordinal: 41000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the fourth verify panel (head 8082e02), blocking. Regression from KIN-33, which removed the timeline's own scroller. Measured on a 3016-call session with the timeline shown, live on and the pane in the middle: one call through ws.onmessage moves scrollTop 14927->29853 (oldest-first) or 14984->0 (newest-first); a marker does the same. At e1711a3 it stayed at 14884. Cause: renderList's follow (web/js/25-traffic-list.js:348-352) scrolls the shared #list-pane whatever view is shown. follow-checks.js calls renderTimeline() directly, so it never sees this; web-ui.md's KIN-38 follow paragraph is therefore false in practice. Harnesses: $TMPDIR/doug4/tools/livemove.js and scratchpad simon4/tools/. Architect's fix direction: set state.timelineFresh in setSortOrder, loadTransactions and setTimeRange so those still follow.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With the timeline (or path or waterfall) shown and scrolled back, a live call or marker leaves scrollTop unchanged in both orders
- [ ] #2 With the pane at the end it follows, a new row is still followed
- [ ] #3 The order toggle, a filter load and a time-range brush still follow on the timeline, as at 8082e02 (state.timelineFresh set in setSortOrder, loadTransactions and setTimeRange)
- [ ] #4 A new check drives the real socket txn and marker path while the timeline is scrolled back; red at 8082e02, green after
- [ ] #5 web-ui.md's follow paragraph is true and names the check
- [ ] #6 Chrome measurement at 1440 and 400 recorded
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer (bob), head after 8082e02.

Fix: renderList follows only while the list is the view shown (web/js/25-traffic-list.js). setSortOrder, loadTransactions and setTimeRange set state.timelineFresh before renderTimeline, so those three still follow on the timeline (they had followed only because the list's follow moved the pane first). The waterfall has no follow of its own, so it no longer moves under live tail at all; that is a consequence, not new behaviour, and is said in web-ui.md.

New checks, check-web-static.js live follow section (scripts/lib/follow-checks.js checkSocketFollow), driving the real 05-network.js ws.onmessage over the real renderers on a stub pane: socket scrolled timeline / path / waterfall (a live call and a marker, both orders, pane at 12000 stays 12000), socket at end timeline (marker and signal at the end still followed, both orders), socket list follows (the list view still follows a live call), timeline asked follows (order toggle, a load, a brushed stretch follow from the middle). Static 64 -> 71 checks.
Red at 8082e02 (its three web files under the new harness): 71 checks, 3 failed - socket scrolled timeline/path/waterfall each 'txn oldest 12000 -> 30000, marker oldest 12000 -> 30000, txn newest 12000 -> 0, marker newest 12000 -> 0'. Both directions proven on the fix: view gate removed -> the same 3 red; gate made never-true -> socket list follows red; timelineFresh removed from setSortOrder -> 'order 12000 -> 12000' red; from loadTransactions -> 'load 12000 -> 12000' red; from setTimeRange -> 'stretch 12000 -> 12000' red; each restored green.

Chrome (headless, CDP; doug4 livemove.js/livearrive.js, simon4 scroll.js) at 1440 and 400, 8082e02 -> after:
- 3016-call session, timeline mid, txn: 1440 oldest 14900->29800 now 14900->14900; newest 14900->0 now 14900; 400 oldest 15286->30572 now 15286; newest ->0 now 15286. Marker: 1440 oldest 14900->29824 now 14900; newest ->0 now 14900; 400 oldest 15286->30596 now 15286; newest ->0 now 15286.
- scroll.js 'arrive txn from middle': 1440 oldest 14927->29853 MOVED now stayed 14927; newest 14984->0 MOVED now stayed; 400 oldest 15313->30625 now stayed; newest 15370->0 now stayed.
- waterfall mid txn/marker: 1440 10315->20630/0 now 10315; 400 10379->20757/0 now 10379. Path (400, 113px of scroll) 57->112/0 now 57.
- fixture: timeline mid txn 1440 142->284/0 now 142; 400 203->406/0 now 203; marker 142->307/0 now 142; path 400 61->121/0 now 61.
- at end, signal: unchanged (1440 oldest 29800->29827 followed, newest 0->0; 400 likewise). List view mid txn still follows (1440 14686->29402, newest ->0), unchanged.
- Unchanged from 8082e02, byte for byte: measure.js 88/0 both sessions, k33.js 66/0 both, m3.js 0 fails both, runjump.js (run toggle 24->24, 29800->29800; 400 95->95, 30510->30510).
- 160k session: rows at 26.1s (8082e02) / 27.3s (here) in hugetime.js, 'ready' 26.6/28.6s at 1440 and 26.5/28.8s at 400 - KIN-42's defect, noise between runs; run toggle there 24->24 and 95->95 both.

Probes: static 71/0; live 182 passed 0 failed (unchanged, ci.yml unchanged); archive 26/0. gradle :inspector-daemon:test 267/0, :repo-checks:test 35/0.

Found, not fixed (outside the ticket): a live call does not redraw the timeline at all - the txn branch of ws.onmessage never called renderTimeline, at e1711a3 either - so the call appears there only on the next redraw (scroll.js rows [1017,1017] across a live txn). With the pane at the end a live call is therefore 'followed' only in the sense that nothing moves.

Also run after commit 8f64058: node scripts/measure-web-ui.js --base <qa-snapshot of 8082e02> (the suite added in d932227). base 184 readings, 22 failed; head 184 readings, 2 failed. Moved against base: 20 lines, every one 'scrolled back: moves -> stays' (big 1440 and 400, both orders, timeline/waterfall txn+marker, and path at 400) - all meant by KIN-41. The 2 head failures are KIN-42's: first rows huge 27062 ms, connected huge 27163 ms (over the 3000 ms bar); big 207/207 ms within.
<!-- SECTION:NOTES:END -->
