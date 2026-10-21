---
id: KIN-58
title: 'Web: a live call reaches the timeline, the glance and the picker'
status: In Progress
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 13:38'
labels:
  - 'item:web-live-calls-reach-every-pane'
dependencies: []
ordinal: 58000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:web-live-calls-reach-every-pane in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (engineer, no team; no architect, so the burst shape is the engineer's choice and is stated here). Written into the ticket after the first build pass rather than before it, because the session was handed this item mid-run; the plan itself is what was built.

Criteria, copied from docs/ROADMAP.md (item:web-live-calls-reach-every-pane), with the check for each and how it is shown red:

1. No filter, live on: a call arriving while the timeline is shown is drawn without any other action; the follow rule holds. Files: web/js/05-network.js — the `txn` branch calls `drawLive()`, one timer that redraws `renderTimeline()` and, while no call is open, the glance. Checks: `socket call drawn`, `socket run stays open`, `socket at end timeline` (now with a call too) in check-web-static.js; `live timeline plain` in the live probe. Red: the `drawLive()` call removed.
2. A burst does not redraw once per call without bound. Shape: a trailing throttle, `LIVE_DRAW_MS` = 250, the first call arms it and later ones ride on it, as `LIVE_REREAD_MS` does. Bound: a burst of 100 calls over T ms costs at most ⌈T/250⌉+1 timeline redraws and as many glance redraws — five over one second. Check: `live draw burst of 100` (100 calls, one per 10 ms, through the real handler). Red: a redraw per call (105 against a bound of 7).
3. The glance's `calls` row equals the header's session count after live calls, with and without a filter; hosts and slowest include them. Same `drawLive`. Checks: `live glance plain`, `live glance filter`. Red: `drawLive()` removed.
4. The selected session's picker label states the header's count after live calls. Files: web/js/90-sessions.js — new `followLiveCount()` sets the selected session's `txnCount` from `sessionTotal`, rewrites that option's text and calls `paintPickerFace()` (1a's one writer of the face). No `/api/sessions` re-read, so no request to throttle. Check: `live picker count` (option and face). Red: `followLiveCount()` removed.
5. Paused, or a call for another session: none of the three moves. Both return before the branch. Checks: `live paused still`, `live other still`. Red: count and draw moved above those returns.
6. No filter evaluated in web/js, no new route: no Kotlin change.
Pace rule: neighbouring `live header plain` re-proven red in the same live run (renderCounts removed from the call branch).

Fix round (QA, qa/web-live-calls-reach-every-pane-report.md, criterion 4): after pause, live calls, resume, the header read 120 while the picker option and face stayed at 118. setLiveTail(true) now calls followLiveCount once its loadTransactions lands, for the same session. That is sessionTotal, the header's number; no /api/sessions read. Check: live paused still sends a second call while paused, resumes, and requires the option and face to end with the header's count. It is red against 952b6c6's setLiveTail (header 18, option 23 calls). The neighbouring live picker count was re-proven red in the same run.
<!-- SECTION:NOTES:END -->
