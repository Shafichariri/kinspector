---
id: KIN-144
title: >-
  Twin guard T2: every pair's parity tests also run in the Android host test,
  against the JVM golden file
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 20:37'
updated_date: '2026-10-22 21:11'
labels:
  - 'item:guard-twin-native-surface'
dependencies: []
type: feature
ordinal: 144000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 25, docs/ROADMAP.md §3 → Guards (item:guard-twin-native-surface), Android half, from the architect's design note. Android output is JVM bytecode, so ApiSurface reads it as it is; today every platform split that reaches Android also reaches the JVM (jvmAndAndroidMain, and @file:JvmName("StreamSinkKt") in both Platform.android.kt and Platform.jvm.kt), so the Android surface equals the JVM surface and the host test asserts each pair's existing JVM golden file read-only. A bonus: the Android facade pin is guarded for the first time. The first Android-only API added to both twins splits the file then. Independent of T1. No plugin, dependency, download or lockfile change. Proven on CI only: there is no Android SDK on the development machine.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 All six twin modules run the pair's golden test and completeness test in testAndroidHostTest: withHostTest {} in inspector-ui, inspector-noop-ui and inspector-noop-stream; androidHostTest gets the same api-parity srcDirs as jvmTest and kotlin("reflect") (and okhttp in inspector-noop)
- [ ] #2 testAndroidHostTest declares the pair's JVM golden file as an input, and inspector.api.regenerate reaches jvmTest only, so the host test can never rewrite the golden file
- [ ] #3 Proven red on CI from a throwaway branch that is never merged: a public androidMain function in all six modules fails all six host tests (A1); a public function on Platform.android.kt fails the stream golden test (A2); deleting @file:JvmName("StreamSinkKt") from Platform.android.kt fails :inspector-stream:testAndroidHostTest (A3)
- [ ] #4 On the PR's green run the six host-test reports list the pair test classes with both tests each (A4)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built per the design note; ready for QA. withHostTest {} added to inspector-ui, inspector-noop-ui and inspector-noop-stream. androidHostTest in all six modules gets the api-parity srcDirs, kotlin("test") and kotlin("reflect"), plus libs.okhttp in inspector-noop as the note said, and in inspector-core too. The note missed core: OkHttp is compileOnly there as well, and CI run 39851887987 failed core's host test on okhttp3.Interceptor and okio.Sink until it was added (811d7c3). The pair's JVM golden file is an input of testAndroidHostTest, matched by name because AGP registers the task after the module script. inspector.api.regenerate now reaches jvmTest only.
ui's 10 commonTest files compile and pass on the Android host: 70 tests, 0 failed besides the probe.
Proofs on CI, throwaway branch shafic/throwaway-android-twin-proof, deleted and never merged, its ci.yml reduced to ./gradlew testAndroidHostTest --continue. A1 (run 39852405138): all six host tests red in every_public_declaration_is_in_the_contract, + dev.inspector.probe.AndroidOnlyProbeKt [androidOnlyProbe]. A2 (same run): stream's golden test red, + dev.inspector.stream.StreamSinkKt: fun androidFacadeProbe(): int and jvm androidFacadeProbe()I. A3 (run 39852841927): with @file:JvmName("StreamSinkKt") deleted, only :inspector-stream:testAndroidHostTest is red: Contract class dev.inspector.stream.StreamSinkKt is not on this module's classpath. The other five pass on that commit. A4: PR #182's run 39852394920 is green and executed all six iosApiTest and all six testAndroidHostTest tasks. The A3 run's reports list both pair tests in each of the six host tests, and no IosApiParityTest class.
<!-- SECTION:NOTES:END -->
