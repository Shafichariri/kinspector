---
id: KIN-143
title: >-
  Twin guard T1: every pair's iOS surface is read from its klib and asserted
  against an iOS golden file
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 20:37'
updated_date: '2026-10-22 20:48'
labels:
  - 'item:guard-twin-native-surface'
dependencies: []
type: feature
ordinal: 143000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 25, docs/ROADMAP.md §3 → Guards (item:guard-twin-native-surface), iOS half, from the architect's design note. Every parity test reads JVM output, so a public declaration added to iosMain of one module of a twin pair and not to the other passes every pair's test; the consuming team finds out when its release build fails on iOS alone. Mechanism: the pinned Kotlin/Native distribution's own `klib dump-abi` (already provisioned in ~/.konan by every iOS compile), read by a JVM test in api-parity/ (KlibSurface.kt), against one new golden file per pair (api/inspector-public-api.ios.txt, api/inspector-stream-public-api.ios.txt, api/inspector-ui-public-api.ios.txt), named in a fourth column of structure/twins.txt. KGP's abiValidation is rejected (four new artifacts, one third-party). No plugin, dependency, download or lockfile change. Out of scope: binary compatibility between releases; Native partial linkage; parameter names (absent from klib dumps). Size M with T2.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Each of the six twin modules has an iosApiTest task that check depends on; it runs klib dump-abi from the Kotlin/Native distribution matching the pinned Kotlin version (libs.versions.kotlin, never the stdlib's version) on both iOS targets' klibs, and asserts the result against its pair's iOS golden file
- [ ] #2 The normalisation drops the dump's header and IR signatures, flattens every member to <enclosing declaration>: <declaration>, drops $stableprop lines by that name only, and for the stream pair reads a Ktor type inside kotlin/FunctionN<...> as kotlin/Any? and then drops lines naming io.ktor.
- [ ] #3 The guard fails, never passes, when the klib tool is not found (naming every path tried), when a klib dir is missing or holds another library (unique-name check), when the two iOS targets disagree (naming both), when the result is empty, and when the golden file is empty
- [ ] #4 structure/twins.txt names each pair's iOS golden file in a fourth column, and NoopTwinTest fails a pair whose fourth file is unnamed or does not exist
- [ ] #5 Each iOS golden file is a declared input of both modules' iosApiTest, so a hand-edited golden file fails without --rerun
- [ ] #6 Proven red then green on macOS: a public iosMain function in each of the six modules (I1); a public val in inspector-stream's iosSimulatorArm64Main only (I2); the detector's own failures (I3); each normalisation rule removed in turn (I4); a hand-edited golden file (I5)
- [ ] #7 api-parity/AGENTS.md lists the three iOS regenerate commands and golden files; docs/decisions/twins-and-api.md carries the decision paragraph; CI's macOS build job runs all six iosApiTest tasks
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built per the design note; ready for QA. Six iosApiTest tasks (root build.gradle.kts), KlibSurface.kt, three api/*.ios.txt goldens (104, 25 and 1 entries), fourth column in structure/twins.txt, NoopTwinTest extended.
Proofs, local macOS, each red then green: I1 iosOnlyProbe in each of the six modules (+ final fun dev.inspector.probe/iosOnlyProbe(): kotlin/Int); I2 a val in stream's iosSimulatorArm64Main only (differs between targets); I3 empty konan home, the stdlib's 2.3.21 version, klib dir pointed at the twin, missing klib dir, empty golden; I4 $stableprop rule removed (26 lines), Function-type rewrite removed (constructor missing), flattening removed (stop() moved into StreamState passes; restored, red); I5 hand-edited golden fails without --rerun, and stays UP-TO-DATE green with the input declaration removed. NoopTwinTest: a missing iOS file fails it; a pair with no fourth column fails configuration with its own message.
Finding: in inspector-core and inspector-noop, src/iosMain is not compiled. Their explicit dependsOn edges turn the default hierarchy template off, so a file there that is not Kotlin builds clean. I1 for those two used iosArm64Main + iosSimulatorArm64Main. Recorded in twins-and-api.md; not fixed (out of scope).
Not verified: parameter names (absent from dumps); the stream constructor is a source-level match only (IR signatures differ); no cross-twin partial-linkage coverage.
<!-- SECTION:NOTES:END -->
