---
id: KIN-133
title: Published POMs carry the dependency versions the locks resolve
status: To Do
assignee: []
created_date: '2026-10-22 16:00'
labels:
  - 'item:pom-version-mapping'
dependencies: []
ordinal: 133000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Fault F1 from the 1.4.0 release rehearsal (KIN-130). kotlin-stdlib is 2.3.20 in 24 POMs (root, -jvm, -iosarm64 and -iossimulatorarm64 of inspector-core, -noop, -ui, -noop-ui, -stream and -noop-stream) while every one of those modules' gradle.lockfile resolves 2.3.21, raised by Ktor 3.5.0 through conflict resolution. The POM carries the version the Kotlin plugin declares; Maven-style consumers get 2.3.20 by nearest-wins under a Ktor built against 2.3.21. Source: the publications.withType<MavenPublication>().configureEach block in the root build.gradle.kts, which has no versionMapping. inspector-model is consistent (no Ktor; 2.3.20 in both). Design by the architect; release.md's rule applies: re-read the generated POM after any change to the POM block.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every published POM's dependency versions match the module's gradle.lockfile.
- [ ] #2 A guard checks the POMs against the locks and is proven red on a POM that disagrees with its lock.
- [ ] #3 The -android POMs are checked where the Android SDK exists (CI, or a machine with the SDK).
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
