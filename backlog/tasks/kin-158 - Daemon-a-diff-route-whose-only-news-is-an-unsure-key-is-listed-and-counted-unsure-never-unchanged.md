---
id: KIN-158
title: >-
  Daemon: a diff route whose only news is an unsure key is listed and counted
  unsure, never unchanged
status: To Do
assignee: []
created_date: '2026-10-23 10:38'
labels:
  - 'item:diff-unsure-key-shown'
dependencies: []
ordinal: 158000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap row 31, lane D (T1 of designs/diff-unsure-key-shown.md). Ordered 2026-10-23 by the product owner; the architect's note decides a per-route flag, a count of its own and the route kept listed; the maintainer's proxy answered Q1 yes (2026-10-23-diff-unsure-whole-vs-none): a direction one side kept whole JSON for and the other kept no whole body also counts as unsure. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label, narrowed to the daemon's surfaces; the page is the W ticket with the same label. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With QA's pair (A two complete bodies holding $.price; B one complete body without it and one truncated), the default reply on REST, MCP diff_sessions and inspector diff lists the route with unsure: true and counts.unsure 1, and never reads as no change (counts.changed 0 and no routes)
- [ ] #2 A route whose direction one side kept whole JSON for and the other kept no whole body (the fixture's /v3/shop/feed) carries unsure: true; both-unknown, both-empty and wide JSON do not
- [ ] #3 counts.unsure is written, counts.unchanged no longer includes unsure routes, and added + removed + changed + unsure + unchanged == routeCount; unchanged=false drops only the routes counted unchanged
- [ ] #4 Unsure routes with no change sort after every changed route and before every unchanged one, so limit cuts in the right place
- [ ] #5 A route with a removed key still reads removed, never unsure; a route that changed and holds unsure keys is counted changed and carries unsure: true
- [ ] #6 The diff's JSON only gains fields; diff.md has a 'Routes with unknown keys' section and the Routes line names them; the MCP description says unsure routes are always listed, and its golden is regenerated
- [ ] #7 Each new guard is shown red on each daemon surface
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
