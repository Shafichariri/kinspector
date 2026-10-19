---
id: KIN-28
title: 'Signals bug: empty states under a filter say nothing happened'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 28000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Simon) on KIN-24 AC6, head 5f6b610. Blocking. docs/decisions/web-ui.md falsely claims the waterfall says 'no matching calls'. Three empty states, all reached under a filter: (1) Waterfall: when the filter leaves no calls it shows the static 'no calls to lay out' from index.html:199 beside 'calls 16'. (2) Path view: a visit emptied by the filter says 'nothing' (50-path.js:227) and 'nothing recorded' (:289) next to '0 matching' (:302); on the fixture that is 4 of 6 visits. (3) Timeline: an empty filtered pane shows a bare 'no entries', and a marker in range, which ignores the filter, hides even that; signals the filter thinned out are dropped with no note. Screenshots: $TMPDIR/simon-sig/shots/served-8262/ (1440-nomatch-path, 400-nomatch-path, 1440-nomatch-all) and served-8262-wf/1440-tagscreen-waterfall.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Each pane (waterfall, path, timeline), when emptied or thinned by a filter, says so, names the filter, and offers 'clear filter' as the traffic list does
- [x] #2 'nothing recorded' appears only when nothing was recorded; under a filter the path view reads '0 of N match', with N from the unfiltered session
- [x] #3 The timeline decides it is empty by counting calls and signals, not markers, and when the filter hid signals it says how many (for example '19 signals hidden by the filter')
- [x] #4 web-ui.md's false sentence about the waterfall is corrected
- [x] #5 render-web-ui.js checks each case, proven red then green
- [x] #6 The tab bar still never changes under a filter
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 67edf15. 25-traffic-list.js gains clearFilterButton() (the list's own control, now shared), hiddenByFilter() (whole-session rows less filtered ones; ranged for brushing panes; screens excluded on the path) and filterNote(). Waterfall: emptied says which of three things (stretch / filter / no calls), thinned gets a note above the groups. Path: '0 of N match' on station and stats, N from visits() over the whole session (same skeleton, index-aligned); 'nothing recorded' only when N is 0; the stub body reads 'hidden by the filter'; pane note rides in a sticky .pv-top with the map. Timeline: empty decided on calls+signals, never markers; emptied note names hidden counts ('16 calls and 19 signals hidden'); thinned note sticky inside #timeline; #timeline-empty moved above #timeline and an empty timeline no longer follows its foot. web-ui.md: false waterfall sentence removed, new paragraph; the signals paragraph reordered because KIN-27/29 text had split 'Both are read through signalPages' from its referent. Probe: 7 lines (waterfall emptied, waterfall thinned, path 0 of N match, path filter note, timeline emptied, timeline thinned, filter panes tabs), each emptied check presses its clear filter. RED against the pre-KIN-28 web/js+css: 6 NO, e.g. 'waterfall emptied  : NO - shown true, "no calls to lay out"', 'path 0 of N match  : NO - ... visit 1: "nothing recorded" / "nothing", "0 of 2 match" was due', 'timeline emptied   : NO - shown false, "no entries", 2 markers drawn'; 172 passed, exit 1. RED timeline emptiness on entries.length (markers): 'timeline emptied   : NO - shown false ... 2 markers drawn', 177 passed. RED waterfall tab offered on state.transactions: 'filter panes tabs  : NO - before network,path,all,waterfall,...; during network,path,all,cache,...'. GREEN 178 passed, exit 0. ci.yml UI 171->178; budgets 2735->2743 by hand. Layout (sticky notes, empty above timeline) not probe-checkable in jsdom; seen in headless Chrome at 1440 and 400 against the fixture, four filters. Ready for QA.

QA (second verify panel, head e1711a3): AC2-6 Verified. AC1 Failed at scale: filter-note counts come from the 1000-row clamp (3016-call session: a 1200-call match draws no waterfall note, a note reads '996 calls hidden' where 3012 are, a no-match filter reads '1000 calls hidden'). Filed as KIN-34. Related empty-text and layout defects found on the same pass: KIN-36 (waterfall empty text, includes the brushed-stretch-plus-filter case) and KIN-33 (timeline filter note covers the sticky prefix bar; station click lands under the sticky box). AC1 stays unticked; ticket stays In Progress.

QA (third verify panel, head a86dde2): AC1 Verified; AC2-6 were verified earlier. All six ACs now ticked. Not closed: the item closes together.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at a86dde2 (third panel; AC2-6 at e1711a3); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
