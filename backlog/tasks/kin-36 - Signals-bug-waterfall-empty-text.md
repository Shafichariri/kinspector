---
id: KIN-36
title: 'Signals bug: waterfall empty text'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 36000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Simon, Doug), head e1711a3. (S2) A brushed stretch plus a filter says 'no calls were recorded in this session' (45-visits.js:207-210); this is Doug's scale-free repro of KIN-28 AC1. (S3) tag:screen says 'nothing matches the filter' though 6 screens matched; it should say 'no calls match'. (S6) 20-navigation.js:57 repeats the removed false claim. Nits: (a) renderListEmpty's doc comment is orphaned (25-traffic-list.js:214-224). (b) web-ui.md:166-168 should name hiddenByFilter as a third reader of state.signals.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The text order is: a filter with hidden calls gives the filter note; then a time range with calls in the session gives the stretch text; then 'nothing recorded' appears only when the session has no calls
- [x] #2 The tag:screen text is 'no calls match'
- [x] #3 The comment at 20-navigation.js:57 is true
- [x] #4 Each case is in the render probe, red then green
- [x] #5 Nit: renderListEmpty's orphaned doc comment (25-traffic-list.js:214-224) is reattached or removed
- [x] #6 Nit: web-ui.md:166-168 names hiddenByFilter as a third reader of state.signals
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). AC1: renderWaterfall's emptied branch (45-visits.js) asks in order: state.filter && hidden calls in range -> filterNote; else state.timeRange && the session has calls (sessionTotal, else allTransactions.length) -> 'no calls in the selected stretch'; else no calls in the session -> 'no calls were recorded in this session'; else 'no calls match' / 'no calls to lay out'. The stretch used to be asked of state.transactions (the filtered calls), which is how filter + empty stretch fell through to 'nothing recorded'. AC2: filterNote takes a lead (default 'nothing matches'); the waterfall passes 'no calls match', so tag:screen reads 'no calls match the filter tag:screen — 16 calls hidden — clear filter'. AC3: 20-navigation.js comment now 'The waterfall says itself that no calls match the filter.' AC5: renderListEmpty's doc comment moved from above clearFilterButton to above renderListEmpty. AC6: web-ui.md names hiddenByFilter as the third reader of state.signals; the filter-note paragraph records the waterfall's order and wording. AC4, live probe, three lines (probeWaterfallText in scripts/lib/filter-probes.js; the stretch set via window.setTimeRange, the function the brush calls, since a brush needs layout): RED at e1711a3's web code, 179 passed, 3 failed, exit 1: 'waterfall stretch  : NO - "no calls were recorded in this session", where "no calls in the selected stretch" was due'; 'waterfall order    : NO - "nothing matches the filter path:/__inspector_probe_matches_nothing__ — 16 calls hidden — clear filter", where the filter note was due' (red on wording at the base); 'waterfall calls    : NO - "nothing matches the filter tag:screen — 16 calls hidden — clear filter", where "no calls match the filter tag:screen …" was due'. Order proven separately on the fix by swapping the first two branches: 'waterfall order    : NO - "no calls in the selected stretch", where the filter note was due', 181 passed, exit 1. GREEN: 182 passed, exit 0; archive 26. ci.yml UI 179->182; budgets render-web-ui.js 2744->2748 by hand with reason; :repo-checks:test --rerun green.

QA (third verify panel, head a86dde2): AC1-6 Verified. Not closed: the item closes together.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at a86dde2 (third panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
