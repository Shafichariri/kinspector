---
id: KIN-33
title: >-
  Web bug: the timeline's filter note covers the sticky prefix bar, and a
  station click lands under the path's sticky box
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
ordinal: 33000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Regressions outside the ACs, found by the second verify panel (Simon, Doug), head e1711a3, measured in headless Chrome. (B2) Timeline: '#timeline > .filter-note' (35-list.css:191, z-index 2) sits on the scope bar at the same y, so rows show stripped paths with no bar, breaking shared-rules.md's sticky-bar rule. Root cause predates the diff: #timeline is height:100% beside the bar, and live follow scrolls #list-pane (70-timeline.js:122); this also hid the first 34px of the timeline before. (S5) Path view: scroll-margin-top: 96px (20-path.css:119) does not count the note, so a clicked station lands 27px (1440) or 41px (400) under the sticky box. Measurement scripts: $TMPDIR/simon2/tools/{scope,interact,tl}.js and $TMPDIR/doug2/h/scopetop.js. Screenshots: $TMPDIR/simon2/shots/served-8272/1440-path-all.png against served-8271/...
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 In headless Chrome at 1440 and 400, under a filter, the element on top at the scope bar's centre is the scope bar
- [x] #2 The timeline's first row is fully visible, with and without a filter
- [x] #3 A clicked station's row head is below the sticky box at both widths, with and without a filter
- [x] #4 Each is checked by a committed measurement or a documented manual check recorded in the notes, and each is red against e1711a3
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). Fix: (1) every sticky thing in #list-pane stops at --sticky-top, which is the scope bar's height while it is drawn and 0px otherwise (#list-pane { --scope-bar-h: 33px; --sticky-top: 0px } and #scope-bar:not([hidden]) ~ * { --sticky-top: var(--scope-bar-h) }; the bar's height is fixed to --scope-bar-h, its natural 33px): #timeline > .filter-note, .pv-top, .wf-head and .path-new (top: calc(var(--sticky-top) + 6px)). (2) #timeline is no longer its own height:100% scroller; it flows in #list-pane like #list, so live follow (70-timeline.js, root.parentElement) scrolls the pane that actually holds it. (3) a station click scrolls its visit to just under the sticky box via scrollBelowMap(row) in 50-path.js, which measures the box (computed top + offsetHeight) at click time; scroll-margin-top: 96px removed. index.html comment and web-ui.md updated (new paragraph 'Nothing sticky in the list pane stops above the scope bar'). Files: css/35-list.css, 60-timeline.css, 20-path.css, 15-waterfall.css, js/50-path.js, index.html. No @media touched; static check 45/0 (hidden panes, 11px, media order all yes).
MEASUREMENT (AC4: documented manual check, not committed — needs Chrome and a daemon): /private/tmp/claude-501/-Users-shafichariri-development-tools-kinspector/bc83ec9e-65d6-4a86-9585-442f1d7fec8a/scratchpad/bob/measure.js, headless Google Chrome 131 over CDP (--headless=new, port 9455), widths 1440 and 400 x 900, filters none / path:/v3/shop/stock (thin) / path:/v3/shop (wide) / tag:screen. Per width and filter: timeline - elementFromPoint at the scope bar's centre is the bar; as rendered under live follow, a timeline that fits shows its first row whole below the bar+note, one that overflows has its newest row in view; scrolled to the start, the first row is whole below bar+note; path - bar on top, and for every station clicked its row head is at or below max(bar bottom, .pv-top bottom); waterfall - bar on top. Base = git worktree at e1711a3 (installDist), head = this commit; same CI fixture (copied) and Doug's 3016-call session (copied).
RED at e1711a3: fixture 88 checks, 30 failed; 3016-call session 88 checks, 32 failed. Decisive lines (fixture): '1440 thin timeline: scope bar on top {onTop:false, hit:filter-note muted, bar:[234,267]}' (same for wide/tagscreen, and at 400 bar [333,366]) = AC1; '1440 thin timeline: first row visible {rowTop:266, ceiling:267, visible:false}' and '1440 none timeline: newest row followed {rowTop:221, ceiling:254, atRowTop:scope-bar, lastBottom:1151, paneBottom:900}' (first row under the bar, newest row never followed; 400: rowTop 333, ceiling 366) = AC2; '400 thin path: station 0 below the box {ceiling:427, headTop:394}' (33px under), '1440 wide path: station 0 below the box {ceiling:319, headTop:300}' (19px under; 3016-call session: ceiling 314, headTop 295), '400 none path: scope bar on top {onTop:false, hit:pv-stop}' (the map covered the bar even with no filter) = AC3/AC1. GREEN at head: fixture 88 checks, 0 failed; 3016-call session 88 checks, 0 failed; no page exceptions. E.g. '1440 thin timeline: scope bar on top {onTop:true, bar:[234,267]}', '1440 thin timeline: first row visible {rowTop:299, ceiling:299}', '400 none timeline: newest row followed {lastBottom:900, paneBottom:900}', '400 thin path: station 0 below the box {ceiling:460, headTop:468}'. Screenshots: scratchpad/bob/shots/{base,head,big-base,big-head}/ (e.g. head/400-thin-path.png, head/1440-thin-timeline.png - looked at). Behaviour change to note: with live on and oldest first, an overflowing timeline now follows its newest row (it never did: follow scrolled the pane by the bar's height only), so its first row is visible after scrolling up, not on load - AC2 is read as 'not covered'. Live probe 182, archive 26, unchanged. Daemons and Chrome stopped.

QA (third verify panel, head a86dde2): AC1-4 Verified. Not closed: the item closes together. Panel found a regression introduced by this ticket, filed as the 'expanding a timeline run under live tail' bug (B) under the item.
<!-- SECTION:NOTES:END -->
