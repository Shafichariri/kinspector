---
id: KIN-130
title: Rehearse the 1.4.0 release locally before the tag
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 13:28'
updated_date: '2026-10-22 15:26'
labels:
  - 'item:release-rehearsal'
dependencies: []
ordinal: 130000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §6 → Release 1.4.0 → 'Rehearse the release locally before the tag' (order row 13e, lane S). Run it once findings and detection are merged, and before 13c's last commit. This is the first release since the gradle.lockfiles; release.md records three POM faults that no build failed on. Out of scope: signing with the real key, any upload to Central or GitHub Packages, and workflow changes unless a fault needs one.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Publish to a local repository at the release version (e.g. -Pinspector.version=1.4.0-rc) and read a generated POM from each of the seven published modules: name, coordinates and dependency versions match the locks, and no dependency is missing or added.
- [ ] #2 The daemon zip is built and passes the same smoke test release.yml runs: unzip, serve, /api/sessions and /, and MCP serverInfo.version.
- [ ] #3 On the rehearsal's artifacts, check-release-clean.sh --self-test finds the canary and the real check finds nothing.
- [ ] #4 The delivery tracker records the results in docs/STATUS.md with the commit they ran on; a fault found becomes an item or a fix before the tag.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Rehearsal ran 2026-10-22 on commit 445408e (origin/main), macOS, JDK 21.0.12, no Android SDK, no signing key, nothing uploaded. Ready for QA; the ACs are left unticked for the qa-verifier.

AC#1 (POMs). NOT met as written. One fault and one gap.
- Command: ./gradlew :<m>:publish{KotlinMultiplatform,Jvm,IosArm64,IosSimulatorArm64}PublicationToMavenLocal for all seven modules, with -Pinspector.version=1.4.0-rc -Dmaven.repo.local=<scratch>/m2. Result: BUILD SUCCESSFUL, 28 POMs. A plain publishToMavenLocal fails on this machine before any task runs with "SDK location not found", because the android publication needs the SDK.
- Coordinates: all 28 are io.github.shafichariri:<artifact>:1.4.0-rc. Each <name> is "Inspector <module without inspector->", e.g. "Inspector core" and "Inspector noop-ui". Every POM has description, url, licenses, developers and scm. Every artifact has a javadoc jar and a sources jar. No test-only dependency appears in any POM (kotlin-test, kotlin-reflect, okhttp, ktor-server, coroutines-test). Sibling dependencies are all at 1.4.0-rc and were all published.
- The per-target POMs (jvm, iosarm64, iossimulatorarm64) list exactly the declared main dependencies. compileOnly okhttp is absent, which is correct. Every third-party version in them matches the lock, with ONE EXCEPTION:
- FAULT F1: org.jetbrains.kotlin:kotlin-stdlib is <version>2.3.20</version> in 24 of the 28 POMs (core, noop, ui, noop-ui, stream and noop-stream, at root, jvm, iosarm64 and iossimulatorarm64). Each of those modules' gradle.lockfile has kotlin-stdlib:2.3.21 in every locked configuration, and dependencyInsight shows Ktor 3.5.0 raises it ("conflict resolution: between versions 2.3.21 and 2.3.20"). inspector-model is consistent: it has no Ktor, so 2.3.20 is in both its POM and its lock. The .module files say requires 2.3.20, which is a floor, so a Gradle consumer still resolves 2.3.21. A Maven consumer reading the POM gets 2.3.20 by nearest-wins, while Ktor 3.5.0 needs 2.3.21. The cause is that the Kotlin plugin 2.3.20 declares the stdlib it compiles with. The POM records that declared version; the lock records the resolved one.
- Observation, not a fault: the root (kotlinMultiplatform) POM mirrors the metadataApiElements variant. So inspector-core's root POM omits its commonMain implementation dependencies kotlinx-datetime and kotlinx-serialization-json, while inspector-stream's root POM includes its own because iosMain is a shared source set. The per-target POMs, which are what resolve, are complete.
- NOT VERIFIED: the seven -android POMs. No Android SDK on this machine.
- The comparison script read every POM and printed "faults: 24", all of them kotlin-stdlib.

AC#2 (daemon zip). Met.
- ./gradlew :inspector-daemon:distZip -Pinspector.version=1.4.0-rc built inspector-1.4.0-rc.zip, 11589662 bytes, sha256 13aa37ab529e72cca3f864e2588043b825964c4ae4b89e4ab5c225c30b8b9156.
- I ran release.yml's smoke step verbatim, changing only port 8123 to 18299 and RUNNER_TEMP to scratch. Output: "ok /api/sessions", "ok / has <title>Inspector</title>", "serverInfo.version = 1.4.0-rc", "SMOKE PASSED", exit 0.
- Red direction: the same MCP check with VERSION=1.4.0 printed "MCP serverInfo.version is '1.4.0-rc', expected '1.4.0'" and exited 1.

AC#3 (release-clean). Met on the build outputs, which is what the script scans. One gap found beside it.
- ./scripts/check-release-clean.sh --self-test exited 0 with "SELF-TEST PASSED: canary detected in 5 core artifact(s)", including both iOS klib directories.
- ./scripts/check-release-clean.sh exited 0 with "PASSED: no capture code found in 8 artifact(s)".
- Over the published 1.4.0-rc repository: --paths inspector-noop*/1.4.0-rc exited 0 with "PASSED ... 12 artifact(s)". --paths inspector-core*/1.4.0-rc exited 1 with "CANARY FOUND" in core.jar and core-jvm.jar.
- GAP F2: that core run did NOT flag inspector-core-iosarm64-1.4.0-rc.klib or the iossimulatorarm64 klib. A published .klib is a deflate zip (`file` reports "Zip archive data"). The script unpacks only jar, aar, apk, aab and zip, and then greps the directory, which finds nothing inside a compressed file. The raw grep count on the klib is 0. Unzipped, the canary is in default/ir/strings.knt and default/linkdata/package_dev.inspector/1_inspector.knm. So --paths over published or downloaded iOS klibs is a false clean.
- MINOR F3: with a long absolute --paths the script fails with "mkdir: ... File name too long". The temp directory name is the whole path with / replaced by _, which can exceed NAME_MAX. It then exits 1, the "canary found" code, instead of 2. I worked around it by copying the repository to a short temp path.

AC#4 is the delivery tracker's. A STATUS paragraph is in the hand-over.
<!-- SECTION:NOTES:END -->
