---
id: KIN-136
title: >-
  Session diff T1 (reduced): foldIdKeys, SessionStamp, the route-stats and
  Markdown extractions
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 19:25'
updated_date: '2026-10-22 19:44'
labels:
  - 'item:session-diff'
dependencies:
  - KIN-112
  - KIN-127
modified_files:
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/archive/SessionStamp.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/BodyShape.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/RouteStats.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/MarkdownText.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/ExportMarkdown.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/BodyShapeTest.kt
  - inspector-daemon/AGENTS.md
type: feature
ordinal: 136000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 24, docs/ROADMAP.md §6 → Session diff; PR A (lane D), after T4. Design: session-diff note §6.2, §10 and §12 T1, reduced by Shared body evidence (KIN-112: RouteKey.mutation, BodySide, BodyState, BodyEvidence, JsonPaths.redactedPaths) and Shared JSON key paths (KIN-127: JsonPaths.walk, group, isAtOrUnder), both on main. This ticket reuses them and renames nothing they built. What is left: JsonPaths.foldIdKeys, archive/SessionStamp.kt, RouteStatsAccumulator.add returning the key it counted under and rowsByKey extracted from snapshot, and MarkdownText moved out of ExportMarkdown.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 JsonPaths.foldIdKeys writes a grouped path's object keys that RouteTemplates.isIdLike reads as ids as {id}, and keeps a non-id key such as USD literal (a documented miss); BodyShapeTest shown red with the fold calling something other than isIdLike
- [ ] #2 archive/SessionStamp.kt holds SessionStamp(indexSize, indexKey, markersSize) and of(dir); it changes when a row or a marker is appended
- [ ] #3 RouteStatsAccumulator.add returns the RouteKey it counted the row under, or null when the filter dropped it; rowsByKey exists and snapshot uses it; RouteStatsTest, RouteStatsServiceTest and StatsRouteTest pass unedited
- [ ] #4 service/MarkdownText.kt holds cell, inlineCode, heading, ms, bytes and count, moved out of ExportMarkdown, which calls them; ExportTest passes unedited
- [ ] #5 inspector-daemon/AGENTS.md's archive/ row names session stamps; DaemonLayersTest passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
