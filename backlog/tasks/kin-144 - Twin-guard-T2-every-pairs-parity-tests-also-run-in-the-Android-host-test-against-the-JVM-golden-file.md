---
id: KIN-144
title: >-
  Twin guard T2: every pair's parity tests also run in the Android host test,
  against the JVM golden file
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 20:37'
updated_date: '2026-10-22 20:37'
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
