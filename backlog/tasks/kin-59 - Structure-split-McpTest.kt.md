---
id: KIN-59
title: 'Structure: split McpTest.kt'
status: In Progress
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-20 20:44'
labels:
  - 'item:structure-split-mcp-test'
dependencies: []
ordinal: 59000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Built in the 2026-10-20 run without an architect or QA before merge, by the maintainer's choice; QA verifies on main after merge. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. The engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:structure-split-mcp-test in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (engineer, 2026-10-20 run). Criteria from ROADMAP §5 'Later — split McpTest.kt': tests split along the tool groups in mcp/, one file per group plus a shared file; same test count before and after, stated; each test moved word for word; budget ratchet records the change and the deferred McpTest.kt line is removed; no assertion changed, no test added.
Split McpTest.kt (784 lines, 43 tests) into inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/: McpProtocolTest (11), TransactionToolsTest (17: get_body, explain), SignalToolsTest (7), SessionToolsTest (1), MarkerToolsTest (1), ExportToolsTest (3), McpAcceptanceTest (3), and the McpToolsFixture base class. Moved by line-range script; 43/43 @Test blocks byte-identical in exactly one new file. Daemon tests 268 before and 268 after, same names, 0 failed. Budget line removed; all new files under 600 lines. Decision files daemon-and-mcp.md and shared-rules.md now name the new classes. PR #102.
<!-- SECTION:NOTES:END -->
