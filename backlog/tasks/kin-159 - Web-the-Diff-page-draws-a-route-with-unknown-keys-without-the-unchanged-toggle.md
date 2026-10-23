---
id: KIN-159
title: >-
  Web: the Diff page draws a route with unknown keys without the unchanged
  toggle
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 10:38'
updated_date: '2026-10-23 12:41'
labels:
  - 'item:diff-unsure-key-shown'
dependencies:
  - KIN-158
ordinal: 159000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap row 31, lane W (T2 of designs/diff-unsure-key-shown.md), for the web developer. Depends on the daemon ticket with the same label, which adds DiffRoute.unsure and counts.unsure. The page reads the daemon's flag and decides nothing. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label, narrowed to the page. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With the toggle off, a route with unsure: true is drawn, its change cell reads 'unknown keys' (diff-tag data-kind=unsure, with the design's title), and the page never reads as no change for it
- [ ] #2 The page hides only the rows the daemon counts unchanged (changes empty and not unsure); the toggle counts with the same predicate
- [ ] #3 The summary names the daemon's counts.unsure ('N with unknown keys') between changed and unchanged
- [ ] #4 Static 'diff unsure shown' and live 'diff unsure shown' (base against probe, the feed row) are shown red with the old predicate; 'diff truncated unknown' no longer needs the toggle and a neighbouring line is re-proven red (Pace rule); CI's static and live counts move
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. 96-diff.js: hide predicate and toggle count become changes empty and not r.unsure (the daemon's SessionDiff.unchanged); an unsure row's change cell draws diff-tag data-kind=unsure 'unknown keys' with the design's title instead of 'no change'; the summary names counts.unsure as 'N with unknown keys' between changed and unchanged.
2. diff-render-checks.js: static 'diff unsure shown' (toggle off: row drawn, tag, summary, toggle counts plain rows only); canned report() gains counts.unsure.
3. diff-probes.js: live 'diff unsure shown' (base->probe feed row drawn without the toggle, tag 'unknown keys'); 'diff explicit pair' expects the unsure count; 'diff truncated unknown' drops the toggle click.
4. web-ui.md Diff paragraph sentence; ci.yml live count.
5. Red proofs: old predicate, unsure flag ignored, count not drawn; Pace neighbour 'diff truncated unknown'.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on shafic/diff-unsure-key-page from e1b2d27, uncommitted. 96-diff.js (512->526): diffUnchanged = changes empty and not r.unsure, used for the hide and the toggle count; r.unsure draws diff-tag data-kind=unsure 'unknown keys' with the design's title (after any kinds, so a changed+unsure route carries both; 'no change' only when neither); summary prints counts.unsure as 'N with unknown keys' between changed and unchanged. Static 'diff unsure shown' (diff-render-checks.js), live 'diff unsure shown' (diff-probes.js, base->probe, feed); 'diff explicit pair' expects the unsure count; 'diff truncated unknown' no longer presses the toggle. web-ui.md Diff paragraph. ci.yml UI_EXPECT_CHECKS 325->326. Counts: static 145->146 (not pinned in CI), live 325->326 (1 n/a, facet filter, as on main), archive 61->61.
Red proofs. Static: old predicate -> 'NO - with the toggle off, rows drawn /v1/changed /v1/both; /v1/changed /v1/both /v1/feed due; the feed's unsure tag reads "nothing", title ""; the toggle reads "show 3 unchanged routes", "show 2 unchanged routes" due'; flag ignored -> 'NO - the feed's unsure tag reads "nothing", title ""; the feed's change cell reads "no change"; /v1/both tags responseShape, responseShape unsure due'; count not drawn -> 'NO - the summary reads "... 2 changed, 4 unchanged", the daemon's 2 with unknown keys due'. Live: old predicate -> 'diff unsure shown: NO - toggle pressed false, "show 2 unchanged routes"; 12 rows where 13 were due; feed not drawn; the daemon flags GET /v3/shop/feed' (and diff truncated unknown NO, feed not drawn); count not drawn -> 'diff explicit pair: NO - ... 4 changed, 1 unchanged; the daemon counts 14 routes: 7 added, 1 removed, 4 changed, 1 with unknown keys, 1 unchanged'. Pace: shape cell title dropped -> 'diff truncated unknown: NO - feed shape "unknown" title "" ...', only that line red. measure --only shell,polish vs e1b2d27: nothing moved, 99/99 each.
<!-- SECTION:NOTES:END -->
