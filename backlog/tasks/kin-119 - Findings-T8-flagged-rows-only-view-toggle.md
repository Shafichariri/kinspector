---
id: KIN-119
title: 'Findings T8: flagged rows only view toggle'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 08:24'
updated_date: '2026-10-22 13:48'
labels:
  - 'item:findings-payment-safety'
dependencies:
  - KIN-118
type: feature
ordinal: 119000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 26 (26a for Shared body evidence), docs/ROADMAP.md §6 → Findings. Built to the architect's design note for item:findings-payment-safety (questions answered 2026-10-22 by the maintainer's proxy: the findings key is read live; Q1 keep both notions of duplicate; Q2 six default payment paths; Q3 resubmitWindowMs 60000 beside duplicateWindowMs 3000; Q4 every mutation plus an empty ignorePaths; Q5 not in this item). PR B or C (web). web/js/97-findings.js, 25-traffic-list.js, index.html, css/90-findings.css, scripts/lib/findings-probes.js.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 ⋮ → flagged rows only leaves the 12 flagged rows with a band saying what it counts and that it is on; off restores 18
- [x] #2 It is not remembered across a reload
- [x] #3 A no-findings session says "No calls with findings among the calls loaded" with "show all calls"
- [x] #4 The band passes sticky top
- [x] #5 25-traffic-list.js stays ≤ 600 lines
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. findingsState.flaggedOnly (not in prefs, so not remembered across a reload); a ⋮ menuitemcheckbox 'flagged rows only' added to #more-menu from 97-findings.js.
2. findingsKeep(row) applied in orderedRows() and renderList's sequence only (list only; timeline, waterfall, path untouched) - zero net lines in 25-traffic-list.js.
3. #flagged-note in index.html after #list-note: sticky on var(--sticky-top), 'Only calls with findings: N of M calls loaded/in this session', 'show all calls', list only; drawn by findingsPaintFlagged(rows) from renderList (one line).
4. Empty state fifth reason 'No calls with findings among the calls loaded.' with 'show all calls', from the same painter.
5. Probes: flagged only (12 then 18 on payments), flagged not remembered (no storage written), flagged none (probe session); sticky top audit red with top: 0 on the band.
6. 25-traffic-list.js kept at or under 592 (591 now).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on KIN-118 (scratch kin-119-t8.patch applies after kin-118-t7.patch onto fc3296f and ends at the working tree). Ready for QA with KIN-118. Static 133, live 289, archive 56.

What: findingsState.flaggedOnly (never stored); findingsFlaggedItem() in wireMoreMenu's list ('flagged rows only', menuitemcheckbox, ✓ when on, as compact rows); findingsKeep applied in orderedRows() and renderList's sequence only, so the timeline, waterfall and path draw every call; findingsPaintFlagged() from renderListNote (so applyView draws it too): #flagged-note (index.html, after #list-note) reads 'Only calls with findings: 12 of 18 calls in this session. List only: ...' with 'show all calls' (or 'N of M calls loaded. Findings on older calls are on the Findings page.'), and the fifth empty reason 'No calls with findings among the calls loaded.' with 'show all calls'. Band sticky on var(--sticky-top); static beside a shown #list-note so the two never stack. 25-traffic-list.js 591 (zero net vs fc3296f).

How each criterion is shown:
#1 'flagged only' (on: 12 rows, all flagged, band text; off: 18, band hidden).
#2 'flagged not remembered' (local and session storage and the address unchanged by the toggle).
#3 'flagged none' (probe session: 0 rows, 'No calls with findings among the calls loaded. show all calls'; the button gives back 16).
#4 static 'sticky top' (9 sticky selectors, each from --sticky-top).
#5 wc -l: 591.
Updated guards: 'more menu' (7 items) and archive 'export items hidden' (7 items, 3 offered).

Red proofs (each restored):
- keep-all predicate -> 'flagged only: NO - on: 18 rows (6 unflagged) ...' and 'flagged none: NO - on: 16 rows ...'
- empty reason not drawn -> 'flagged none: NO - on: 0 rows, empty "no calls were recorded in this session" (shown true) ...'
- prefs.set on toggle -> 'flagged not remembered: NO - the toggle wrote [...["inspector.flaggedOnly","1"]...] where [...] was'
- #flagged-note { top: 0 } -> 'sticky top: NO - sticky, and stops above the scope bar: #flagged-note { top: 0 } (css/90-findings.css)'
- probe leaves flagged on -> 'findings traffic restored: NO - ... flagged true' (19 later lines failed with it)
- item removed from ⋮ -> 'flagged only: NO - found no flagged rows only item, so this checked nothing'; 'more menu: NO - 4 exports, then keyboard shortcuts, compact rows'; 'export items hidden: NO - ... offered: keyboard shortcuts, compact rows'

Not verified: a real reload in a browser (the probe reads storage and the address, which is what a reload restores from).

Closed 2026-10-22 by the delivery tracker on QA's report qa/findings-pr-c-report.md (verdict: close yes, no blocking findings), PR #164, merge f4bbfbf. Nothing listed as not verified for this ticket.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA clicking the real menu item: 12 rows and the band 'Only calls with findings: 12 of 18 calls in this session…', off restores 18; a real location.reload() gives 18 rows, no storage key; a no-findings session reads 'No calls with findings among the calls loaded' with 'show all calls'; sticky top passes; 25-traffic-list.js is 591 lines. Red with the filter keeping every row: 'flagged only : NO - on: 18 rows (6 unflagged)'.
<!-- SECTION:FINAL_SUMMARY:END -->
