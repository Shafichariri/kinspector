---
id: KIN-25
title: >-
  Signals: GET …/signals reads SessionRepository.querySignals, so a filter
  reaches the signals and the route holds no rule
status: To Do
assignee: []
created_date: '2026-10-18 19:27'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-24
modified_files:
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/SignalRoutes.kt
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/archive/SessionRepository.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/SignalQueryTest.kt
  - scripts/render-web-ui.js
  - .github/workflows/ci.yml
  - structure/budgets.txt
  - docs/decisions/daemon-and-mcp.md
  - docs/decisions/signals.md
type: feature
ordinal: 25000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Daemon half of item:signals-query-one-rule, and the ticket that changes what users see. docs/decisions/daemon-and-mcp.md: 'A rule both adapters serve lives in service/ or archive/, never in a route or tool body.' The route in http/SignalRoutes.kt filters on tag with equals(ignoreCase = true), sorts by mono descending and clamps limit to 1..1000 itself, and ignores the filter app.js sends. Fix: the route parses filter, offset (default 0), limit (default 100, unchanged) and tag, calls repository.querySignals(dir, filter, offset, limit, tag) and answers page.items as the same bare array, newest first. A FilterParseException is a 400 carrying the parser's message, as the transactions route does. querySignals gains tag: String? = null, ANDed in as a CONSTRUCTED TagTerm(tag) (Filter.And), never concatenated into the filter text: a tag value containing '|' or whitespace would otherwise rewrite the query. The sort and the 1..1000 clamp stay where they are, in querySignals, once. The limit decision: the clamp is not raised; the web pages (KIN-24). MCP list_signals is untouched: same arguments, same page shape, same 1..200 argument range. Behaviour change: on the web, a filter now drops signals from the timeline tab and the path view's visit lines by the grammar's exclusion rule (path: drops every signal, tag:screen drops every call and every other tag); tabs, tag browsers and the glance still describe the whole session (KIN-24). A REST caller sees ?filter= honoured and a bad filter refused 400 instead of ignored. Record: daemon-and-mcp.md's layering paragraph drops the signals violation ('Two places break that today' becomes the session delete loop alone) and says the route and the tool read querySignals, with tag= as a constructed term and SignalQueryTest as the pin; signals.md's 'The web UI has always sent its filter to the signal route' gains that the route ignored it until this item. Release notes, one line: 'Web UI: a filter now applies to signals on the timeline and path views, and a session with more than 1000 signals shows all of them.' No public API, wire or schema change.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The route gets its rows from the same function list_signals uses (SessionRepository.querySignals), so the sort, the clamp and the filter exist in one place; SignalRoutes.kt's list route has no sort, clamp or tag comparison of its own.
- [ ] #2 tag= keeps working: ?tag=screen and ?tag=SCREEN return the screen signals; ?tag=screen&filter=name:Route is their AND; a tag value containing ' | path:x' returns only signals with that exact tag (none), proving the tag is a term and not text. The existing RestApiTest signals_are_served_newest_first_and_filterable_by_tag passes unchanged.
- [ ] #3 The route still answers with a bare array, newest first; the export's archiveSource mirrors that shape and render-web-ui-archive.js holds it (KIN-24's checks stay green).
- [ ] #4 A new daemon test file (SignalQueryTest.kt, not McpTest.kt, which is over its size budget and next to be split) asks the route and list_signals for the same session and filters ('', tag:screen, name:Route, text:pair, since:marker(...), path:/v2, 'tag:screen | path:/v2', 'status:500 | tag:cache') and asserts the same ids in the same order. Its session has mono ties written out of archive order and ts disagreeing with mono. It fails with the route's old body put back, naming the filter it diverged on, and fails with a route sort by ts.
- [ ] #5 A session with 1205 signals (1100 screen, 105 cache): ?limit=2000 returns the 1000 newest; ?offset=1000&limit=1000 returns the other 205 including the oldest; paging with filter=tag:screen returns exactly 1100; no id twice.
- [ ] #6 ?filter=bogus:1 is a 400 whose body carries the parser's message (Unknown filter key 'bogus') verbatim.
- [ ] #7 A filter typed in the web UI drops signals by the grammar's exclusion rule, checked by render-web-ui.js on the timeline tab: a path: filter matching at least one call leaves at least one call row and zero signal rows; tag:screen leaves zero call rows and at least one signal row, every one in the screen lane; clearing restores the unfiltered counts; and the tab bar's view list is identical before, during and after. The signal-row checks fail with the old route put back; the tab check fails with 20-navigation.js reading state.signals. UI_EXPECT_CHECKS moves by the number added, and budgets.txt raises render-web-ui.js by hand with the reason.
- [ ] #8 MCP list_signals' arguments and page shape are unchanged; McpToolsListGoldenTest passes untouched.
- [ ] #9 daemon-and-mcp.md and signals.md carry the edits above; DecisionsTest passes. The release notes carry one line.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
