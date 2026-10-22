---
id: KIN-107
title: >-
  Polish A6: an editor for routeTemplates on Settings, and the Stats note links
  to it
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 10:12'
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
PARKED for after v1.4.0 (maintainer's call, relayed by the coordinator), uncommitted on branch shafic/web-ui-polish-a456 over 7882f33. The coordinator commits.
Not started: no code for the routeTemplates editor, the Stats note link, 'routes editor', 'stats note link', 'stats note export', DAEMON.md or the §8 routes paragraph. The plan holds the criteria. Facts gathered: GET /api/config gives file.routeTemplates and effective.routeTemplates (ConfigRoutes.kt:69); PUT validates through ConfigFile.validateRouteTemplates, which refuses the whole list on any bad entry; 80-settings.js is 226 lines, plenty of room; the textarea's width rule belongs beside .config-row in css/07-shell.css.
<!-- SECTION:NOTES:END -->
