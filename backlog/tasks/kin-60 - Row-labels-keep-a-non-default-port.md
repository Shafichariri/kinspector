---
id: KIN-60
title: Row labels keep a non-default port
status: In Progress
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 04:19'
labels:
  - 'item:row-label-port'
dependencies: []
ordinal: 60000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:row-label-port in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Roadmap criteria (docs/ROADMAP.md, item:row-label-port):
1. Both labels (SessionRepository TimelineEntry.of, ExportMarkdown timeline table) show host:port when the port is not the scheme's default, the bare host when it is or when port is null.
2. No third copy of the rule: one helper, built on defaultPortFor, shared by both call sites.
3. A daemon test with calls on :8080 and :9090 asserts two different labels in MCP timeline and in the Markdown table, and :443 over https has no port; red with either old line put back; lives in the split test files.
4. The label still has no query.
Out of scope: web UI and overlay rows; any other change to the label's shape.

Plan (no architect on this row, so the builder places the helper): add NetworkTransaction.hostPort to :inspector-model (Transaction.kt), next to url, and make url use it too, so url and both labels read one rule. New public declaration in a published module with no twin and no golden file, as the item allows. Both daemon call sites use txn.hostPort. Model test in commonTest for hostPort; daemon tests in mcp/SignalToolsTest (timeline) and ExportTest (Markdown). schema.md lists hostPort among derived properties. Prove red by restoring each old line.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Helper: NetworkTransaction.hostPort in :inspector-model (Transaction.kt), on defaultPortFor; url is now built on it too, so the rule exists once. Both daemon labels (SessionRepository TimelineEntry.of, ExportMarkdown timeline row) use it. New public declaration in a published module with no twin or golden, as the item allows.
Tests: SignalToolsTest.timeline_labels_keep_a_non_default_port_and_drop_the_default_one (:8080, :9090 with a query, :443 https, null port), ExportTest 'the timeline table keeps a non-default port and drops the default one', PortTest hostPort case. Red: each old line put back alone fails exactly its surface's test (1 of 35 each); hostPort without the default check fails 3 model + 2 daemon tests. Green: daemon 270/0 (268 + 2).
<!-- SECTION:NOTES:END -->
