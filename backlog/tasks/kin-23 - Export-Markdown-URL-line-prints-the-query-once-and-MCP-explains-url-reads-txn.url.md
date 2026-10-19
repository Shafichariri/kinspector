---
id: KIN-23
title: >-
  Export: Markdown URL line prints the query once, and MCP explain's url reads
  txn.url
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 19:18'
updated_date: '2026-10-18 19:28'
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
- [x] #1 An ExportTest Markdown test with a query-bearing row (query a=1&b=2) asserts the URL line is exactly - **URL:** `https://api.example.com/v2/users/me?a=1&b=2` — the query appears once. It fails with the old line put back.
- [x] #2 A row with query = null renders the URL line with no '?'.
- [x] #3 A row with an empty query ('?' and nothing after) renders …/v2/users/me? — one '?', as the web's urlOf does per docs/decisions/shared-rules.md. It fails with the old line put back (old output: '??').
- [x] #4 A McpTest explain test on a row with port 8080 and query a=1 asserts call.url equals that row's NetworkTransaction.url (https://api.example.com:8080/…?a=1). It fails with ExplainTool's old hand-built url put back.
- [x] #5 No other change to the Markdown layout; the HTML and JSON exports are untouched.
- [ ] #6 The release notes carry one line.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by engineer (bob). Fix: ExportMarkdown URL line -> inlineCode(txn.url); ExplainTool CallLine.url -> txn.url.
Tests: ExportTest "the URL line carries the query once", "the URL line has no question mark without a query", "an empty query is one question mark, as the web UI draws it"; McpTest explain_reports_the_full_url_with_port_and_query.
Red proofs (new tests run against the old lines, 70 tests in the two classes, 3 failed):
- carries the query once: expected <- **URL:** `https://api.example.com/v2/users/me?a=1&b=2`> but was <- **URL:** `https://api.example.com/v2/users/me?a=1&b=2?a=1&b=2`>
- empty query: expected <...me?`> but was <- **URL:** `https://api.example.com/v2/users/me??`>
- explain: expected <https://api.example.com:8080/v2/users/me?a=1> but was <https://api.example.com/v2/users/me>
- null query: passes on old and new (AC #2 asks for no failure; it pins the no-? case).
Green: ./gradlew :inspector-daemon:test --rerun -> BUILD SUCCESSFUL, 263 tests, 0 failures, 0 errors, 0 skipped.
AC #6 (release notes): not written in this build; release notes are cut by the release-steward at release time. The line is in the description: "Daemon: the Markdown export's URL line no longer repeats the query string, and MCP explain reports the full URL, port and query included."

AC #6 deferred to the next release's notes, owned by the release-steward. The line is in the description. DoD #3 (no client names) left unticked: the hygiene scan is the release-steward's and has not been run on this ticket.

Follow-up bug KIN-26: the explain test added in 2b1b650 pushes McpTest.kt to 830 lines against a budget of 815, so :repo-checks:test fails (FileBudgetTest). Only :inspector-daemon:test was run at the time.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug) at 2b1b650. AC1-5 Verified: red proofs re-run (old lines fail: '?a=1&b=2?a=1&b=2', '??', explain url without port/query); ./gradlew :inspector-daemon:test -> 263 tests, 0 failures; real-session Markdown export diff changed exactly 8 URL lines; MCP binaries compared old vs new. AC6 not verified (no release notes exist yet). DoD3 pending hygiene scan.
<!-- SECTION:FINAL_SUMMARY:END -->
