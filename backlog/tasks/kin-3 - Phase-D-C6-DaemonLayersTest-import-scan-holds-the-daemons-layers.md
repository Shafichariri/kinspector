---
id: KIN-3
title: 'Phase D C6: DaemonLayersTest import scan holds the daemon''s layers'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 10:50'
updated_date: '2026-10-18 12:40'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-2
modified_files:
  - repo-checks/src/test/kotlin/dev/inspector/repochecks/DaemonLayersTest.kt
type: task
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commit C6 (design note §5 "Import scan", revision 2 "Scan revised"). Kotlin `internal` is module-wide, so packages alone enforce nothing; an import scan in `:repo-checks` holds the layers. It lands deliberately before the splits, so they are made under the guard.

`DaemonLayersTest.kt` reads `RepoFiles.text` filtered to `inspector-daemon/src/main/kotlin/` (tests exempt). A file's layer is the first directory under `dev/inspector/daemon/`, prefix-matched. Every layer may import `kotlin.`, `kotlinx.serialization.`, `kotlinx.coroutines.`, `java.`, `dev.inspector.model.` and its own prefix. Beyond that:

| Layer | May also import |
|---|---|
| archive | nothing |
| service | archive |
| http | archive, service, `io.ktor.` |
| mcp | archive, service (no `io.ktor`) |
| usb | `javax.xml.`, `org.w3c.dom.`, `org.xml.sax.`; no daemon package (the bridge goes through `WS /ingest`) |
| root | anything, but only `Main.kt` |

Adapters (http, mcp) may read `archive/` directly; strict adapters→service only would need pass-through wrappers for about 25 `SessionRepository` methods, a second copy of each.

Rules: L0 a filesystem walk of `inspector-daemon/src/main/` compared with the `git ls-files` list (a file on disk not in the list fails; any `.java` under `src/main` fails; any `.kt` outside `src/main/kotlin/dev/inspector/daemon/` fails). L1 the root holds exactly `Main.kt`. L2 an unknown layer directory fails and names the table. L3 the `package` line matches the directory. L4 the first token of every import (alias stripped) matches the allowlist, which catches wildcards, and nothing may import the root. L5 inline qualified names, matched by `(?<![\w.])(io\.ktor|dev\.inspector\.daemon\.(archive|service|http|mcp|usb))(\.\w+)+` over the whole text after normalising `\s*\.\s*` to `.` and dropping backticks; comment false positives are accepted; reports file and name, plus line where recoverable. L6 per-mechanism non-vacuity: archive, service, http and mcp each hold at least one file; L4 found at least one `io.ktor` import in `http/` and at least one archive or service import in `mcp/`; L5 found at least as many `io.ktor` names in `http/` as L4 found import lines (the matcher runs over import lines too); L0 walked at least one file.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 `repo-checks/src/test/kotlin/dev/inspector/repochecks/DaemonLayersTest.kt` exists, built on a pure function `layerViolations(files)`, and encodes the layer table and rules L0 to L6 from the description
- [x] #2 Permanent synthetic cases each produce a violation: service imports `io.ktor.http.HttpStatusCode`; archive names `io.ktor…Route` inline; http imports mcp; mcp imports http; archive imports service; usb imports service; `import io.ktor.*` in service; a service file declaring `package …http`; an extra file in the root; a qualified name split across lines; a `.java` file; a file on disk that is not in git
- [x] #3 A clean synthetic tree gives 0 violations, an empty tree fails L6, and an input that breaks the L4 parser trips L6
- [x] #4 The live run over the repository reports 0 violations
- [x] #5 One-off proof for the roadmap criterion, shown red in the PR and then restored: adding `import io.ktor.http.HttpStatusCode` to a real file under `service/` fails the test, and adding `import dev.inspector.daemon.http.InspectorDaemon` to a real file under `mcp/` fails it
- [x] #6 `./gradlew :inspector-daemon:test :repo-checks:test` passes and the daemon test diff is unchanged by this commit
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C6 committed as 730aceb. repo-checks 18 -> 35 (17 DaemonLayersTest cases). Live: 0 violations. Proven: real-file breaks in service/ and mcp/ (AC #5), blind reader and blind matcher trip L6, an ignored file trips L0. Files read directly, not via RepoFiles.text, because archive/SessionLayout.kt holds a NUL byte and RepoFiles.text skips such files. Interpretation: 'nothing may import the root' is enforced as L4 refusing dev.inspector.daemon.X / dev.inspector.daemon.* from any non-root layer. An import line the reader cannot parse is itself an L4 violation, so nothing is skipped silently.

Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: pure layerViolations covers L0-L6, 17/17 synthetic cases; live run 0 violations. Real-file one-offs go red (L4 on service/Har.kt and mcp/McpServer.kt) plus 15+ further breaks including a split-line FQN; L6 blinding goes red. DoD #3 (no client names) is NOT verified and left unticked: the term list is deliberately kept out of the repo, so it cannot be checked here by design; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
