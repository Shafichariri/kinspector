---
id: KIN-48
title: 'Web export: an archive draws every embedded call'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 11:01'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies: []
type: bug
ordinal: 48000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the first verify panel (qa doug, architect charles-2, ux simon; base c21aaf0, head 0fbced1); all three agreed. A regression from KIN-43 together with KIN-45. Head's export of the 1203-call session draws only the newest 1000. Offline there is no filter, so calls #0-#202 cannot be opened. Brushing the oldest stretch reads "0 of 1203 calls" and "no calls in the selected stretch" while THIS STRETCH says 151. The path view says "older calls not loaded" in a file that holds them. Base's export drew all 1203 and showed 151. Fix: in loadTransactions, limit: String(ARCHIVE ? ARCHIVE.transactions.length : LIST_CEILING). KIN-43's slicing stays.
Decision, 2026-10-19 (maintainer): an exported HTML session over 1000 calls draws every embedded call (header "1203 of 1203 calls"), not the newest 1000. This amends the approved design's "the export draws the same list" rule.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The archive probe's "long count no filter" expects "1203 of 1203 calls".
- [x] #2 A new archive line "long stretch oldest" brushes the oldest 151 calls and expects 151 rows drawn. It is red with the archive asking for LIST_CEILING. ARCHIVE_EXPECT_CHECKS changes in the same commit.
- [x] #3 The chips and repeat lines are unchanged and still yes.
- [x] #4 The archive half of KIN-44 AC#4's red proof becomes inapplicable, which goes in KIN-44's notes; the live half stays.
- [x] #5 In docs/decisions/web-ui.md the clause "so an export, which answers whatever limit it is asked, draws the same list" becomes: "An export asks for every embedded call instead: it has no filter to reach past a ceiling, and at 1000 its oldest calls could not be opened at all, while a brushed stretch over them said they did not exist."
- [x] #6 (a) "long stretch oldest" is archive-only, in render-web-ui-archive.js. It brushes mono[0]..mono[150] of the long export through setTimeRange and expects "151 of 1203 calls" with 151 rows drawn. Red proof: with the export put back to LIST_CEILING it reads "0 of 1203 calls". ARCHIVE_EXPECT_CHECKS goes up in the same commit. It is not printed on the live probe, where it reads NO because of an older defect.
- [x] #7 (b) Replacement offline guard for the export's slicing: the archive probe asks the export's transactions route for offset=1000 (no limit, or limit >= 203) on the long export and expects exactly the 203 oldest rows. Red when the route slices with rows.slice(offset, limit) or ignores offset. Today's KIN-43 lines pass with that break, because none asks for a middle offset.
- [x] #8 (c) KIN-45 AC#4 ("the same text live and offline") is amended: offline reads "1203 of 1203 calls", live reads "newest 1000 of 1203 shown". Recorded in KIN-45's notes.
- [x] #9 (d) The archive half of KIN-44 AC#4's red proof is retired, with the reason (offline the copy is the list, so the pager is never called), recorded in KIN-44's notes. The live half stays.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in cd198c4. loadTransactions asks limit=String(ARCHIVE ? ARCHIVE.transactions.length : LIST_CEILING); LIST_CEILING's comment rewritten; KIN-43's slicing in archiveSource untouched. Probe: readLongSession(doc, calls, { exported }) -> archive 'long count no filter' expects "1203 of 1203 calls", live keeps "newest 1000 of 1203 shown". New archive-only lines in render-web-ui-archive.js: 'long stretch oldest' (setTimeRange mono[0]..mono[150], expects "151 of 1203 calls" and 151 rows) and 'long offset 1000 sliced' (source.fetch offset=1000&limit=1000 on the long export, expects the 203 oldest ids newest first). ARCHIVE_EXPECT_CHECKS 35 -> 37 in ci.yml. Red proofs (daemon on 8231 serving this tree's web/ ahead of the jar, export re-fetched each run): probe lines added, export still at LIST_CEILING -> 'long count no filter: NO - "newest 1000 of 1203 shown"', 'long stretch oldest: NO - "0 of 1203 calls", 0 rows drawn', 35 passed 2 failed; fixed -> 37/0. archiveSource rows.slice(offset, limit) -> 'long offset 1000 sliced: NO - 0 rows', 36 passed 1 failed (no other line catches it); rows.slice(0, limit) -> 'long offset 1000 sliced: NO - 1000 rows' plus 'transactions offset past end' NO, 35/2; restored 37/0. Chips and repeat lines yes throughout. web-ui.md clause replaced verbatim. Notes appended to KIN-44 and KIN-45. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit cd198c4. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). Archive probe 37/0 with the long export: 'long count no filter' reads '1203 of 1203 calls', 'long stretch oldest' and 'long offset 1000 sliced' yes; red proofs re-run in round 2. AC#4/#8/#9 are the recorded retirement/amendment of KIN-44 AC#4 (archive half) and KIN-45 AC#4, in those tickets' notes. Maintainer decision 2026-10-19: an export draws every embedded call.
<!-- SECTION:FINAL_SUMMARY:END -->
