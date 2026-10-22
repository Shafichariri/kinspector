---
id: KIN-101
title: 'Web: a burst of live signals or markers redraws once, not per message'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 06:18'
updated_date: '2026-10-22 06:41'
labels:
  - 'item:web-live-signal-burst-bounded'
dependencies: []
ordinal: 101000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 23 (§3 → Bugs found). Acceptance criteria are the roadmap item's own (docs/ROADMAP.md, heading carrying this label); the engineer copies them into the plan. Route live signals and markers through the existing 250 ms drawLive timer, with the per-signal /current read; proven with the committed live-traffic driver. The plan says whether the two payload reads ride the same timer, and why.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:web-live-signal-burst-bounded holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Acceptance criteria (docs/ROADMAP.md, item:web-live-signal-burst-bounded, copied):
- A burst of 100 signals over T ms costs at most ⌈T/250⌉+1 redraws of each of the timeline, the waterfall and the path, and at most that many `/current` reads. A burst of markers costs at most that many redraws of the timeline and the path. These are the bound calls already meet.
- A single signal or marker is still drawn within 250 ms, and the timeline keeps its follow rule.
- The existing `socket at end timeline` lines for markers and signals wait for the throttled draw and still pass. A new burst line is proven red with the per-message redraws put back.
- When paused, or for another session, nothing moves, as for calls.
Out of scope: changing LIVE_DRAW_MS; web UI only, no public API, no twin, no golden.

Build:
1. web/js/05-network.js: `drawLive({ path, signal })` records what the one armed redraw owes; `drawLiveNow` is the tick. A call owes timeline + glance (unchanged). A marker owes the path. A signal owes the waterfall, the path, one `/current` read and the payload reads of the pane showing them. The marker branch keeps renderMarkers/renderList per message; the signal branch keeps renderTabs per message (a tag's first signal makes its tab reachable).
2. Reads at the tick only for the session the signals arrived on, while it is read (`!sessionPending()`) and live tail is on: a `/current` read after a switch inside the window would put the new session's values in the strip while every pane says "being read".
3. web/js/00-core.js: `liveDraw: { timer, path, signals }`.

DECISION, the two payload reads (path view's screen payloads, the tag browser's payload): they ride the same timer. Each payload is its own request, so riding saves no request; what it saves is the redraw each answer made (the path redrew per screen on landing as well as per signal; the browser redrew per signal) and `loadScreenPayloads` re-asking for every screen still in flight. At the tick the path waits for its screen payloads and the browser for its payloads, and each is drawn once: drawing the path before and again after would cost two redraws per tick and break the bound. Cost: a screen on the path view lands after its payload read (milliseconds on loopback). Recorded once, in docs/decisions/web-ui.md, in the drawLive paragraph.

Proofs:
- jsdom (check-web-static.js, scripts/lib/follow-checks.js): `live signal burst timeline`, `live signal burst path`, `live marker burst`, `live signal still` (paused, other session, switch before the redraw reads no /current); `socket at end timeline` now waits for the redraw for markers and signals.
- Chrome (measure-web-ui.js --only traffic, scripts/lib/live-traffic-cases.js, real daemon through /ingest; ingest-client gains `marker`): `traffic signal burst`, `traffic marker burst`, `traffic lone signal drawn`, `traffic lone marker drawn`, `traffic paused still`, `traffic other still`; each fails on an empty scan, printing its counts.
- Red with the per-message branches put back; green after.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob on shafic/web-live-signal-burst-bounded (cut from 95b4de4). Not committed; ready for QA once committed.

Proven red, then green (each restored and seen green):
- jsdom, base 05-network.js put back: `live signal burst timeline: NO - 100 signals arrived in 1335 ms: 100 timeline, 100 waterfall and 100 path redraws, 100 /current reads, bound 7`; `live signal burst path: NO - … 110 path redraws …`; `live marker burst: NO - 50 markers arrived in 1134 ms: 50 timeline and 50 path redraws, bound 6`.
- jsdom, drawLive taken out of the signal and marker branches: `socket at end timeline: NO - marker oldest 29195 -> 29195, signal oldest 29195 -> 29195, …` (the line now waits for the throttled draw).
- jsdom, the tick's reads not filtered by session/liveTail/sessionPending: `live signal still: NO - switched before the redraw: 1 /current reads`. With the liveTail return removed: `NO - paused: redraw armed true, 3 redraws and reads`.
- jsdom, signals never admitted: `live signal burst timeline: NO - found no signal arriving, so this checked nothing (0 signals arrived …)`.
- Chrome (`measure-web-ui.js --only traffic`), base 05-network.js: `FAIL traffic signal burst: over bound (100 signals over 1192 ms: 100 renderTimeline, 100 renderWaterfall, 100 renderPath, 100 current, at most 6 each …)`, `FAIL traffic marker burst: over bound (50 markers over 1076 ms: 50 renderTimeline, 50 renderPath, at most 6 each …)`. liveTail return removed: `FAIL traffic paused still: moved (… 1 renderTimeline, 1 renderWaterfall, 1 renderPath …)`. Bursts sent on the other session: `FAIL traffic signal burst: checked nothing (found no signal arriving (100 for other sessions): 0 renderTimeline, …)`, same for markers.

Green: check-web-static 116 -> 120 checks, 0 failed. render-web-ui.js on the CI fixture at 8102: 263 passed, 0 failed, 1 n/a (unchanged, no line added to the shared page, UI_EXPECT_CHECKS stays 263). Archive: 48 passed. measure --only traffic,live,layout --base origin/main (85e9552): 192 readings a side, head 0 failed, base 2; moved: only `traffic signal burst` and `traffic marker burst` (over bound -> within bound). :repo-checks:test --rerun: 36 tests, 0 failures.

Head Chrome: 100 signals over 1178 ms: 5/5/5 redraws, 5 /current reads (bound 6); 50 markers over 1074 ms: 5/5 (bound 6). Lone signal 268–279 ms and lone marker 270–278 ms from socket arrival to timeline row (the 250 ms timer plus the render and a 5 ms poll); base 2–3 ms.

Found, not changed (out of scope): the call branch still calls renderWaterfall and renderPath per call, so the roadmap's "the bound calls already meet" holds for the timeline and the glance only. The marker branch keeps renderMarkers/renderList per marker and the signal branch renderTabs per signal.
<!-- SECTION:NOTES:END -->
