---
id: KIN-68
title: 'Processes page: bulk kill, presets, MCP warning, restart/stop and MCP moved'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 12:38'
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
- [ ] #1 peer presets, processes bulk, self has kill btn green
- [ ] #2 Stop/restart lines green on the Processes page
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
PR B, in the working tree (not committed). Processes page (#page-processes, js/85-processes.js): This daemon (pid/port/up/data line, restart, stop; ids kept), the process table with checkboxes, peerPresets (pure: servers, others, stale; never self, never mcp), kill selected (n) and the row kill both through confirmThen and one POST /api/peers/kill via controlFetch (confirm() is gone), per-item results, the MCP warning when an mcp row is ticked by hand, and the MCP section moved from the dialog.
Lines: peer presets (static), processes bulk, self has kill btn, stopArms, stopped, noRestartOffered, lastCapture, recovers (all on the Processes page now). The stubs catch both kill routes.

Fix round (QA, qa/web-foundation-shell-report.md): the per-pid kill results were wiped about 300 ms after they were drawn, because the post-kill reload (loadPeers) cleared #peers-status. loadPeers now takes { keepStatus }, and the post-kill reload passes true; Refresh and opening the page still clear the status. Check: processes bulk now answers pid 9003 as noSuchProcess and reads the status after the reload. It is red against a7e3946's 85-processes.js ('after the reload the status read ""'). The Pace rule's neighbour, self has kill btn, was re-proven red in the same run.
<!-- SECTION:NOTES:END -->
