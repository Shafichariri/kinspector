---
id: KIN-109
title: 'Guard: interrupting a measure run leaves its daemons running'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 07:40'
labels:
  - 'item:guard-measure-interrupt-cleans-up'
dependencies: []
ordinal: 109000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 16d. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:guard-measure-interrupt-cleans-up holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, branch shafic/scripts-guards-16d-16e). Criteria, copied from ROADMAP §3 → Guards, item:guard-measure-interrupt-cleans-up:
1. SIGINT or SIGTERM sent to a measure run mid-case ends it within a few seconds; afterwards no process whose command line names the run's temp directory is left, and the directory is gone unless --keep was given.
2. The run exits non-zero and says it was interrupted; it prints no readings as if they were a result.
3. Proven both ways with KIN-99's harness, signalling the run instead of killing Chrome: at base the daemons survive, on the change nothing survives. A --base run is included.
Out of scope: SIGKILL, render-web-ui.js and other scripts (a shared gap becomes a note in the hand-over), Windows.
Build: scripts/measure-web-ui.js gets SIGINT/SIGTERM handlers that run the same cleanup as the finally (memoised, so it runs once): close Chrome, stop each spawned daemon and wait for its exit (SIGKILL after a bound), remove the temp dir, print 'interrupted by <signal>', exit 130/143. Chrome spawned before launchChrome resolves is tracked too (scripts/lib/measure-cdp.js gains an onSpawn hook). main skips the readings and the stack trace once interrupted. Harness in the session scratchpad: start a run, wait for its Chrome, signal the node pid N s in, time the exit, list processes naming the temp dir, check the dir. Red at origin/main, green on the change, SIGINT and SIGTERM, head-only and --base.
<!-- SECTION:NOTES:END -->
