---
id: KIN-131
title: The landing page fits a 400 px window
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 14:19'
updated_date: '2026-10-22 18:06'
labels:
  - 'item:landing-page-fits-400'
dependencies: []
type: bug
ordinal: 131000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §3 → Bugs found, not fixed → 'The landing page scrolls sideways at 400 px, and cuts a card's text' (order row 13f, lane S). At 400 px site/ is 605 px wide: grid items of .step and .grid2 keep min-width: auto, so a 531 px pre and a 454 px .term widen their columns. The architecture SVG is not the cause; it scrolls inside .diagram-box. Surfaces: site/index.html, scripts/measure-web-ui.js and a case file under scripts/lib, the web CI job. No public API, no INTEGRATION.md change.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 At 400 px, served from site/ in Chrome, the document is no wider than the window, and no element outside a scroll container ends past the window's right edge. The diagrams, the gallery, the code blocks and the tables may still scroll inside their own boxes.
- [x] #2 Every bullet of every card in Four surfaces ends inside the window at 400 px.
- [x] #3 At 1440 px the page measures as it does at 638119e: the fix narrows nothing that fits today.
- [x] #4 A check measures it, hosted by scripts/measure-web-ui.js on lib/measure-cdp.js with no daemon, only site/index.html from disk. It fails when the document is wider than the window at 400 px or a card's text passes the edge, proven red with the fix removed (605 px, then 558 px with only one half of it) and green with it.
- [x] #5 site/index.html stays inside its line budget (723). The fix is CSS on lines that already exist; the budget is not raised.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Fix: '.step > * { min-width: 0 }' on the .step line (every width) and '.grid2 > * { min-width: 0 }' inside the existing 820 px query (one column only). Global .grid2 rule evened the two columns at 1440 (478/538 -> 508/508), against the 1440 criterion, so it is scoped. site/index.html stays 723 lines. Guard: node scripts/measure-web-ui.js --only site (scripts/lib/site-cases.js), no daemon; readings 'site 400 document width', 'site 400 past the edge', 'site 400 card bullets' judged, 'site 1440 layout' a digest compared with --base. Red: 605 (no rule), 558 (.step only), 605 (.grid2 only); green 400. 1440 digest 1469658b at base and head. CI: web job step 'Landing page fits a 400 px window' on the runner's /usr/bin/google-chrome. Seen, not taken on: at 821-885 px the page is 886 px wide at base and head (two-column .grid2).

Closed 2026-10-22 by the delivery tracker on QA's report qa/landing-page-fits-400-report.md (verdict: close yes, no blocking findings), PR #168, merge 41b968c. Not verified by QA: Safari, Firefox and real phones. Outside this ticket: from 821 to 885 px the page is 886 px wide, on main before this fix too; placed as an unordered candidate in ROADMAP section 6.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: node scripts/measure-web-ui.js --only site gives 'ok site 400 document width: 400 px in 400', 'ok site 400 past the edge: 0 elements', 'ok site 400 card bullets: 0 of 12 past the edge' (base: 605 px, 51 elements, 12 of 12); against fda2cd8 'site 1440 layout' is 1440x10382, 455 boxes, digest 1469658b on both trees; red with both rules removed (605 px), only .step (605), only .grid2 (558); ci.yml's web job runs it, and main's push run for 41b968c read 385 px in 400 (Linux scrollbar), 0 failed; site/index.html is 723 lines.
<!-- SECTION:FINAL_SUMMARY:END -->
