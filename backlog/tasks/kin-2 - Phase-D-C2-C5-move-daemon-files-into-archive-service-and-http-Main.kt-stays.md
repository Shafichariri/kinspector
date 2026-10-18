---
id: KIN-2
title: >-
  Phase D C2-C5: move daemon files into archive/, service/ and http/; Main.kt
  stays
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 10:50'
updated_date: '2026-10-18 12:40'
labels:
  - 'item:structure-phase-d'
dependencies:
  - KIN-1
modified_files:
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/archive
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/service/Version.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/Server.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/McpServer.kt
  - inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/McpTools.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon
  - structure/budgets.txt
type: task
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase D, commits C2 to C5 (design note §1 "File → destination", §4 "Rename hazards", revision 2 "Budgets per commit" and "B1 serial-name pins"). One ticket because the intermediate states have no acceptance of their own: "the packages are as proposed" is only checkable once C5 leaves `Main.kt` alone in the root. They still land as four commits, each green on its own.

- **C2 `archive/`**: `DaemonConfig.kt` (with `InspectorDaemonJson`), `SessionLayout.kt`, `SessionRepository.kt`, `SessionWriter.kt`, `Retention.kt`. `DaemonConfig.FileConfig` gets its pre-D `@SerialName`.
- **C3 `service/`**: `SessionManager.kt`, `LiveApps.kt`, `Replay.kt`, `Replayer.kt`, `PeerDaemons.kt`, `ServerControl.kt`, `McpControl.kt`, `Export.kt`, `ExportMarkdown.kt`, `Har.kt`, `HtmlExport.kt`. `ReplayRequest` and `SignalPullRequest` get their pre-D `@SerialName`.
- **C4**: `daemonVersion()` moves out of `Server.kt` into a new `service/Version.kt`, before `Server.kt` moves. Today it is the only mcp→http edge (`McpServer.kt` and `McpTools.kt` import it).
- **C5**: `Server.kt` → `http/Server.kt`, rename only. `Main.kt` stays in the root: it is the composition root, `MainKt` is the peer argv token and the `application.mainClass`.

`usb/` stays as it is: an explicit fifth package that imports nothing from the daemon. Each serial-name pin carries this comment: "Pinned to the name this class had before phase D. kotlinx-serialization quotes the serial name in MissingFieldException, and the route echoes that message into a 400 body (for FileConfig, to stderr). Moving the class must not change what a caller reads. Do not "correct" this to the current package."

"Every daemon test passes unchanged" was decided at the gate to mean the test diff is import lines only: all 22 test files are in `package dev.inspector.daemon` and reach production classes without imports, so moving a class forces one. Root-package typealias shims were rejected (a second copy of every name, and the scan cannot see edges through them). Not in scope: extracting the delete/clear loop or the signals sort into `service/`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Four commits in order C2 (archive/), C3 (service/), C4 (service/Version.kt), C5 (http/Server.kt), and `./gradlew :inspector-daemon:test :repo-checks:test` passes at each of them
- [x] #2 `git show -M` on C2, C3 and C5 reports every moved file as a rename with similarity of at least 90, and the rename commits change only `package` lines, `import` lines and the three `@SerialName` pins
- [x] #3 `DaemonConfig.FileConfig`, `ReplayRequest` and `SignalPullRequest` carry `@SerialName` equal to their pre-D FQNs with the pinned comment, and C1's `SerialNameTest` passes unchanged
- [x] #4 After C5 `inspector-daemon/src/main/kotlin/dev/inspector/daemon/` holds exactly one file, `Main.kt`, beside the directories `archive/`, `service/`, `http/`, `mcp/` and `usb/`; `usb/` has no diff
- [x] #5 `daemonVersion()` is declared in `service/Version.kt`; `mcp/McpServer.kt` and `mcp/McpTools.kt` change only that import, and no file under `mcp/` imports `dev.inspector.daemon.http`
- [x] #6 Every changed line in `git diff <base> -- inspector-daemon/src/test` (headers aside) matches `^[+-]import [A-Za-z0-9_.]+$`, with no `*` and no `as`, including `usb/UsbBridgeTest.kt`; no test file moves or is renamed
- [x] #7 `structure/budgets.txt` changes in the same commit as each change: hand raises to the measured count with the reason "# phase D Cn: +k import lines" (C2 Server.kt and McpTest.kt; C3 Server.kt, McpTest.kt if it grew), the C4 lowering of Server.kt done with `-Dinspector.budgets.tighten=true` plus McpTest.kt raised by its measured delta, and the C5 Server.kt entry's path hand-edited to `inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/Server.kt`
- [x] #8 C1's `MainClassTest`, `McpToolsListGoldenTest`, `PeerKillIdentityTest` and `ReadRoutesOpenTest` pass unchanged at C5
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
C2 8e47dc8 (archive/), C3 7894a6f (service/), C4 dfb310f (service/Version.kt), C5 <see next note> (http/Server.kt). Each green: daemon 235, repo-checks 18. Renames 93-99% similarity. Moves done by a script that git-mv's, rewrites the package line, and adds only the imports the compiler reports unresolved (existing dev.inspector.daemon.X imports rewritten in place). Found: Retention collides with the default import kotlin.annotation.Retention, so moving it out of a file's package would rebind silently rather than fail; every file using it got an explicit import (checked the C3 names against java.lang and kotlin default packages: no other collision). SessionLayout.kt holds a literal NUL char at base (line 66), so git shows it as binary; use git diff --text to review it. Test diff vs 1db168b: pre-existing files import-only (105 lines at C5); the C1 guard files changed only by imports since C1. Interpretation: 'C1 guards pass unchanged' read under decision A, i.e. import lines only.

C5 is cda3226. KIN-2 ready for QA once the item is done.

Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: four commits, each green. Renames 93-99% similarity; only other changes are the 3 @SerialName pins and budgets. Pins checked live: replay with {} gives the same serial name as base, config stderr identical. Root holds only Main.kt, usb has no diff, Version.kt in place. Test diff is imports only (105 lines). Per-commit budgets: Server.kt 917>924>948>939, McpTest 810>814>815. DoD #2: no new guard in this ticket. DoD #3 (no client names) is NOT verified and left unticked: the term list is deliberately kept out of the repo, so it cannot be checked here by design; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
