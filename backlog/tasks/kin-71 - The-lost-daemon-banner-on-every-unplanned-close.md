---
id: KIN-71
title: The lost-daemon banner on every unplanned close
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-66
ordinal: 71000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Banner visible after a first drop, a second drop after a reconnect and a restart that never returns; cleared on reconnect
- [x] #2 banner lines red with the running check back; recovers re-proven red (Pace rule)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). js/05-network.js: every close that is not a stop sets away and paints the lost banner, except a restart younger than RESTART_GRACE_MS (20 s, state.restartAt set in requestRestart, cleared in onopen). Lines banner every drop, banner after failure, banner restart lost replay the page's own handlers on the Processes page beside the stop probe; recovers re-proven red (Pace).
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR B, a7e3946, qa/web-foundation-shell-report.md), Chrome with real process deaths. AC1: first drop (SIGKILL) 'Lost the daemon - reconnecting...' and cleared on return; a second drop banners again; a restart that never returns reads 'Restarting...' at +5 s and 'Lost the daemon - reconnecting...' at +24 s, past the 20 s grace; a deliberate two-click stop shows 'daemon stopped' with no restart button. AC2: m2 (running check put back) gives 'banner after failure: NO' and 'banner restart lost: NO - a restart 60 s old still reads Restarting...'; 'banner every drop' stays yes, matching the design's finding that a plain drop never reproduced the bug. Caveat: the 'recovers' re-proof is the engineer's record in PR #112's body; QA did not repeat it.
<!-- SECTION:FINAL_SUMMARY:END -->
