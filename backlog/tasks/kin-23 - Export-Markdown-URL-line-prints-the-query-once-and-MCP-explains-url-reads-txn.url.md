---
id: KIN-23
title: >-
  Export: Markdown URL line prints the query once, and MCP explain's url reads
  txn.url
status: To Do
assignee: []
created_date: '2026-10-18 19:18'
labels:
  - 'item:export-md-query-once'
dependencies: []
modified_files:
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/ExportMarkdown.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/ExplainTool.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ExportTest.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/McpTest.kt
type: bug
ordinal: 23000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ExportMarkdown.kt:158 builds the URL line as txn.url + (txn.query?.let { "?$it" } ?: ""), but NetworkTransaction.url already appends '?' + query (Transaction.kt:111), so every call with a query string reads …/path?a=1?a=1, and an empty query reads …/path??. Fix: the line renders inlineCode(txn.url) and nothing else. Same cause found in MCP explain: ExplainTool.kt:104 hand-builds CallLine.url as "${txn.scheme}://${txn.host}${txn.path}", which drops the query and a non-default port — the exact defect docs/decisions/capture.md records ('a capture of 127.0.0.1:8080 rendered as http://127.0.0.1. Every copied cURL for a non-default port was silently wrong'). Fix: url = txn.url. HAR (Har.kt:120), Replayer, the overlay and the web urlOf already read the shared rule and are unaffected. Daemon only; no public API, no wire or schema change, no recorded decision changes. Release notes, one line: 'Daemon: the Markdown export's URL line no longer repeats the query string, and MCP explain reports the full URL, port and query included.'
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 An ExportTest Markdown test with a query-bearing row (query a=1&b=2) asserts the URL line is exactly - **URL:** `https://api.example.com/v2/users/me?a=1&b=2` — the query appears once. It fails with the old line put back.
- [ ] #2 A row with query = null renders the URL line with no '?'.
- [ ] #3 A row with an empty query ('?' and nothing after) renders …/v2/users/me? — one '?', as the web's urlOf does per docs/decisions/shared-rules.md. It fails with the old line put back (old output: '??').
- [ ] #4 A McpTest explain test on a row with port 8080 and query a=1 asserts call.url equals that row's NetworkTransaction.url (https://api.example.com:8080/…?a=1). It fails with ExplainTool's old hand-built url put back.
- [ ] #5 No other change to the Markdown layout; the HTML and JSON exports are untouched.
- [ ] #6 The release notes carry one line.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
