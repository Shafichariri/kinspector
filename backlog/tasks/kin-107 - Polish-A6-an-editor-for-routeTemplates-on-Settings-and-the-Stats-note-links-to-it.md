---
id: KIN-107
title: >-
  Polish A6: an editor for routeTemplates on Settings, and the Stats note links
  to it
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 19:58'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 107000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The note's §9 A6 criteria hold, each guard shown red
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A6 criteria:
1. Settings shows 'route templates' with the file's list, one per line, and 'running: N templates'.
2. An unchanged save sends no routeTemplates. A changed one sends the trimmed non-blank lines in order, through controlFetch. The daemon's 400 is shown verbatim (routes editor, red with the key always sent).
3. The Stats note links #/settings live and names config.json in an export (stats note link, stats note export).
4. At 1440 and 400 Settings does not scroll sideways.
5. docs/DAEMON.md names the editor. The routes paragraph of §8 is in.
Pace rule: chip marks active re-proven red with the new shared-page lines present; stats offline drawn re-proven red for the archive line.
Files: web/js/80-settings.js (82-route-templates.js only if 80 would pass 600, §1.12), web/index.html, web/css/40-detail.css, web/css/07-shell.css (the textarea's width, beside .config-row), scripts/lib/polish-probes.js, scripts/render-web-ui-archive.js, .github/workflows/ci.yml, docs/DAEMON.md, docs/decisions/web-ui.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (part 3b). Ready for QA as patch 3 of 3 (A4, A5, A6) under the run's scratch; not committed.
Done: the routes editor in 80-settings.js (427 lines, so no 82-route-templates.js): routeTemplatesRender adds a 'route templates' config-row, textarea#route-templates (6 rows, monospace, spellcheck off, placeholder /articles/{slug}) holding file.routeTemplates one per line, 'running: N templates' from effective.routeTemplates, and the muted line 'One path per line, first match wins; {name} matches one segment. Applies when the daemon restarts.' routeTemplatesPatch sends the trimmed non-blank lines in order, only when they differ from the file's list, through configPatch -> controlFetch; a 400 shows in #config-status verbatim as before. Width: '.config-row textarea.route-templates' in css/07-shell.css (flex 1 1 240px, min-width 0). Stats note (index.html, 597 lines): '.live-only' link to #/settings, '.archive-only' words naming config.json; css/40-detail.css hides .live-only under body.archive and .archive-only outside it. docs/DAEMON.md routeTemplates section names the editor; web-ui.md routes paragraph (note §8) in.
Checks: live 'routes editor' and 'stats note link' (scripts/lib/polish-probes.js), archive 'stats note export' (wired in render-web-ui-archive.js, body in polish-probes.js). render-web-ui.js untouched (2354, its budget).
Red proofs: 'routes editor: NO - a retention save sent {"maxSessions":101,"routeTemplates":[]}' (configPatch sending the key always); 'stats note link: NO - the Stats note has no link to #/settings' (span removed); 'stats note export: NO - the Settings link's display is inline, the export's words' inline' (.live-only taken out of the hide list, the same edit made in the tree and the export). Pace: 'chip marks active : false' with all new lines present; archive Pace: 'stats offline drawn: NO - 0 rows of 13 ... "not in this export: ..."' with the /stats branch taken out of archiveSource and 'stats note export' present. All restored.
Chrome: 'shell <w> page settings fits' fits at 1440, 1000, 600 and 400. Note: at 1440 the box runs the row's full remaining width (about 1000px), as §3 P9 asks.
Final: ci.yml UI_EXPECT_CHECKS 304, ARCHIVE_EXPECT_CHECKS 59 (main 299/58).
<!-- SECTION:NOTES:END -->
