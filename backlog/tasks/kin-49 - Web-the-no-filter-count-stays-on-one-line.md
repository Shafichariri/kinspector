---
id: KIN-49
title: 'Web: the no-filter count stays on one line'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 11:01'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies: []
type: bug
ordinal: 49000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the first verify panel (qa doug, architect charles-2, ux simon; base c21aaf0, head 0fbced1); all three agreed. It fails KIN-45's count criterion as seen: between about 600 and 975 px, #counts wraps inside #topbar's fixed 44 px height, and what is visible is "1000 of 1203", the filter shape the item forbids. Fix in inspector-daemon/src/main/resources/web/css/05-base.css: "#counts { white-space: nowrap; }" and "#session-picker { min-width: 120px; flex: 0 1 380px; }" (max-width: 380px stays), the picker rule beside the existing #session-picker rule. Each rule gets a why-comment: the count must not wrap, the picker is what gives way, and 1a rebuilds the bar. nowrap alone is NOT acceptable: simon measured that it pushes restart and stop off-screen at 975 px, and settings at 900. Out of scope: overflow below about 900 px, which belongs to 1a (item:web-foundation-shell, ROADMAP "(e) Top bar and tab bar overflow").
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 At 975 and 800 px, every word of #counts is inside #topbar and the viewport.
- [x] #2 No top-bar control that base has inside the viewport at a measured width is outside it at head.
- [x] #3 The proof is a measure-web-ui.js (or scratch Chrome) reading at 975 and 800 px, run three ways, numbers recorded in the ticket notes: as built red (the count wraps); nowrap alone red (restart and stop off-screen at 975); both rules green.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in 7a56668: css/05-base.css gains '#session-picker { min-width: 120px; flex: 0 1 380px; }' beside the existing picker rule (max-width 380px kept) and '#counts { white-space: nowrap; }', each with a why-comment. Proof: scratch headless Chrome (Chrome's own layout, DevTools protocol; a copy of simon's bar.js that also measures each word of #counts with a DOM Range against #topbar and the viewport) on the 1203-call probe session, daemon serving each tree's web/ ahead of the head jar. At 975 / 800 px:
- as built (0fbced1): RED. 975: count takes 4 lines, 'newest' at y -8..7 and 'shown' at y 37..52, outside the 44px bar (#counts box 567-631). 800: 5 lines, 'newest', '1000' and 'shown' outside; settings clipped, restart and stop off-screen.
- nowrap alone (injected): RED. 975: count on 1 line, inside; settings CLIPPED [937-992], restart OFF [998-1048], stop OFF [1054-1092] in a 975 viewport. 800: live CLIPPED, conn, settings, restart, stop OFF.
- both rules (injected, then from the real file, identical output): GREEN. 975: count 1 line, every word inside [435-631]; every control ok, stop [922-961]. 800: count 1 line, every word inside [307-502]; picker 86-206; restart ok [738-788]; stop CLIPPED [794-832] (base has stop OFF at 800, so AC#2 holds; below ~900 px is 1a's).
AC#2, 21 widths 600..1100 step 25, base c21aaf0 against head: no control that base has fully inside the viewport and the bar is not so at head (scripted comparison). For the record, nowrap alone fails that comparison at 31 width/control pairs from 700 to 1075 px. #counts is fully inside at every width 600..1100 at head; at base it was clipped below 1000 px.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit 7a56668. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). Three-way reading (as built red, nowrap alone red, both rules green) at 975 and 800 px is in the ticket notes; 21-width base-vs-head comparison clean. Scope: Chrome 131 headless only, no other engine; stop is still clipped at 800 px (base had it off-screen), below about 900 px is item 1a.
<!-- SECTION:FINAL_SUMMARY:END -->
