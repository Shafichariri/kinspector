---
id: KIN-45
title: >-
  Web: with no filter, a list past its ceiling counts as 'newest 1000 of N
  shown', and the page asks for its own 1000
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 10:06'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies:
  - KIN-44
modified_files:
  - inspector-daemon/src/main/resources/web/js/15-session-data.js
  - scripts/lib/session-state-checks.js
  - scripts/lib/long-session-probes.js
  - scripts/render-web-ui-archive.js
  - docs/decisions/web-ui.md
  - .github/workflows/ci.yml
type: feature
ordinal: 45000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Part of item:web-transactions-paged (docs/ROADMAP.md, "The web traffic list stops at 1000 calls and does not say so"), the count criterion: a count never reads as a filter that is not there; with no filter set, `1000 of 4210 calls` does not appear. The maintainer approved a labelled list ceiling on 2026-10-19 (ROADMAP §5, "Is a labelled 1000-call ceiling acceptable?": yes): the list may draw the newest 1000 calls provided the count says so in words that cannot be read as a filter, such as `newest 1000 of 4210 shown`. KIN-42 is not part of this item.

Read first: docs/decisions/web-ui.md ("The web UI keeps an unfiltered copy of the session, and two features depend on it." and the filter-note paragraph "A pane a filter emptied or thinned says so, names the filter and offers `clear filter`.").

The change, all in `inspector-daemon/src/main/resources/web/js/15-session-data.js`:
- `loadTransactions` asks the transactions route for `limit=1000`, not `2000`, as one named top-level constant (for example `LIST_CEILING`; `check-web-static.js`'s "one declaration per name" audit must stay green). The ceiling is then the page's own number rather than the daemon's clamp happening to match it, and the export draws the same: once KIN-43 slices by `limit`, `limit=2000` would draw 2000 rows offline against 1000 live. The daemon's clamp is unchanged; `routePages` is not touched.
- `renderCounts`: with no filter, no brushed stretch, and fewer calls shown than `state.sessionTotal`, the header reads `newest ${shown} of ${total} shown`, with the noun in its `title` (for example "the list draws the newest 1000 calls; the session holds 4210"). Every other case reads as today: `16 of 16 calls` for a whole session, and under a filter or a stretch the existing text, since KIN-34's filter note already says `newest N of M matches loaded`. The rule the checks hold: with no filter, `#counts` never matches `(\d+) of (\d+) calls?` with unequal numbers.
- A live tail can grow the list past 1000 (`newest 1043 of 4253 shown`), which is true; trimming or virtualising the list is out of scope.

The proof.
- Static, in `scripts/lib/session-state-checks.js`: `no-filter count` over `clampPage` (the 3016-call stub clamped as the daemon clamps), loaded with no filter, asserts `#counts` reads `newest 1000 of 3016 shown`. `clampPage` replaces `renderCounts` with a no-op after loading the files, so this check must call the real one.
- Live and archive, on the 1203-call session KIN-44 adds, in `scripts/lib/long-session-probes.js` and `scripts/render-web-ui-archive.js`: `long count no filter` asserts `#counts` reads `newest 1000 of 1203 shown`, the same text in both. The existing `countsNoun` line on the 16-call probe session (`/ of \d+ calls?$/`) stays green.

Check counts. `UI_EXPECT_CHECKS` and `ARCHIVE_EXPECT_CHECKS` in `.github/workflows/ci.yml` change in the same commit as the checks.

docs/decisions/web-ui.md, in the paragraph "The web UI keeps an unfiltered copy of the session":
- The sentence "The list itself still stops at the clamp; that is `item:web-transactions-paged`." is replaced. The new text says the list draws the newest 1000 calls by the page's own limit, so an export draws the same; that with no filter the header says `newest 1000 of 4210 shown`, because `1000 of 4210 calls` is the shape a filter produces and sent readers looking for one; and that `no-filter count` and `long count no filter` hold it.
- In the paragraph "No row array is spread into an argument list.", the sentence "Holding that many rows at all is a cost this does not settle; `item:web-transactions-paged` owns whether the whole-session read is capped." stays open. Only its owner changes, to KIN-42, where the maintainer deferred it on 2026-10-19. The whole-session read stays uncapped in this item.
Keep each decision in one place: `DecisionsTest` fails on a second copy.

The item gets a release-notes line because it changes what a user sees on a long session (ROADMAP); that line is not written in this ticket.

No public API, no schema, no change to the security boundary.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 loadTransactions in web/js/15-session-data.js asks for limit=1000 through one named top-level constant; the daemon's clamp and routePages are unchanged
- [x] #2 With no filter and no stretch, renderCounts reads 'newest N of T shown' (noun in the title) whenever fewer calls are shown than state.sessionTotal; a whole session still reads 'N of N calls'; counts under a filter or a stretch are unchanged
- [x] #3 check-web-static.js reports 'no-filter count' yes: the 3016-call clampPage stub loaded with no filter reads 'newest 1000 of 3016 shown', through the real renderCounts
- [ ] #4 render-web-ui.js and render-web-ui-archive.js each report 'long count no filter' yes on the 1203-call session: 'newest 1000 of 1203 shown', the same text live and offline; countsNoun on the probe session stays yes
- [x] #5 Red proof: with the old renderCounts restored, 'no-filter count' and both 'long count no filter' lines report NO; with limit=2000 restored, the archive's 'long count no filter' reports NO; restored, all yes
- [x] #6 docs/decisions/web-ui.md: 'The list itself still stops at the clamp; that is item:web-transactions-paged.' is replaced by the list ceiling and its count wording, naming both checks; the cap sentence ('... owns whether the whole-session read is capped.') stays open with its owner changed to KIN-42; DecisionsTest passes
- [x] #7 UI_EXPECT_CHECKS and ARCHIVE_EXPECT_CHECKS in .github/workflows/ci.yml change in the same commit as the checks
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (5cd2ca3). Red: old renderCounts -> 'no-filter count' NO ("1000 of 3016 calls"), live and archive 'long count no filter' NO ("1000 of 1203 calls"); limit=2000 -> archive 'long count no filter' NO ("1203 of 1203 calls"), live stays yes (daemon clamps). Restored: static 72/0, live 185, archive 35. Deviation: render-web-ui.js touched (not in modified_files) to print the long probe's labelled lines in a loop; net zero lines, budget unchanged at 2752. Ready for QA.

2026-10-19, KIN-48 (maintainer decision: an export draws every embedded call): AC#4's "the same text live and offline" is amended. Offline reads "1203 of 1203 calls"; live reads "newest 1000 of 1203 shown". The archive's 'long count no filter' expects the former; the live probe keeps the latter.

Built in cd198c4 (KIN-48): readLongSession takes { exported }; the archive's 'long count no filter' reads yes on "1203 of 1203 calls", the live probe's still expects "newest 1000 of 1203 shown".
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit 5cd2ca3. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). 'no-filter count' yes in static; red proofs (old renderCounts, limit=2000) re-run in round 2. AC#4 left unticked as written: it was amended by KIN-48 (offline reads '1203 of 1203 calls', live 'newest 1000 of 1203 shown'); the amended lines pass in the live and archive probes. AC#7 ticked for local counts only; CI not run.
<!-- SECTION:FINAL_SUMMARY:END -->
