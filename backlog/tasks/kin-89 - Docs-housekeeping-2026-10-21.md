---
id: KIN-89
title: Docs housekeeping (2026-10-21)
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 18:15'
updated_date: '2026-10-22 06:42'
labels:
  - 'item:docs-housekeeping-2026-10-21'
dependencies: []
ordinal: 89000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Batch 1 of the order decided 2026-10-21. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. Built without QA before merge; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:docs-housekeeping-2026-10-21 in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Part 1 (docs) in PR #124: INTEGRATION.md v49/v50 headings lose '(this document)', no version bump (not consumer-affecting); AGENTS.md structure-pass rows S-D/S-F/S-H/S-I merged into one row 'S' (phases A to I, STATUS.md for PRs and red repo pushes), 14,990 -> 14,606 bytes. Remaining: (1) .claude/agents/architect.md still says :inspector-noop-ui has no golden file; should name api/inspector-ui-public-api.txt; waits on maintainer approval. (2) Sessions page shows ~/.inspector/sessions whatever --data is; web lane, needs a probe line proven red. Also seen, out of this item's scope: root AGENTS.md Architecture and Production safety still say the ui pair has no golden file.
architect.md edit approved by the maintainer on 2026-10-21: the noop-ui line now names api/inspector-ui-public-api.txt. Remaining: the Sessions page ~/.inspector/sessions line (web lane).

2026-10-22 (tracker): not closed. Part 1 (PR #124) and the architect.md edit (PR #130, approved by the maintainer 2026-10-21) are merged; the Sessions page ~/.inspector/sessions line (web lane) remains.

2026-10-22 (bob): Sessions page part built on branch shafic/sessions-page-data-dir, not committed. The note's path is <code id=sessions-dir>, '<data dir>/sessions' until known; loadSessionsDir() (js/90-sessions.js), called from enterPage('sessions') (js/08-routes.js), fills it with GET /api/mcp's dataDir + '/sessions' (McpInfo.dataDir = DaemonConfig.dataDir; sessionsDir = dataDir.resolve("sessions")). No daemon change, no new route, read through api(). Export: enterPage returns early and the page is hidden (archive probe 'pages hidden in export', 'routes fall back in export' pass), so it names none. Probe: 'sessions folder' in scripts/lib/shell-probes.js, truth from GET /api/config path with config.json -> sessions (a different route from the page's). Red with the original index.html line put back: 'NO - the note says "Every run of the app is a folder under ~/.inspector/sessions. Deleting…"', 262 passed, 1 failed; green 263. Pace rule: 'clear all arms' red with Clear all firing on its first click (stubbed), restored. ('deletes disabled' reads 0 of 0 on the fixture, so it cannot go red there.) UI_EXPECT_CHECKS 262 -> 263. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), whole item, no blocking findings; QA round 1 had no failures. Part 1, PR #124 merge 6c1cb32: `grep -n "^### .*(this document)" docs/INTEGRATION.md` gives only `### v62 ... (this document)` (line 1346), no version bump; AGENTS.md 14,606 bytes, and AgentGuideTest red at 15,000 and 15,007 ("AGENTS.md is 15000 bytes; the budget is under 15000"), green at 14,999; every fact dropped from the S rows is in docs/STATUS.md; CLAUDE.md and .cursorrules are mode 120000 symlinks. architect.md, PR #130 merge e071594 (approved by the maintainer 2026-10-21): line 31 now reads `:inspector-noop-ui ... (golden: api/inspector-ui-public-api.txt)`. Sessions page, PR #140 merge 95b4de4, Chrome at 1440 and 400: `#sessions-dir` reads `<scratch>/custom data/sessions` with --data (same folder the daemon names), `<scratch>/home/.inspector/sessions` by default, hidden in an export; probe line `sessions folder` green on head (263 passed, 0 failed, 1 n/a) and red against 84a1747's page (`NO - the note says "Every run of the app is a folder under ~/.inspector/sessions..."`, 262 passed, 1 failed, exit 1). check-web-static.js 116/0; :repo-checks:test --rerun green. Not verified: a relative --data shows a relative path (the daemon's own value); push runs on main were in progress when QA looked.
<!-- SECTION:FINAL_SUMMARY:END -->
