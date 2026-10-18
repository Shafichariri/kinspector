---
id: KIN-32
title: >-
  Signals bug: a reconnect after an unplanned drop neither re-reads nor clears
  the whole-session copies
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 21:27'
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
- [ ] #1 An unplanned drop marks the daemon away, and the next open clears and re-reads both whole-session copies
- [ ] #2 A render-web-ui.js probe line drives a real drop (not the stubbed stop): red without the fix, green with it
- [ ] #3 web-ui.md and the comment at 05-network.js:221 are true
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
