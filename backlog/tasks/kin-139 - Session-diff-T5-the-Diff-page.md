---
id: KIN-139
title: 'Session diff T5: the Diff page'
status: To Do
assignee: []
created_date: '2026-10-22 19:26'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-137
  - KIN-138
  - KIN-135
  - KIN-66
  - KIN-70
  - KIN-124
  - KIN-102
  - KIN-103
  - KIN-104
  - KIN-105
  - KIN-106
  - KIN-107
  - KIN-108
modified_files:
  - inspector-daemon/src/main/resources/web/js/96-diff.js
  - inspector-daemon/src/main/resources/web/css/88-diff.css
  - inspector-daemon/src/main/resources/web/scripts.txt
  - inspector-daemon/src/main/resources/web/styles.txt
  - inspector-daemon/src/main/resources/web/index.html
  - inspector-daemon/src/main/resources/web/js/08-routes.js
  - scripts/lib/diff-probes.js
  - scripts/lib/diff-render-checks.js
  - scripts/lib/shell-checks.js
  - scripts/lib/shell-cases.js
  - scripts/render-web-ui.js
  - scripts/render-web-ui-archive.js
  - scripts/check-web-static.js
  - .github/workflows/ci.yml
  - structure/budgets.txt
  - docs/decisions/web-ui.md
type: feature
ordinal: 139000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR B (lane W), cut after PR A merges, after detection T5 (KIN-124) and polish PR A (KIN-102 to KIN-108). Design: session-diff note §9.1, §9.3, §12 T5 and §14 (the web-ui.md paragraph). The page draws the daemon's comparison and decides nothing; no mirror block, no case file. Rail order is Traffic, Stats, Findings, Diff, Sessions.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #/diff, #/diff?a=…, #/diff?a=…&b=… parse and round-trip (route parse); a PAGES row diff with live: true and a rail link between Stats and Sessions; rail is navigation passes
- [ ] #2 #/diff with no ids shows the latest run against its previous one and is not rewritten
- [ ] #3 The table draws the daemon's order, kinds, loud mark and verdicts; an unknown direction shows the daemon's why and no removed; n<5 is printed from the reply (check-web-static.js diff draws kinds, diff unknown verbatim, diff verdict verbatim, diff stale dropped, each red as in the design's §12)
- [ ] #4 The live probe's diff lines from diff default pair to diff same session, plus diff restored, pass on the CI fixture, including diff long session whole (1201 calls removed) and diff truncated unknown; the Pace-rule red of the next probe's guard is in the hand-over
- [ ] #5 The page does not re-read on a live message; a recording B shows the note and refresh
- [ ] #6 In an export the Diff rail link is hidden and #/diff lands on Traffic (diff hidden in export); 1a's export lines still pass
- [ ] #7 At 1440, 1000, 600 and 400 px the document does not scroll sideways on #/diff (shell <w> page diff fits); QA renders 1440 and 400 with an open detail row, with screenshots
- [ ] #8 UI_EXPECT_CHECKS and ARCHIVE_EXPECT_CHECKS raised in the same commit; index.html and render-web-ui.js budgets raised by hand with reasons; fetch still called once across scripts.txt's files; nothing new under 11 px
- [ ] #9 The web-ui.md paragraph of the design's §14 is in
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
