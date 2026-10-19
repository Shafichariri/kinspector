---
id: KIN-43
title: 'Web export: the transactions route slices by offset and limit'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 10:05'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies: []
modified_files:
  - inspector-daemon/src/main/resources/web/js/05-network.js
  - scripts/render-web-ui-archive.js
  - .github/workflows/ci.yml
type: feature
ordinal: 43000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Part of item:web-transactions-paged (docs/ROADMAP.md, "The web traffic list stops at 1000 calls and does not say so"). Design approved by the maintainer on 2026-10-19. KIN-42 is not part of this item.

Read first: docs/decisions/exports.md ("The HTML export is the web UI, not a report, and one seam is what makes that hold.") and docs/decisions/web-ui.md.

The problem. `archiveSource` in `inspector-daemon/src/main/resources/web/js/05-network.js` (the transactions branch, about lines 61-66) returns every row as `{ total, matched, items: rows }`, whatever `offset` and `limit` it is asked for. Its signals branch, just below, already slices "as asked, with no clamp of its own", and its comment says why: an export that ignores `offset` answers a different question from the daemon. Until the transactions branch slices too, the archive half of the 1001-call probe (the ticket that depends on this one) cannot be checked against a real page walk.

The change. In the transactions branch, slice `rows` (already newest first) by `offset` and `limit` exactly as the signals branch does: `offset` defaults to 0 and is floored at 0; a missing `limit` means every row; no clamp of the export's own. `total` and `matched` stay `rows.length`. A `filter` is still refused with 400 and the existing message. The daemon's clamp (`SessionRepository.queryTransactions`, 1000 per page) is unchanged and out of scope. `routePages` is not touched.

The proof. `scripts/render-web-ui-archive.js` has a "the signal route, as the export answers it" section that asks the page's own `source` directly (`window.eval('source')`). Add the same four lines for the transactions route, compared with the embedded `archive.transactions` sorted newest first by `mono` in the probe, not by the page: `transactions sliced by limit`, `transactions limit past total`, `transactions offset past end`, `transactions filter refused`.

Check counts. CI pins how many checks pass: `ARCHIVE_EXPECT_CHECKS` in `.github/workflows/ci.yml` (26 at the time of writing) changes in the same commit as the new lines, by the number of lines added.

Optional, docs/decisions/exports.md: one clause may be added saying the transactions route slices by `offset` and `limit` as the daemon does. No existing sentence there changes.

No public API, no schema, no change to the security boundary.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 archiveSource's transactions route in web/js/05-network.js returns rows.slice(offset, offset + limit), newest first, as its signals route does: offset defaults to 0, a missing limit means every row, no clamp of its own; total and matched stay rows.length; a filter is still refused with 400
- [x] #2 render-web-ui-archive.js reports 'transactions sliced by limit', 'transactions limit past total', 'transactions offset past end' and 'transactions filter refused', each yes against the CI probe export, with the expected rows computed in the probe from the embedded archive.transactions sorted newest first by mono
- [x] #3 Red proof: with the branch put back to returning every row ({ total, matched, items: rows }), 'transactions sliced by limit' and 'transactions offset past end' report NO; restored, they report yes
- [ ] #4 ARCHIVE_EXPECT_CHECKS in .github/workflows/ci.yml is raised in the same commit by the number of check lines added, and the archive probe passes with it in CI
- [x] #5 The daemon's clamp (SessionRepository.queryTransactions) and routePages are unchanged
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built. archiveSource's transactions branch slices like its signals branch. Four archive probe lines added; ARCHIVE_EXPECT_CHECKS 26 -> 30. Red proof: branch put back to items: rows -> 'transactions sliced by limit' and 'transactions offset past end' NO (28 passed, 2 failed); restored 30 passed. Optional exports.md clause not added. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit 1d60505. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). Red proof re-run in round 2: branch returning every row -> 'transactions sliced by limit' and 'transactions offset past end' NO, restored yes. AC#4 left unticked: ARCHIVE_EXPECT_CHECKS was raised (26 to 30) but 'passes with it in CI' is not verified, CI has not run.
<!-- SECTION:FINAL_SUMMARY:END -->
