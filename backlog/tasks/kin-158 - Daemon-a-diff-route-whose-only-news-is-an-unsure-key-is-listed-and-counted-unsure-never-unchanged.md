---
id: KIN-158
title: >-
  Daemon: a diff route whose only news is an unsure key is listed and counted
  unsure, never unchanged
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 10:38'
updated_date: '2026-10-23 12:09'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob, 2026-10-23, from designs/diff-unsure-key-shown.md T1 and decision 2026-10-23-diff-unsure-whole-vs-none (condition (b) in).
- SessionDiff.route sets DiffRoute.unsure (written only when true) and nowhere else: a direction with an unsure key, or compared false with one side's kind json and the other's unknown. Both unknown, both empty and wide JSON on both sides are not unsure.
- DiffCounts.unsure (no default). counts.unchanged = SessionDiff.unchanged (no change and not unsure); the five sum to routeCount. view() with unchanged=false drops only SessionDiff.unchanged routes.
- ORDER: an unsure route with no change ranks KINDS.size, unchanged KINDS.size + 1.
- DiffMarkdown: Routes line names 'N with unknown keys'; section '## Routes with unknown keys' between Routes removed and Unchanged routes, with a block per route whose Changes line reads 'none the diff can be sure of: ...', never 'none'.
- MCP description's last sentence changed; mcp-tools-list.json regenerated. CLI USAGE line. DAEMON.md section 7, daemon-and-mcp.md paragraph, exports.md sentence.
- Assertions moved on purpose (condition (b) makes the fixture's feed unsure): DiffRouteTest [7,1,4,2] -> [7,1,4,1,1]; DiffMarkdownTest Routes line; DiffToolTest and DiffCliTest 'every route has a change' -> 'a change or unsure'.
- Tests: QA's pair in DiffTestSupport (qaPair, writeQaPair under its own app id, latest untouched). Eleven breaks each shown red; see the hand-over.
- Between this and KIN-159 the page still hides the feed behind its toggle and its summary prints four counts summing to 13 of 14 on base->probe; live probe 325/325 and archive 61/61 stay green.

Merged: PR #202, merge e1b2d2711e1222e754b1694f7b201ae877e75ec7 (head bf61557; build, release-swap, repo, web pass by name; main push CI run 39949586195 all success). Ready for QA.
<!-- SECTION:NOTES:END -->
