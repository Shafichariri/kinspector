---
id: KIN-58
title: 'Web: a live call reaches the timeline, the glance and the picker'
status: Done
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 14:18'
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
- [x] #1 Every acceptance criterion of the roadmap item labelled item:web-live-calls-reach-every-pane in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
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

QA round 1 (at 952b6c6, PR #113): criterion 4 FAILED, the other five Verified. After a pause, two calls while paused and a resume, the header and the glance read N+2 while the selected picker option and the face read N until the next live call for that session. Reproduced in real Chrome; red proof m2 (followLiveCount removed) also red. No new ticket was filed, by the maintainer's instruction (another session holds unmerged ticket numbers); the finding is recorded here, and it was fixed inside this item by PR #117 (433f30d). QA round 2 (at 433f30d): fixed and Verified at 1440, 1000 and 400 px; the check live paused still is red against 952b6c6's setLiveTail ('header total 18 (was 16), option 23 calls'), green at head.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Done 2026-10-21 on QA's two rounds (qa/web-live-calls-reach-every-pane-report.md), PRs #113 (952b6c6) and fix #117 (433f30d). Round 1 failed criterion 4 only: after pause, two calls, resume, the picker option and face lagged the header (118 against 120); the finding is recorded here, with no separate ticket. Round 2 at 433f30d, close yes. AC1 / the six roadmap criteria: (1) real Chrome at 1440, 1000, 400: a live call is drawn by +500 ms, scrolled-back scrollTop unchanged 1421 to 1421, at the end it follows; base does not draw it; red with drawLive removed (m1: 'socket call drawn: NO', 'live timeline plain: NO'). An open run staying open is checked in jsdom only. (2) 100 calls over about 1.2 s cost 5 renderTimeline calls and 0 /api/sessions reads, bound ceil(T/250)+1; red with a redraw per call (m3: 'live draw burst of 100: NO - 100 timeline and 100 glance redraws, bound 7'). (3) glance calls = header, no filter (14) and under status>=500 (16 against '4 of 16 calls'), 116 = 116 after the burst, 122 = 122 after reconnect. (4) round 2: header, glance, option and face all read 120 at 159 ms after the resume, at every width; check red against 952b6c6 ('live paused still: NO - header total 18 (was 16), option 23 calls'); m2 'live picker count: NO' with followLiveCount removed. (5) paused and other-session calls moved nothing ([118,118,118,118,119] before and after). (6) no filter evaluated in web/js, no new route. Counts at 433f30d: check-web-static.js 99/0, render-web-ui.js 225 passed 0 failed 1 n/a, render-web-ui-archive.js 40, :repo-checks:test --rerun green; PR #117 build, release-swap, repo and web pass; check-commit-identity.sh 4e9d363 433f30d passes; client-name scan no match. DoD1 per the table; DoD2 by m1 to m3 and the round-2 revert; DoD3 by QA's scan. Not verified: Safari, Firefox, a phone; the full ./gradlew build locally (no Android SDK; CI ran it); the main push CI run on 433f30d was queued when QA looked.
<!-- SECTION:FINAL_SUMMARY:END -->
