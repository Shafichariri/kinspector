---
id: KIN-56
title: 'Web: the list says when it holds only part of what it counts'
status: In Progress
assignee: []
created_date: '2026-10-20 20:25'
updated_date: '2026-10-21 07:12'
labels:
  - 'item:web-list-says-what-it-holds'
dependencies: []
ordinal: 56000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:web-list-says-what-it-holds in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (engineer, no team). Written into the ticket once it reached main mid-build (PR #101 merged after work began); the plan itself was fixed before code.

Criteria, copied from docs/ROADMAP.md (item:web-list-says-what-it-holds), with the check for each and how it is shown red:

1. Filtered header numbers the matches from the route's `matched`: `newest 1000 of 1200 matches shown`; `1000 of 3016 calls` never appears.
   Files: web/js/15-session-data.js `renderCounts`. Checks: `filtered count` (check-web-static.js, 3016-call clamped stub), `long filtered count` (live probe, 1203-call session under `path:/v3/long/bulk`, 1201 matched). Red: the new branch disabled, header reads `1000 of 3016 calls` / `1000 of 1203 calls`.
2. The list draws the same `newest N of M matches loaded` note through `filterNote`. Files: web/js/25-traffic-list.js `renderListNote`, index.html `#list-note` (outside the listbox), css/35-list.css (sticky at --sticky-top), 20-navigation.js `applyView`. Checks: `list note past the clamp` (static), `long list note` (live). Red: note never drawn / kept hidden.
3. An empty brushed stretch with calls below the oldest loaded never reads `no calls in the selected stretch`; says they are older than what the list loaded and how to reach them; "at least" below where the whole copy is whole. Files: 25-traffic-list.js `olderThanLoaded`, `renderListEmpty`. Checks: `stretch older than loaded` (static, no filter, under a filter, and an "at least" case), `long stretch older` (live). Red: old wording put back; "at least" removed.
4. Boundary row: not built; the note and the stretch text meet criteria 2 and 3 (architect's choice was the engineer's here).
5. Export offline: `long list note absent` in render-web-ui-archive.js; existing `long stretch oldest` stays green. Red: export edited to show the note.
6. List still draws at most 1000, daemon clamp unchanged: no change to LIST_CEILING or any Kotlin.
Pace rule: neighbouring `long count no filter` re-proven red in the same run.

Fix round (QA report qa/web-list-says-what-it-holds-report.md, two failures):
1. Criterion 3, a regression: under a filter, olderThanLoaded counted the unfiltered whole copy. A stretch where nothing matched read "500 calls … narrow the filter to reach them". It now claims no count under a filter: "none of the newest N of M matches the list loaded is in this stretch, and the K older ones are not loaded — narrow the filter to reach any here". A stretch the copy shows no calls in reads "no calls in the selected stretch". Check: stretch older than loaded (static: 5 stretches, including a non-matching one and an empty one) and long stretch older (live, filtered case added). Both are red against fb8e54e's code.
2. Criterion 1: the filtered header now holds under a stretch: "400 of the newest 1000 of 1200 matches shown". Check: filtered count (static: no stretch, whole stretch, partial) and long filtered count (live, whole-session stretch). Both are red against fb8e54e's code ("1000 of 3016 calls").
Pace rule: long list note was re-proven red in the same live run.
<!-- SECTION:NOTES:END -->
