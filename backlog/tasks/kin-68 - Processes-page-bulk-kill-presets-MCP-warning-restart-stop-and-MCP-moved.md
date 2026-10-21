---
id: KIN-68
title: 'Processes page: bulk kill, presets, MCP warning, restart/stop and MCP moved'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
labels:
  - 'item:web-foundation-shell'
dependencies:
  - KIN-64
  - KIN-66
ordinal: 68000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 peer presets, processes bulk, self has kill btn green
- [x] #2 Stop/restart lines green on the Processes page
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Processes page (#page-processes, js/85-processes.js): This daemon (pid/port/up/data line, restart, stop; ids kept), the process table with checkboxes, peerPresets (pure: servers, others, stale; never self, never mcp), kill selected (n) and the row kill both through confirmThen and one POST /api/peers/kill via controlFetch (confirm() is gone), per-item results, the MCP warning when an mcp row is ticked by hand, and the MCP section moved from the dialog.
Lines: peer presets (static), processes bulk, self has kill btn, stopArms, stopped, noRestartOffered, lastCapture, recovers (all on the Processes page now). The stubs catch both kill routes.

Fix round (QA, qa/web-foundation-shell-report.md): the per-pid kill results were wiped about 300 ms after they were drawn, because the post-kill reload (loadPeers) cleared #peers-status. loadPeers now takes { keepStatus }, and the post-kill reload passes true; Refresh and opening the page still clear the status. Check: processes bulk now answers pid 9003 as noSuchProcess and reads the status after the reload. It is red against a7e3946's 85-processes.js ('after the reload the status read ""'). The Pace rule's neighbour, self has kill btn, was re-proven red in the same run.

QA round 1 (a7e3946) FAILED the criterion 'processes bulk' on this ticket: killPeers drew 'signalled N of M' and the per-pid list into #peers-status, then setTimeout(loadPeers, 300), and loadPeers began with setPeersStatus(''), so the results were gone by +1650 ms. Reproduction: tick a peer, let it exit before the click, kill selected twice; +150 ms 'signalled 0 of 1 - pid 42777 - noSuchProcess', +1650 ms empty. The probe read the status before the reload and could not see it. Recorded here, not as a separate ticket. Fixed in PR #114 (cc9dbce); QA round 2 verified it.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 (a7e3946) verified everything but one defect: the per-pid kill results were wiped about 300 ms after they showed (setTimeout(loadPeers, 300) with loadPeers clearing #peers-status); at +150 ms 'signalled 0 of 1 - pid 42777 - noSuchProcess', at +1650 ms empty. Fixed in PR #114 (cc9dbce): loadPeers({keepStatus}), the post-kill reload passes true. QA round 2 (qa/web-foundation-shell-report.md): same Chrome reproduction, pid 51109 SIGKILLed before the click, status still 'signalled 0 of 1 - pid 51109 - noSuchProcess' at +1650 ms after the reload. 'processes bulk' now stubs one signalled and one noSuchProcess, waits 700 ms and requires both; red with a7e3946's 85-processes.js: 'after the reload the status read ""' (224 passed, 1 failed); head 225 passed 0 failed 1 n/a. Round 1 also verified: presets never tick self or MCP (peer presets, red with MCP in stale), MCP warning on a hand tick, first click sends nothing and the second one POST /api/peers/kill {pids:[42552],force:false} that killed only my peer, restart/stop/MCP moved to Processes.
<!-- SECTION:FINAL_SUMMARY:END -->
