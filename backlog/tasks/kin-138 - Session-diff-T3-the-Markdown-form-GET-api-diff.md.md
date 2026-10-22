---
id: KIN-138
title: 'Session diff T3: the Markdown form, GET /api/diff.md'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:25'
updated_date: '2026-10-22 20:01'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-137
modified_files:
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/DiffMarkdown.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/DiffRoutes.kt
  - docs/decisions/exports.md
type: feature
ordinal: 138000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR A (lane D), after T2. Design: session-diff note §7.3, §12 T3 and §14 (the exports.md paragraph). Rendered by service/DiffMarkdown.kt from the same report the JSON route returns, through MarkdownText.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 GET /api/diff.md answers text/markdown with Content-Disposition attachment; filename="diff-<a>-vs-<b>.md", and errors as JSON with §7.2's statuses
- [ ] #2 The document has §7.3's header, its sections in order, escaped cells, and an unknown body never described as removed (DiffMarkdownTest, red with unsure keys rendered under removed and with MarkdownText.cell bypassed)
- [ ] #3 The exports.md paragraph of the design's §14 is in
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once PR A merges. service/DiffMarkdown.kt, GET /api/diff.md (text/markdown; charset=UTF-8, attachment diff-<a>-vs-<b>.md, errors as JSON), inspector diff --md. An unsure key is under 'Unknown — capture did not keep every body', never 'Removed'; a shape not compared reads 'unknown (1 truncated)', and two known non-JSON kinds read as the kinds ('empty', 'json → empty'). Red: unsure keys rendered under 'Removed: only in A'; cells joined without MarkdownText.cell (an unescaped pipe). exports.md paragraph in. -- bob
<!-- SECTION:NOTES:END -->
