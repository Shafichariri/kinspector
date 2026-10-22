---
id: KIN-135
title: 'Session diff T4: a baseline run in the probe fixture'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:25'
updated_date: '2026-10-22 20:01'
labels:
  - 'item:session-diff'
dependencies: []
modified_files:
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ProbeFixture.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ProbeFixtureTest.kt
type: feature
ordinal: 135000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR A (lane D), first of its tickets. Design: session-diff note §11 and §12 T4. Adds BASE_SESSION_ID 2025-12-30T09-00-00_probe_ci_debug to writeProbeFixture, older than the long and probe sessions, with one route per diff rule against the probe session (unchanged config, token with a redacted response and no X-Request-Id, a truncated feed, products/42 with price a number, orders/submit twice with 200, stock five times at 80 ms, a legacy-banner route the probe lacks). Daemon tests only; no web file.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 writeProbeFixture writes 2025-12-30T09-00-00_probe_ci_debug with the design's §11 rows and replaces it on a re-run; latest still names the probe session
- [ ] #2 ProbeFixtureTest asserts each §11 fact through SessionRepository, shown red with the feed row's resBodyTruncated dropped
- [ ] #3 Every existing probe and measure-web-ui.js reading is unchanged against the three-session fixture, stated in the hand-over
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once PR A merges. ProbeFixture.kt: BASE_SESSION_ID 2025-12-30T09-00-00_probe_ci_debug (baseSessionCalls), the oldest of four sessions; ProbeFixtureTest.the_base_session_holds_each_difference_the_diff_reads asserts each design §11 fact through SessionRepository, and that the base session has no marker, signal or finding. Red: the feed's resBodyTruncated dropped -> 'the base feed is not marked truncated'. Probes against the four-session fixture on port 18310: render-web-ui.js 299 passed, 0 failed, 1 n/a (facet filter, also n/a on main's CI run of PR #178); render-web-ui-archive.js 58 passed, 0 failed; only the note 'session options' moved, 3 -> 4. measure-web-ui.js --base main: see the PR A hand-over. -- bob
<!-- SECTION:NOTES:END -->
