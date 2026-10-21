---
id: KIN-66
title: 'Rail, hash routes and the four pages; dialog contents moved; controlFetch'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:03'
labels:
  - 'item:web-foundation-shell'
dependencies: []
ordinal: 66000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 route lines, rail is navigation, control header once, moved probe lines green with before/after counts stated
- [ ] #2 Export shows Traffic only and falls back
- [ ] #3 Row 4's switch probes green through the picker and through the hash route
- [ ] #4 render-web-ui.js no longer than its budget, budget tightened
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B (web), built in the working tree on shafic/web-foundation-shell-b, not committed. Ready for QA once committed. Red proofs, run by the engineer and restored: route parse (session not decoded), control header once (header back in 75-replay.js), rail is navigation (input in #rail), route default (pages not hidden), route pages (href from label), route back (navigate replaces), route view (tab without navigate), route session (router sets sessionId), route switch window and now switch shows none (followSession removed), pages hidden in export, routes fall back in export (live ignored).
- New: js/08-routes.js (PAGES, parseRoute, routeHash, navigate, applyRoute, renderRail, setRailMode, writeHash), css/07-shell.css. controlFetch in js/05-network.js is the only writer of the control header (control header once).
- Deviation: sessionLabelParts, sessionLabel, paintPickerFace and showSessionLabel live in js/90-sessions.js, not 15-session-data.js: the latter would have reached 614 lines. switchSession is there too.
- Deviation: writeHash falls back to location when the history API refuses a file: page (jsdom does, for every export). Without it routes fall back in export failed and tab clicks in an export threw.
- Probe move: probeSessions, probeTopBar, the settings/peers block and probeEndpointLimit moved to scripts/lib/shell-probes.js; render-web-ui.js 2758 -> 2561 lines, budget tightened.
- Counts: render-web-ui.js 204 -> 219 passing; archive 38 -> 40; check-web-static.js 90 -> 96.
<!-- SECTION:NOTES:END -->
