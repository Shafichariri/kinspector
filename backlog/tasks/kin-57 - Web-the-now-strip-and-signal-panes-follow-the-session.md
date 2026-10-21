---
id: KIN-57
title: 'Web: the now strip and signal panes follow the session'
status: Done
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 10:50'
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
- [x] #1 Every acceptance criterion of the roadmap item labelled item:web-now-strip-follows-session in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
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

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #107, merge 6804c94, base 1da0d75; head and base daemons, headless Chrome, plus four mutated copies of scripts/ and web/ run against one head daemon. (1) Switch window: live probe 'now switch shows none: yes' (no strip rows, no timeline signals, glance 'loading this session...'); red m1 (followSession returns at once) 'NO - while ...'s signals were held, the strip lists state CartViewModel'; Chrome picker path at 1440 and 400 and delete path (afterSessionChange) show no previous-session value; base reproduces the leak. (2) 'now newest read decides: yes' and 'now load read overtaken: yes'; red m2 (ticket check removed) both NO 'the older read landed last'; Chrome: later-started read wrote qa.delta-s8. (3) 'now failed read kept: yes' with 'now failed read empty: yes'; red m3 'NO - the strip lists nothing, where its 10 were held'. (4) 'now late reply dropped: yes (... 2 of <long>'s own)' vs base 0; red m4 NO; no n/a ^now line; ProbeFixtureTest passes. (5) Only Kotlin change is the fixture; check-web-static.js 90 checks 0 failed. Counts base->head: live probe 200->204 passed, 1 n/a (facet filter), archive 38, static 90; ci.yml 200->204 matches. Each mutant failed only its own lines. daemon and repo-checks tests green with --rerun. Not run locally: full Gradle build (no Android SDK). Older defects, not filed: in the switch window the timeline call rows and endpoint chips still show the session just left (true at base; item scope excludes calls; relevant to 1a); path, waterfall and tag browsers are not redrawn by followSession (by inspection only).
<!-- SECTION:FINAL_SUMMARY:END -->
