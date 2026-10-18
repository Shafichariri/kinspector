---
id: KIN-2
title: >-
  Phase D C2-C5: move daemon files into archive/, service/ and http/; Main.kt
  stays
status: To Do
assignee: []
created_date: '2026-10-18 10:50'
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
- [ ] #1 Four commits in order C2 (archive/), C3 (service/), C4 (service/Version.kt), C5 (http/Server.kt), and `./gradlew :inspector-daemon:test :repo-checks:test` passes at each of them
- [ ] #2 `git show -M` on C2, C3 and C5 reports every moved file as a rename with similarity of at least 90, and the rename commits change only `package` lines, `import` lines and the three `@SerialName` pins
- [ ] #3 `DaemonConfig.FileConfig`, `ReplayRequest` and `SignalPullRequest` carry `@SerialName` equal to their pre-D FQNs with the pinned comment, and C1's `SerialNameTest` passes unchanged
- [ ] #4 After C5 `inspector-daemon/src/main/kotlin/dev/inspector/daemon/` holds exactly one file, `Main.kt`, beside the directories `archive/`, `service/`, `http/`, `mcp/` and `usb/`; `usb/` has no diff
- [ ] #5 `daemonVersion()` is declared in `service/Version.kt`; `mcp/McpServer.kt` and `mcp/McpTools.kt` change only that import, and no file under `mcp/` imports `dev.inspector.daemon.http`
- [ ] #6 Every changed line in `git diff <base> -- inspector-daemon/src/test` (headers aside) matches `^[+-]import [A-Za-z0-9_.]+$`, with no `*` and no `as`, including `usb/UsbBridgeTest.kt`; no test file moves or is renamed
- [ ] #7 `structure/budgets.txt` changes in the same commit as each change: hand raises to the measured count with the reason "# phase D Cn: +k import lines" (C2 Server.kt and McpTest.kt; C3 Server.kt, McpTest.kt if it grew), the C4 lowering of Server.kt done with `-Dinspector.budgets.tighten=true` plus McpTest.kt raised by its measured delta, and the C5 Server.kt entry's path hand-edited to `inspector-daemon/src/main/kotlin/dev/inspector/daemon/http/Server.kt`
- [ ] #8 C1's `MainClassTest`, `McpToolsListGoldenTest`, `PeerKillIdentityTest` and `ReadRoutesOpenTest` pass unchanged at C5
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
