---
id: KIN-108
title: 'Polish A7: the contact sheet harness'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 09:31'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 108000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The note's §9 A7 criteria hold
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A7 criteria:
1. node scripts/measure-web-ui.js --only sheet --out <dir> writes a PNG per page and view (every PAGES page, Traffic list/timeline/path, #/txn) at 1440, 1000, 600 and 400, dark and light, live and exported, plus an index.html grid of them.
2. Its lines are note()s, and every judged group's count is unchanged.
3. scripts/AGENTS.md's measure-web-ui.js row names polish and sheet.
4. QA attaches PR A's sheet.
Files: scripts/lib/sheet-cases.js (new), scripts/measure-web-ui.js, scripts/AGENTS.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (bob). measure-web-ui.js --only sheet --out <dir> (opt-in, not in the default list; refuses to start without --out). Shots: list, detail (500 row opened), txn, timeline, path, stats, sessions, processes, settings; at 1440/1000/600/400, dark/light, served and exported (export: the five it offers). 72 served + 48 exported PNGs + index.html grid. With --base each tree writes to <out>/head and <out>/base. Rows are notes (ok null): '--only sheet' printed 'head: 5 readings, 0 failed', all five '--'. Needed a screenshot() on the CDP page (scripts/lib/measure-cdp.js, +6), not in the note's file list. scripts/AGENTS.md row names sheet and polish. Run: node scripts/measure-web-ui.js --only sheet --out <dir> took about 2 min on the probe fixture.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #150 merge 4343823; item stays open. 'node scripts/measure-web-ui.js --only sheet --out <dir>' wrote 120 PNGs (served dark 36, served light 36, export dark 24, export light 24) at 1440, 1000, 600 and 400; index.html references all 120, none missing (checked by script). Served covers 9 views, exported 6 (an export offers Traffic and Stats only, by design). 'head: 5 readings, 0 failed', all five are note()s; no judged group's count moved. scripts/AGENTS.md names polish and sheet. QA attached the sheet in the run folder (qa/web-ui-polish-part1-sheet/index.html) for the maintainer. Verdict: close yes.
<!-- SECTION:FINAL_SUMMARY:END -->
