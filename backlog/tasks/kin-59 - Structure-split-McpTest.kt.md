---
id: KIN-59
title: 'Structure: split McpTest.kt'
status: Done
assignee: []
created_date: '2026-10-20 20:26'
updated_date: '2026-10-21 10:50'
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
- [x] #1 Every acceptance criterion of the roadmap item labelled item:structure-split-mcp-test in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (engineer, 2026-10-20 run). Criteria from ROADMAP §5 'Later — split McpTest.kt': tests split along the tool groups in mcp/, one file per group plus a shared file; same test count before and after, stated; each test moved word for word; budget ratchet records the change and the deferred McpTest.kt line is removed; no assertion changed, no test added.
Split McpTest.kt (784 lines, 43 tests) into inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/: McpProtocolTest (11), TransactionToolsTest (17: get_body, explain), SignalToolsTest (7), SessionToolsTest (1), MarkerToolsTest (1), ExportToolsTest (3), McpAcceptanceTest (3), and the McpToolsFixture base class. Moved by line-range script; 43/43 @Test blocks byte-identical in exactly one new file. Daemon tests 268 before and 268 after, same names, 0 failed. Budget line removed; all new files under 600 lines. Decision files daemon-and-mcp.md and shared-rules.md now name the new classes. PR #102.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #102, merge 49735be, snapshots of head and base. Split along mcp/ tool groups, one file each plus McpToolsFixture: McpTest.kt gone. Count: ':inspector-daemon:test --rerun' base 268/0 failed, head 268/0, sorted test-name lists identical (diff empty). Word for word: 43/43 @Test bodies byte-identical (indent normalised); git diff 82b48f9 49735be -- inspector-daemon/src/main is empty. Ratchet: only the McpTest budget line removed; all new files under 600 lines (largest 286); ':repo-checks:test --rerun' 35/35; -Dinspector.budgets.tighten=true leaves the tree unchanged. Red: padding TransactionToolsTest.kt to 616 lines makes ':repo-checks:test' fail (35 tests completed, 1 failed); restored, shasum identical. Identity check 3 commits pass; client-name scan clean. Not run locally: full ./gradlew build (no Android SDK); CI build, release-swap, repo, web pass on the PR and the main push on 49735be succeeded. Non-blocking: fixture members widened private to protected, int() to internal (test source set only).
<!-- SECTION:FINAL_SUMMARY:END -->
