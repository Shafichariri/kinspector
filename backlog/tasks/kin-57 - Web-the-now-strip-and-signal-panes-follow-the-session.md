---
id: KIN-57
title: 'Web: the now strip and signal panes follow the session'
status: In Progress
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 04:19'
labels:
  - 'item:web-now-strip-follows-session'
dependencies: []
ordinal: 57000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:web-now-strip-follows-session in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (engineer, no team), fixed before code.

Criteria, copied from docs/ROADMAP.md (item:web-now-strip-follows-session), with the check for each and how it is shown red:

1. From the moment another session is selected until its own signals land, the now strip, the timeline's signal rows and the glance show none of the previous session's values. Files: web/js/15-session-data.js — new `followSession`, called first in `loadTransactions` (the picker's path) and in `afterSessionChange` (delete, clear); it clears `signals`, `allSignals`, `screens`, `current`, overtakes the signal load and every `/current` read in flight, and redraws the strip, the timeline and the glance. The glance draws a loading line, naming no values, until `loadSignals` has written the selected session (`state.signalSessionId`). Check: live probe `now switch shows none` holds the new session's `/signals` and asserts all three. Red: the clear removed; and each of the three kept in turn.
2. Of two `/current` reads for one session, the later-started decides. Files: one reader, `readCurrent`, used by `loadSignals` and `refreshCurrent`, with a sent/written ticket (`state.currentRead`) as `readSent`/`readWritten` do for the filtered reads. Checks: `now newest read decides` (two live reads, released newest first) and `now load read overtaken` (a load's read, then a live one). Red: the ticket check removed.
3. A failed `/current` on an unchanged session keeps the strip; after a switch it stays empty. Files: `state.currentSessionId` records whose values `state.current` holds; a failure keeps them only when they are the selected session's. Checks: new `now failed read kept`; KIN-51's `now failed read empty` stays green. Red: `catch(() => [])` put back.
4. The fixture's second session (the long one) gets current values of its own. Files: ProbeFixture.kt. `now late reply dropped` then asserts them; red with a mismatched reply written as `[]`; no `n/a` line.
5. No filter evaluated in web/js; no new route. Kotlin change is test-fixture only.
Pace rule: a neighbouring guard re-proven red in the same live run.
<!-- SECTION:NOTES:END -->
