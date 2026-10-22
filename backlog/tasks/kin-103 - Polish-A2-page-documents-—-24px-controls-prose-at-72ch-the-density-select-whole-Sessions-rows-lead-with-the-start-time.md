---
id: KIN-103
title: >-
  Polish A2: page documents — 24px controls, prose at 72ch, the density select
  whole, Sessions rows lead with the start time
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:web-ui-polish'
dependencies:
  - KIN-102
ordinal: 103000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The note's §9 A2 criteria hold, each guard shown red
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A2 criteria:
1. At 1440 and 400 every visible .btn on Stats, Sessions, Processes and Settings is at least 24 px tall (polish <w> page targets).
2. At 1440 every .settings-note is at most 72ch (polish 1440 prose width).
3. At 1440 and 400 the density select shows comfortable whole (polish <w> density whole).
4. At 400 and 1440 every Sessions row shows its start time whole, first, with the app ellipsized after it. The errors cell reads 0 when there are none (session row time, polish <w> session time whole).
5. shell <w> page <name> fits stays green at 1440, 1000, 600 and 400.
6. The picker paragraph's appended sentence (§8) is in.
Files: web/css/00-tokens.css, 07-shell.css, 55-duplicates.css, 75-sessions.css, web/js/90-sessions.js, scripts/lib/polish-checks.js, polish-probes.js, polish-cases.js, scripts/render-web-ui.js, .github/workflows/ci.yml, structure/budgets.txt, docs/decisions/web-ui.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (bob). --prose-max 72ch; .page-doc .btn { min-height: var(--target-min) } and .page-doc .settings-note, .page-doc .page-prose { max-width: var(--prose-max) } in 07-shell.css beside .page-actions; .settings-field select { width: auto; min-width: 84px } in 55-duplicates.css; Sessions label = span.session-row-time (flex none) + span.session-row-app (ellipsis), label flex gap 6px; errors cell .session-errors reads 0 muted.
Static (122 -> 124): page targets css red with the rule removed: 'NO - found no .page-doc .btn rule, so this checked nothing'; prose width css red with the cap removed: 'NO - .page-doc .settings-note does not take max-width from var(--prose-max)'.
Live probe (render-web-ui.js 263 -> 264, UI_EXPECT_CHECKS raised): session row time, wired from lib/shell-probes.js probeSessionsPage so render-web-ui.js does not grow (budget 2354 untouched). Red with [app, when] order: 'NO - 2026-01-01T09-00-00_probe_ci_debug leads with session-row-app "com.example.projectx", not the time "11:00 Jan 1"; ...'; red with errors blank: 'NO - 2025-12-31T09-00-00_probe-long_ci_debug has no errors and its cell reads ""'. Pace rule, same run: 'sessions folder: NO - the note says ...' (262 passed, 2 failed), restored. Archive 48 -> 48.
Chrome: polish 1440/400 page targets (27 buttons), polish 1440 prose width (9 notes, widest 544 = 72ch), polish 1440/400 density whole ('comfortable' 112px for 99px), polish 1440/400 session time whole. Red in one run with the rule removed, the cap removed, width 84px back and the order reversed: 7 failed ('page-sessions preset-keep 19px', 'widest 1232px against a 72ch of 544px', '82px for 99px', 'com.example.projectx not first'). Empty scans fail ('NO - found no ...'). shell <w> page <name> fits green at 1440/1000/600/400.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #150 merge 4343823; item stays open. Chrome: 'polish 1440/400 page targets: at least 24px (27 buttons on 4 pages)' (base short); 'polish 1440 prose width: at most 72ch (9 notes, widest 544px against a 72ch of 544px)' (base wider than 72ch); density select '"comfortable": 112px for 99px' at 1440 and 400, red re-run with width: 84px put back reads 'cut ("comfortable": 82px for 99px)'; 'session time whole: whole, first (2 rows)' at 1440 and 400; live probe 'session row time : yes (2 rows lead with their start time; 1 error-free read 0)', render-web-ui.js 264 passed, 0 failed, 1 n/a matching ci.yml 264; shell <w> page <name> fits unchanged at all four widths; web-ui.md:707 carries the picker sentence. measure-web-ui.js --only polish,room,shell --base: base 80 readings 19 failed, head 80 readings 0 failed, only the 19 polish readings moved. Verdict: close yes.
<!-- SECTION:FINAL_SUMMARY:END -->
