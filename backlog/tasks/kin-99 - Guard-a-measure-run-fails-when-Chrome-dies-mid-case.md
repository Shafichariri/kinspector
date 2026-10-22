---
id: KIN-99
title: 'Guard: a measure run fails when Chrome dies mid-case'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 05:13'
updated_date: '2026-10-22 05:49'
labels:
  - 'item:guard-measure-chrome-exit-fails'
dependencies: []
ordinal: 99000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-22 by the maintainer on the product owner's proposal. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:guard-measure-chrome-exit-fails in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, 2026-10-22). Criteria, copied from docs/ROADMAP.md item:guard-measure-chrome-exit-fails:
1. When Chrome exits or its DevTools socket closes during a case, every pending command fails with an error naming the case and the cause, and the run ends with a non-zero exit within seconds. It never prints a reading as passed.
2. The runner stops the daemons and the Chrome it started on that path, as it does on success.
3. Proven by killing Chrome during a case: before the fix the run hangs, after it the run exits.
Plus, asked by the coordinator: a per-send deadline. The roadmap puts a live-Chrome deadline out of scope unless it comes with a number the slowest case (first rows on 160,000 calls) is shown to meet, so the number is measured on a full run including --only first and reported.

Files: scripts/lib/measure-cdp.js (Chrome exit after start-up and each page socket's close reject every pending send with case + cause; a send after that rejects at once; per-send deadline; the browser-target socket gets the same; page.close bounded). scripts/measure-web-ui.js only to name each page's case (onPage label) and to refuse a page's rows when its Chrome was lost, so no reading prints as passed; cleanup path (finally: chrome.close, daemons killed) is already there and is checked for orphans with pgrep after the red run.
onNewDocument kept as is (KIN-88).
Proof: red = kill Chrome mid-case on origin/main code (hangs, killed by hand after a bound), and on the fix (exits non-zero within seconds, names case, no chrome/daemon left). Green = --only traffic and --only layout,run,live; slowest send measured on a full run incl. first.

Built (bob, PR #141); ready for QA. Red on origin/main: Chrome SIGKILLed 8 s into --only traffic left the run alive 60 s later and five daemons running once it was killed by hand. Fixed: same kill exits 2 after 1 s, 'head traffic: Chrome exited (signal SIGKILL) ...', nothing left running; 4 s into --only layout,run,live: 'head layout big: Chrome exited (signal SIGKILL) while Runtime.evaluate waited for its answer'. Socket closed with Chrome alive: rejected in 259 ms (red without onclose: 30 s deadline, lost unset). Deadline: 30 s; slowest send in a full run incl. first = 718 ms (287 readings, 0 failed); red with no deadline: hung at 60 s. Swallowing case: exit 2; red without the onPage lost check: printed 'ok SWALLOWED' and exit 0. Green: --only traffic 10/0 failed, --only layout,run,live 182/0 failed, check-web-static 116/0, repo-checks pass. Not verified: Linux, a real wedged renderer, Ctrl-C on the runner itself (its finally does not run; out of scope).
<!-- SECTION:NOTES:END -->
