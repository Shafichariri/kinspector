---
id: KIN-32
title: >-
  Signals bug: a reconnect after an unplanned drop neither re-reads nor clears
  the whole-session copies
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-28
type: bug
ordinal: 32000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (Doug) on KIN-24 AC6, head e1711a3. ws.onclose (web/js/05-network.js:203-216) never moves serverState off 'running', so wasAway (:218) is true only after this page's own stop or restart. Live repro: under tag:screen, after a drop and a real redial, the page stays stale with no new tab until the filter is cleared. docs/decisions/web-ui.md and the comment at 05-network.js:221 claim otherwise. The probe reaches onopen only through the stubbed stop, so it never saw this. Harness: /private/tmp/claude-501/-Users-shafichariri-development-tools-kinspector/bc83ec9e-65d6-4a86-9585-442f1d7fec8a/scratchpad/c2/blip.js. Fails KIN-24 AC6.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 An unplanned drop marks the daemon away, and the next open clears and re-reads both whole-session copies
- [x] #2 A render-web-ui.js probe line drives a real drop (not the stubbed stop): red without the fix, green with it
- [x] #3 web-ui.md and the comment at 05-network.js:221 are true
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer). ws.onclose (05-network.js) moves serverState from 'running' to the new 'away' when the drop was not asked for, so the next onopen's wasAway is true and it runs forgetWholeSession() + the re-read; 'stopped' and 'restarting' are untouched. 00-core.js documents 'away'; the comment above wasAway now says 'a reconnect after a drop or a blip', which is what the code does. Probe: new live line 'drop refreshes' (scripts/lib/filter-probes.js, third gap in probeWholeSessionRefresh): under tag:screen it calls the page's own socket onclose, waits for the page's own redial (connectLive after 1 s; the stub socket opens itself), and checks a signal under a new tag got a tab and the busiest endpoint chip counted one more. Nothing fires onopen by hand. RED against e1711a3's 05-network.js: 'drop refreshes     : NO - tab for 'probe-dropped' false, /stock counts 4 where 5 was due, under tag:screen', 178 passed, 1 failed, exit 1. GREEN: 'drop refreshes     : yes (a tab for 'probe-dropped', and /stock counts 5, under tag:screen)', 179 passed, exit 0; 'stopped' and 'recovers' still yes. Archive 26 passed. Doug's c2/blip.js now gets into the wasAway branch (it throws 'loadTransactions is not defined' from its own stub of loadSessions().then, i.e. after forget ran; at e1711a3 it printed serverState running and no forget). ci.yml UI 178->179; budgets render-web-ui.js 2743->2744 by hand. web-ui.md: the stale-copies paragraph says a plain drop marks the daemon away and names the three probe lines. :repo-checks:test --rerun green.

QA (third verify panel, head a86dde2): AC1-3 Verified. Not closed: the item closes together.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at a86dde2 (third panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
